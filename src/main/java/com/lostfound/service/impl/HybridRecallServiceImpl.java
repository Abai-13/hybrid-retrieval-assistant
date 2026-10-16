package com.lostfound.service.impl;

import com.lostfound.entity.Item;
import com.lostfound.service.CandidateService;
import com.lostfound.service.HybridRecallService;
import com.lostfound.service.ItemService;
import com.lostfound.service.ItemVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合召回实现 —— 两路各跑各的，按物品 id 去重后用 RRF 融合，再按 topK 截断。
 *
 * <p>调用顺序刻意是「先拿 2-gram，再决定要不要跑向量」：
 * 2-gram 恒定可用且快（内存里打分），向量腿可能因为各种原因不可用 ——
 * 先跑它，向量腿挂了也已经有一份能用的结果，回退是<b>免费</b>的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRecallServiceImpl implements HybridRecallService {

    /**
     * RRF 常数 k = 60（计划定的值）。
     * <p>k 决定「名次差一分值差多少」：k 越大，第 1 名和第 10 名的差距越平缓，
     * 两路的共识越被看重。60 是文献里的常用默认值，没有针对本项目调过 ——
     * 要调得先有基线数据（约定 17），现在调它属于没有依据的微调。
     */
    private static final int RRF_K = 60;

    /**
     * 跑向量腿所需的最小剩余预算（毫秒）。
     * <p>云端 embedding 实测 200~300ms，Qdrant 本地几毫秒，但云端也可能抖到秒级 ——
     * 剩余预算连这些都盖不住时，跑向量腿只会把「还来得及给出结果」的那一轮拖成超时。
     * 宁可这一轮只用 2-gram，也不能让增强路径拖垮主路径。
     */
    private static final long MIN_VECTOR_BUDGET_MS = 3_000L;

    /** 向量点数上限 —— 挡住把 overfetch 配成离谱值，一次检出几百条只是白花钱 */
    private static final int MAX_VECTOR_LIMIT = 200;

    private final CandidateService candidateService;
    private final ItemVectorService itemVectorService;
    private final ItemService itemService;

    /** 混合召回总开关（关掉 = 助手完全回到改造前的 2-gram 行为） */
    @Value("${ai.vector.enabled:true}")
    private boolean vectorEnabled;

    /**
     * 向量腿取几倍候选再回 MySQL 过滤（默认 3 → 50 个槽位先取 150 条）。
     * <p>为什么必须多取：向量库和 MySQL 是<b>异步同步</b>的，
     * 检索回来的 id 里会有「已经认领走了 / 已经删了 / 刚好被排除」的，
     * 直接取 topK 条再过滤，最后可能连 K 条都不剩 —— 而这些空缺本该由
     * 排在后面的正常候选补上。多取一些，过滤完还够用。
     */
    @Value("${ai.vector.overfetch:3}")
    private int overfetch;

    @Override
    public CandidateService.RecallResult recall(String query, int topK, Set<Long> excludeIds,
                                                long deadlineEpochMs) {
        long start = System.currentTimeMillis();
        Set<Long> excluded = excludeIds == null ? Set.of() : excludeIds;

        // 2-gram 这一路和改造前逐字相同 —— 它是回退路径，必须先拿到手
        CandidateService.RecallResult lexical =
                candidateService.recallDetailed(query, Item.TYPE_FOUND, topK, excluded);
        if (!vectorEnabled || topK <= 0) {
            return lexical;
        }

        long remaining = deadlineEpochMs - start;
        if (remaining < MIN_VECTOR_BUDGET_MS) {
            log.info("混合召回: 剩余预算 {} ms 低于 {} ms，跳过向量腿，直接用 2-gram {} 条",
                    remaining, MIN_VECTOR_BUDGET_MS, lexical.candidates().size());
            return lexical;
        }

        int limit = (int) Math.min((long) topK * Math.max(1, overfetch), MAX_VECTOR_LIMIT);
        List<Long> vectorIds = itemVectorService.searchIds(query, limit);
        if (vectorIds == null || vectorIds.isEmpty()) {
            // null = 向量路径不可用（回退），空 = 语义上一条都不像（正常结果）
            // 两种情况都用 2-gram 结果，但日志要分得清
            log.info("混合召回: 向量腿 {}（2-gram {} 条），耗时 {} ms",
                    vectorIds == null ? "不可用" : "无命中",
                    lexical.candidates().size(), System.currentTimeMillis() - start);
            return lexical;
        }

        // 向量腿的结果一律回 MySQL 复核：向量库只存 id，「还存不存在、还是不是招领、
        // 还能不能被申请」这些事实只有 MySQL 说了算（索引滞后时点还在、行可能已经没了）
        List<Long> freshIds = vectorIds.stream()
                .filter(id -> !excluded.contains(id))
                .distinct()
                .toList();
        Map<Long, Item> freshItems = freshIds.isEmpty() ? Map.of() : itemService.mapByIds(freshIds);
        List<Item> vectorItems = new ArrayList<>(freshIds.size());
        int droppedStale = 0;
        for (Long id : freshIds) {
            Item item = freshItems.get(id);
            if (item == null || !Item.TYPE_FOUND.equals(item.getType())
                    || !matchable(item.getStatus())) {
                droppedStale++;
                continue;
            }
            vectorItems.add(item);
        }

        // ===================== RRF 融合 =====================
        // 两路各自按名次计分，按 id 去重后相加；分数相同时保持插入顺序（先 2-gram 后向量）
        Map<Long, Item> pool = new LinkedHashMap<>();
        Map<Long, Double> scores = new LinkedHashMap<>();
        int rank = 1;
        for (Item item : lexical.candidates()) {
            pool.put(item.getId(), item);
            addRrf(scores, item.getId(), rank++);
        }
        rank = 1;
        for (Item item : vectorItems) {
            pool.putIfAbsent(item.getId(), item);
            addRrf(scores, item.getId(), rank++);
        }
        List<Long> rankedIds = new ArrayList<>(scores.keySet());
        // List.sort 是稳定排序 → RRF 同分时按上面的插入顺序（2-gram 优先），结果可复现
        rankedIds.sort(Comparator.comparingDouble(scores::get).reversed());
        List<Item> top = rankedIds.stream().limit(topK).map(pool::get).toList();

        // 只记条数/耗时/回退情况，不记 query 原文（用户描述含地点等私密信息）
        log.info("混合召回: 2-gram {} 条 + 向量 {} 条（复核剔除 {} 条过期/不可申请）→ 融合去重 {} 条 → 取 Top{}，耗时 {} ms",
                lexical.candidates().size(), vectorIds.size(), droppedStale,
                pool.size(), top.size(), System.currentTimeMillis() - start);
        // poolSize / excludedCount 沿用 2-gram 那一路的口径：诊断数字跨改造前后可比，
        // 混合检索自己那部分的条数在上面这行日志里
        return new CandidateService.RecallResult(top, lexical.poolSize(), lexical.excludedCount());
    }

    /**
     * RRF 计分：{@code score += 1 / (k + rank)}，rank 从 1 起。
     * <p>出现在两路里的候选会把两边的分加起来 —— 这正是 RRF 想要的：
     * 「两路都认为它靠前」比「只有一路认为它靠前」更可信。
     */
    private static void addRrf(Map<Long, Double> scores, Long itemId, int rank) {
        scores.merge(itemId, 1.0d / (RRF_K + rank), Double::sum);
    }

    /** 还能被申请的状态：UNCLAIMED（没人申请）与 CLAIMING（有人申请但还没归还） */
    private static boolean matchable(String status) {
        return Item.STATUS_UNCLAIMED.equals(status) || Item.STATUS_CLAIMING.equals(status);
    }
}
