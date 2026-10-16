package com.lostfound.service.impl;

import com.lostfound.common.ItemTexts;
import com.lostfound.entity.Item;
import com.lostfound.service.EmbeddingService;
import com.lostfound.service.ItemService;
import com.lostfound.service.ItemVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 物品向量实现 —— 写入（发布触发 / 分批补齐）与查询侧检索。
 *
 * <p><b>为什么向量库只存 id + 向量 + 模型/指纹</b>：业务事实的唯一来源是 MySQL。
 * 如果把标题、描述复制进 payload，物品被认领下架后两边就会各说各话 ——
 * 所以检索回来的 id 一律回 MySQL 复核（见 {@link HybridRecallServiceImpl}），
 * payload 只留「要不要重算」所需的最小信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemVectorServiceImpl implements ItemVectorService {

    /** 开关：{@code ai.vector.enabled=false} 时整条向量链路静默停用（助手退回纯 2-gram） */
    @Value("${ai.vector.enabled:true}")
    private boolean vectorEnabled;

    private final EmbeddingService embeddingService;
    private final QdrantClient qdrantClient;
    private final ItemService itemService;
    /**
     * 手动注入的线程池（bean 名 {@code itemIndexExecutor}）——
     * 字段名必须和 bean 名一致才解析得到（同 {@code llmRestTemplate} 的规则）。
     * <p>用 {@code Executor} 而不是 {@code @Async}：{@code @Async} 自调用不生效，
     * 而触发与执行就在同一个类里；直接提交到线程池更直白，也更好测。
     */
    private final Executor itemIndexExecutor;

    /** payload 字段名 —— 与 {@link QdrantClient} 的哨兵点字段保持同一套命名 */
    private static final String PAYLOAD_MODEL = "model";
    private static final String PAYLOAD_FINGERPRINT = "fp";

    @Override
    public void triggerAfterPublish(Item item) {
        if (!vectorEnabled) {
            log.debug("物品向量化已关闭（ai.vector.enabled=false），跳过: id={}",
                    item == null ? null : item.getId());
            return;
        }
        if (item == null || item.getId() == null) {
            return;
        }
        if (!Item.TYPE_FOUND.equals(item.getType())) {
            // 只有招领进向量库：聊天找物是「丢失描述 → 找招领池」，这一侧才是它要检索的池子
            return;
        }
        try {
            itemIndexExecutor.execute(() -> {
                try {
                    index(item);
                } catch (Exception e) {
                    // 兜底：向量是异步增强，任何异常都不能外溢到发布链路
                    log.error("物品向量化失败（不影响发布，可运行补齐命令修复）: id={}", item.getId(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 自定义拒绝策略只记日志不抛，这里是第二道保险（万一执行器换实现）
            log.error("向量化任务被拒绝入队: id={} —— 发布不受影响，可运行补齐命令修复", item.getId());
        }
    }

    @Override
    public boolean index(Item item) {
        if (!vectorEnabled || item == null || item.getId() == null) {
            return false;
        }
        if (!Item.TYPE_FOUND.equals(item.getType())) {
            return false;
        }
        String text = ItemTexts.docText(item);
        if (text.isBlank()) {
            // 空文本的向量是垃圾向量：会进库、能被检出、但对任何描述都"像"
            log.warn("物品匹配文本为空，跳过向量化: id={}", item.getId());
            return false;
        }
        float[] vector = embeddingService.embed(text);
        if (vector == null) {
            log.error("物品向量化失败（补齐命令可重试）: id={}", item.getId());
            return false;
        }
        if (vector.length != embeddingService.dimension()) {
            log.error("向量维度不符，拒绝写入: id={}, 实际 {} / 配置 {}", item.getId(),
                    vector.length, embeddingService.dimension());
            return false;
        }
        if (!qdrantClient.ensureCollection(vector.length, embeddingService.providerName())) {
            log.error("Qdrant 集合不可用，跳过写入: id={}", item.getId());
            return false;
        }
        boolean ok = qdrantClient.upsertPoint(item.getId(), vector, payload(text));
        if (ok) {
            log.info("物品向量已写入: id={}, model={}", item.getId(), embeddingService.providerName());
        }
        return ok;
    }

    @Override
    public List<Long> searchIds(String queryText, int limit) {
        if (!vectorEnabled || !qdrantClient.isEnabled()) {
            return null;
        }
        if (queryText == null || queryText.isBlank() || limit <= 0) {
            return null;
        }
        long start = System.currentTimeMillis();
        float[] vector = embeddingService.embed(queryText);
        if (vector == null) {
            log.warn("查询向量化失败，本次回退 2-gram 召回（{} ms）",
                    System.currentTimeMillis() - start);
            return null;
        }
        if (vector.length != embeddingService.dimension()) {
            log.error("查询向量维度不符: 实际 {} / 配置 {}，回退 2-gram",
                    vector.length, embeddingService.dimension());
            return null;
        }
        if (!qdrantClient.ensureCollection(vector.length, embeddingService.providerName())) {
            return null;
        }
        List<Long> ids = qdrantClient.searchIds(vector, limit);
        // 只记条数与耗时，**不记 query 原文** —— 那是用户描述的私密内容（含地点与特征）
        log.info("向量召回: limit={} → {} 条, 耗时 {} ms",
                limit, ids == null ? "失败" : String.valueOf(ids.size()),
                System.currentTimeMillis() - start);
        return ids;
    }

    @Override
    public BackfillReport backfill(int batchSize, boolean force) {
        long start = System.currentTimeMillis();
        if (!vectorEnabled) {
            log.error("物品向量化已关闭（ai.vector.enabled=false），补齐不执行");
            return new BackfillReport(0, 0, 0, 0, 0, false, System.currentTimeMillis() - start);
        }
        int size = Math.max(1, Math.min(batchSize, 1000));
        int dimension = embeddingService.dimension();
        String model = embeddingService.providerName();
        if (!qdrantClient.ensureCollection(dimension, model)) {
            log.error("Qdrant 集合不可用，补齐不执行（先确认服务与 qdrant.* 配置）");
            return new BackfillReport(0, 0, 0, 0, 0, false, System.currentTimeMillis() - start);
        }

        int scanned = 0;
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        boolean completed = false;
        long lastId = 0L;
        Set<Long> seen = new HashSet<>();
        int batchNo = 0;

        while (true) {
            List<Item> batch = itemService.listFoundAfter(lastId, size);
            if (batch == null || batch.isEmpty()) {
                completed = true;
                break;
            }
            batchNo++;
            for (Item item : batch) {
                seen.add(item.getId());
                lastId = Math.max(lastId, item.getId());
            }
            scanned += batch.size();

            // 1) 算哪些要写：读 Qdrant 现有 payload，模型与文本指纹都没变的直接跳过
            Map<Long, Map<String, Object>> existing = force
                    ? Map.of()
                    : qdrantClient.retrievePayloads(idsOf(batch));
            List<Item> need = new ArrayList<>();
            Map<Long, String> texts = new LinkedHashMap<>();
            for (Item item : batch) {
                String text = ItemTexts.docText(item);
                if (text.isBlank()) {
                    failed++;
                    log.warn("物品匹配文本为空，跳过: id={}", item.getId());
                    continue;
                }
                if (!force) {
                    Map<String, Object> payload = existing == null ? null : existing.get(item.getId());
                    if (payload != null
                            && model.equals(payload.get(PAYLOAD_MODEL))
                            && fingerprint(text).equals(payload.get(PAYLOAD_FINGERPRINT))) {
                        skipped++;
                        continue;
                    }
                }
                need.add(item);
                texts.put(item.getId(), text);
            }

            // 2) 批量向量化 + 批量 upsert
            if (!need.isEmpty()) {
                List<float[]> vectors = embeddingService.embedBatch(
                        need.stream().map(i -> texts.get(i.getId())).toList());
                if (vectors == null || vectors.size() != need.size()) {
                    // 向量化是这里最贵也最容易断的一环：失败就中止，
                    // 已经写好的点保留（下次重跑靠指纹跳过，不重复计费）
                    failed += need.size();
                    log.error("第 {} 批向量化失败，补齐中止（已完成部分保留，可直接重跑）", batchNo);
                    break;
                }
                List<QdrantClient.Point> points = new ArrayList<>(need.size());
                for (int i = 0; i < need.size(); i++) {
                    float[] vector = vectors.get(i);
                    if (vector == null || vector.length != dimension) {
                        failed++;
                        log.error("第 {} 批向量维度异常，跳过: id={}, 实际 {} / 配置 {}",
                                batchNo, need.get(i).getId(),
                                vector == null ? 0 : vector.length, dimension);
                        continue;
                    }
                    points.add(new QdrantClient.Point(need.get(i).getId(), vector,
                            payload(texts.get(need.get(i).getId()))));
                }
                if (!points.isEmpty()) {
                    if (qdrantClient.upsertPoints(points)) {
                        indexed += points.size();
                    } else {
                        // upsert 失败只记账不中止：坏的多半是个别批次，下一批还有机会
                        failed += points.size();
                    }
                }
            }

            if (batch.size() < size) {
                completed = true;
                break;
            }
        }

        // 3) 孤儿清理 —— 只有**完整扫完**才做：中途「没见过」不等于「不存在」
        int orphans = completed ? deleteOrphans(seen) : 0;

        BackfillReport report = new BackfillReport(scanned, indexed, skipped, failed, orphans,
                completed, System.currentTimeMillis() - start);
        log.info("物品向量补齐完成: {}", report.summary());
        return report;
    }

    /**
     * 删掉「Qdrant 里有、MySQL 里已不存在（或已不是 FOUND）」的点。
     * <p>再查一次 MySQL 而不是直接拿 {@code seen} 的差集：两者之间隔着扫描时间，
     * 扫到一半新发布的物品不在 {@code seen} 里，直接按差集删会误伤正常物品。
     */
    private int deleteOrphans(Set<Long> seen) {
        List<Long> all = qdrantClient.scrollAllIds();
        if (all == null) {
            log.warn("读取集合点列表失败，孤儿清理跳过（下次补齐重试）");
            return 0;
        }
        List<Long> candidates = all.stream()
                .filter(id -> id != QdrantClient.SENTINEL_ID && !seen.contains(id))
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<Long, Item> stillThere = itemService.mapByIds(candidates);
        List<Long> orphans = candidates.stream()
                .filter(id -> {
                    Item item = stillThere.get(id);
                    return item == null || !Item.TYPE_FOUND.equals(item.getType());
                })
                .toList();
        if (orphans.isEmpty()) {
            return 0;
        }
        return qdrantClient.deletePoints(orphans) ? orphans.size() : 0;
    }

    private Map<String, Object> payload(String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_MODEL, embeddingService.providerName());
        payload.put(PAYLOAD_FINGERPRINT, fingerprint(text));
        return payload;
    }

    /**
     * 文本指纹（SHA-256 取前 16 字节）—— 判断「这条物品的文本变了没有」。
     * <p>存指纹而不是原文：payload 里不需要业务文本，改一改也不泄露内容；
     * 存 hash 而不是长度/前缀：前缀相同的两条描述会被误判成没变。
     */
    static String fingerprint(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256，走到这里说明运行环境有问题 —— 不静默降级成"永远要重算"
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static List<Long> idsOf(List<Item> items) {
        return items.stream().map(Item::getId).toList();
    }
}
