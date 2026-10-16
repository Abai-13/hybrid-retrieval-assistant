package com.lostfound.service.impl;

import com.lostfound.common.ItemTexts;
import com.lostfound.entity.Item;
import com.lostfound.service.CandidateService;
import com.lostfound.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 候选召回实现 —— 基于字符 2-gram 覆盖率的轻量召回。
 * <p>
 * <b>为什么不用「最近 N 条」扩大范围</b>：{@code ORDER BY created_at DESC LIMIT 300}
 * 取的是「最近」的 300 条，不是「最像」的 300 条。一个三个月前丢的手机，
 * 就算库里真有人捡到，它也在 300 名开外 —— 扩大 N 只是把问题推远，没有解决。
 *
 * <b>为什么用 2-gram 而不是上向量</b>：先把「够不够得着」解决，再解决「像不像」。
 * 2-gram 能解决覆盖问题（候选池 30 → 全量 1000+），但解决不了语义问题 ——
 * 「苹果手机」和「iPhone 15 深空黑」一个 2-gram 都不重合。
 * 分开做的好处是能分别量化：这一步只该改善召回率，语义留给后面的向量召回。
 *
 * <b>性能</b>：每次请求对全量候选重新切 2-gram，1000 条量级约几毫秒。
 * 数据量上到十万级时需要预建索引（把每个物品的 2-gram 集合缓存起来），
 * 或者换倒排索引 —— 那时 2-gram 这套就该让位给真正的检索引擎了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateServiceImpl implements CandidateService {

    private final ItemService itemService;

    @Override
    public List<Item> recall(String query, int topK) {
        return recall(query, Item.TYPE_FOUND, topK, Set.of());
    }

    @Override
    public List<Item> recall(String query, String poolType, int topK) {
        return recall(query, poolType, topK, Set.of());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 池子由 {@code poolType} 决定：{@code FOUND} = 失物描述找招领（主动匹配、发布失物后自动匹配）；
     * {@code LOST} = 招领描述找失物（发布招领后反向匹配）。
     * <b>打分算法一个字都没改</b> —— 两边都是"一段自然语言描述 vs 一批候选物品的文本"，
     * 对称的，换个池子就够。
     */
    @Override
    public List<Item> recall(String query, String poolType, int topK, Set<Long> excludeIds) {
        return recallDetailed(query, poolType, topK, excludeIds).candidates();
    }

    @Override
    public RecallResult recallDetailed(String query, String poolType, int topK, Set<Long> excludeIds) {
        Set<String> queryGrams = bigrams(query);
        if (queryGrams.isEmpty()) {
            return new RecallResult(List.of(), 0, 0);
        }

        long start = System.currentTimeMillis();
        // 走 ItemService 而不是直接查 Mapper：全量候选列表带缓存，
        // 每次 AI 请求都扫一遍 1000 行数据库没必要
        List<Item> pool = Item.TYPE_LOST.equals(poolType)
                ? itemService.listUnclaimedLost()
                : itemService.listUnclaimedFound();

        Set<Long> excluded = excludeIds == null ? Set.of() : excludeIds;
        int excludedCount = 0;
        List<Scored> scored = new ArrayList<>(pool.size());
        for (Item item : pool) {
            // 排除项在打分**之前**就剔掉，不是取完 Top-K 再删。
            // 先截断后删除会让用户少拿到一条本可以补上来的结果 ——
            // 他说的是"再找找"，不是"少给我一条"。
            // 排除键是 itemId 而不是文本：用户否掉的是**这条物品**，
            // 不是"所有标题长这样的物品"。
            if (excluded.contains(item.getId())) {
                excludedCount++;
                continue;
            }
            double score = coverage(queryGrams, bigrams(docText(item)));
            // 分数为 0 说明和用户描述一个字都不重合，纯噪音。
            // 塞进 prompt 只会浪费 token 并分散模型注意力，直接丢掉。
            if (score > 0) {
                scored.add(new Scored(item, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Item> top = scored.stream().limit(topK).map(Scored::item).toList();
        log.info("召回[{}池]: 候选池 {} 条 → 按用户排除 {} 条 → 有重合 {} 条 → 取 Top{}，实际返回 {} 条，耗时 {} ms",
                poolType, pool.size(), excludedCount, scored.size(), topK, top.size(),
                System.currentTimeMillis() - start);
        return new RecallResult(top, pool.size(), excludedCount);
    }

    // ===================== 打分 =====================

    private record Scored(Item item, double score) {
    }

    /**
     * 把文本切成字符 2-gram 集合。
     * <p>
     * 为什么不用中文分词：分词要引依赖（HanLP / jieba），而且对错别字和新词很脆。
     * 2-gram 零依赖、对新词鲁棒 —— 代价是会产生「的苹」这种无意义的片段，
     * 但因为 query 和候选用的是同一套切法，无意义片段在两边同时出现时照样能匹配上。
     */
    private static Set<String> bigrams(String text) {
        String t = normalize(text);
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 2 <= t.length(); i++) {
            grams.add(t.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * 只保留汉字和小写字母数字。
     * <p>
     * 去掉空格和标点是必要的：否则「iPhone 15」会被切成 "e "、" 1" 这种
     * 跨空格的假片段，换个写法（iPhone15）就匹配不上了。
     */
    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    /**
     * query 的 2-gram 有多大比例出现在候选文本里。
     * <p>
     * 注意这是**单向**覆盖率（除以 query 的 gram 数），不是对称的 Jaccard。
     * 因为两边长度差很多（query 几十字，候选文本上百字），
     * 用交集除以并集会让长文本天然吃亏，短文本天然占便宜。
     * 除以 query 长度，等价于问「用户说的特征，这条候选覆盖了多少」。
     */
    private static double coverage(Set<String> queryGrams, Set<String> docGrams) {
        int hit = 0;
        for (String gram : queryGrams) {
            if (docGrams.contains(gram)) {
                hit++;
            }
        }
        return (double) hit / queryGrams.size();
    }

    /**
     * 参与匹配的文本 —— 和喂给大模型的字段保持一致，避免「召回用的信息模型看不到」。
     * <p>拼法已抽到 {@link com.lostfound.common.ItemTexts}：向量化也要用同一份，
     * 三处各自手写早晚会漂移（约定 8）。
     */
    private static String docText(Item item) {
        return ItemTexts.docText(item);
    }
}
