package com.lostfound.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lostfound.config.QdrantConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Qdrant REST 客户端 —— 只做「把 HTTP 收发讲对」，不含任何业务判断。
 *
 * <p><b>为什么用 REST 而不是官方 Java SDK</b>：本项目要的只有四个动作
 * （建集合 / 写点 / 检索 / 清理），REST 用已有的 {@code RestTemplate} 加几十行就够，
 * 而引 SDK 会连带引入它自己的 HTTP 栈、重试策略和版本约束 ——
 * 为了四个请求不值当（计划选型：优先 Qdrant REST API 与已有 HTTP 基础设施）。
 *
 * <p><b>失败契约和 {@link com.lostfound.service.EmbeddingService} 一致：失败返回 {@code null} / {@code false}，不抛异常。</b>
 * 向量层整体是「性能与召回的增强」，不是正确性依赖 —— 它坏了必须让上层安静地回退到 2-gram，
 * 而不是让一次找物对话变成 500。所有异常在这里就地消化并留下日志（可观测，不静默）。
 *
 * <p><b>点 ID 直接用物品 id</b>：这是「发布/补齐幂等」的全部秘密 ——
 * 同一个物品重复 upsert 是覆盖而不是新增，天然不会写出第二份（不需要先查后写）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "qdrant", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QdrantClient {

    private final QdrantConfig config;
    private final RestTemplate qdrantRestTemplate;

    /**
     * 集合是否已经确认存在。
     * <p>进程内只确认一次：每次检索前都先 GET 一遍等于给每个请求加一个固定往返 ——
     * 对「快才有意义」的本地服务纯属浪费。确认过之后集合被删属于异常情况，
     * 后续请求会拿到 404 → 返回 null → 上层回退，日志里看得见。
     */
    private final AtomicBoolean collectionEnsured = new AtomicBoolean(false);

    /** 一个待写入的点 */
    public record Point(long itemId, float[] vector, Map<String, Object> payload) {
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public String collectionName() {
        return config.getCollection();
    }

    // ===================== 集合 =====================

    /**
     * 哨兵点的 id —— 自增主键永远走不到的值，所以它<b>不占用任何真实物品 id</b>。
     * <p>这个点存的是「集合当前装的是哪个模型、多少维」。有了它，"
     * 改了 {@code embedding.model} 却忘了换集合名"这种错会<b>当场报错</b>，
     * 而不是把两个语义空间的向量静默混在同一个集合里互相比较 ——
     * 那种错不抛异常，只会让检索结果一点点变奇怪，是最难查的一类问题。
     */
    public static final long SENTINEL_ID = Long.MAX_VALUE;

    private static final String PAYLOAD_MODEL = "model";

    /**
     * 确认集合存在且与当前模型一致，不一致就拒绝使用。
     *
     * @param dimension 向量维度（取自 {@link com.lostfound.service.EmbeddingService#dimension()}）
     * @param modelId   模型标识（取自 {@link com.lostfound.service.EmbeddingService#providerName()}）
     * @return 是否可用；Qdrant 不可达 / 建集合失败 / <b>模型不匹配</b> 返回 {@code false}
     */
    public boolean ensureCollection(int dimension, String modelId) {
        if (!config.isEnabled()) {
            return false;
        }
        if (collectionEnsured.get()) {
            return true;
        }
        String url = collectionUrl();
        try {
            qdrantRestTemplate.exchange(url, HttpMethod.GET, null, String.class);
            return verifySentinel(dimension, modelId);
        } catch (HttpClientErrorException.NotFound missing) {
            return createCollection(url, dimension, modelId);
        } catch (RestClientException e) {
            log.error("Qdrant 连接失败（按向量不可用处理，上层会回退 2-gram 召回）: {}", url, e);
            return false;
        }
    }

    /** 老集合 / 手动建的集合里没有哨兵时补写一个（首次启用向量能力会走到这里） */
    private boolean verifySentinel(int dimension, String modelId) {
        Map<Long, Map<String, Object>> sentinels = retrievePayloads(List.of(SENTINEL_ID));
        if (sentinels == null) {
            // 读不到就没法证明集合和模型是对的 —— 宁可回退 2-gram，也不往可能不匹配的集合里写
            log.error("无法读取 Qdrant 哨兵点（集合元信息），按向量不可用处理");
            return false;
        }
        Map<String, Object> payload = sentinels.get(SENTINEL_ID);
        if (payload == null) {
            return writeSentinel(dimension, modelId);
        }
        Object existingModel = payload.get(PAYLOAD_MODEL);
        Object existingDim = payload.get("dimension");
        if (modelId.equals(existingModel) && Integer.valueOf(dimension).equals(toInt(existingDim))) {
            collectionEnsured.set(true);
            return true;
        }
        log.error("Qdrant 集合与当前模型不匹配：集合里是 model={} dimension={}，配置是 model={} dimension={} —— "
                        + "禁止混用（两个模型的向量放一起比较没有意义）。"
                        + "请换一个新的 qdrant.collection 名并重建索引（运行补齐命令，force=true）",
                existingModel, existingDim, modelId, dimension);
        return false;
    }

    private boolean createCollection(String url, int dimension, String modelId) {
        try {
            JSONObject vectors = new JSONObject();
            vectors.set("size", dimension);
            // 余弦：物品向量与查询向量必须同一模型同一距离口径，混用等于拿不同尺子量
            vectors.set("distance", "Cosine");
            JSONObject body = new JSONObject();
            body.set("vectors", vectors);
            exchange(HttpMethod.PUT, url, body);
            log.info("Qdrant 集合已创建: collection={}, dimension={}, distance=Cosine, model={}",
                    config.getCollection(), dimension, modelId);
            return writeSentinel(dimension, modelId);
        } catch (RestClientException e) {
            log.error("Qdrant 创建集合失败: {} (dimension={})", url, dimension, e);
            return false;
        }
    }

    private boolean writeSentinel(int dimension, String modelId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PAYLOAD_MODEL, modelId);
        payload.put("dimension", dimension);
        payload.put("distance", "Cosine");
        // 向量本身没有意义（哨兵只用来存元信息），但必须是**合法**向量：
        // 全零向量的模长为 0，算余弦会得到 NaN —— 不能往库里放一个算不出分的点
        float[] placeholder = new float[dimension];
        placeholder[0] = 1f;
        boolean ok = upsertPoint(SENTINEL_ID, placeholder, payload);
        if (ok) {
            collectionEnsured.set(true);
            log.info("Qdrant 哨兵点已写入: collection={}, model={}, dimension={}",
                    config.getCollection(), modelId, dimension);
        }
        return ok;
    }

    private static Integer toInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    // ===================== 写 =====================

    /** 单点 upsert —— 同一 itemId 重复调用是覆盖，幂等 */
    public boolean upsertPoint(long itemId, float[] vector, Map<String, Object> payload) {
        return upsertPoints(List.of(new Point(itemId, vector, payload)));
    }

    /**
     * 批量 upsert（{@code PUT /collections/{c}/points}）。
     * <p>一次写一批而不是一条条写：补齐几千条时请求数差两个数量级。
     */
    public boolean upsertPoints(List<Point> points) {
        if (!config.isEnabled() || points == null || points.isEmpty()) {
            return false;
        }
        JSONArray arr = new JSONArray();
        for (Point p : points) {
            JSONObject point = new JSONObject();
            point.set("id", p.itemId());
            point.set("vector", toVectorArray(p.vector()));
            if (p.payload() != null && !p.payload().isEmpty()) {
                point.set("payload", JSONUtil.parseObj(p.payload()));
            }
            arr.add(point);
        }
        JSONObject body = new JSONObject();
        body.set("points", arr);
        try {
            exchange(HttpMethod.PUT, collectionUrl() + "/points", body);
            return true;
        } catch (RestClientException e) {
            log.error("Qdrant 写入失败: collection={}, {} 个点（itemId={}）",
                    config.getCollection(), points.size(), idsOf(points), e);
            return false;
        }
    }

    // ===================== 读 =====================

    /**
     * 向量检索（{@code POST /collections/{c}/points/search}，余弦分数降序）。
     *
     * @return 点 id 列表（按相似度降序）；集合不存在 / Qdrant 不可达 / 返回结构异常 → {@code null}
     *         （<b>null 和「查到 0 条」是两件事</b>：前者是故障要回退，后者是正常结果）
     */
    public List<Long> searchIds(float[] vector, int limit) {
        if (!config.isEnabled() || vector == null || vector.length == 0 || limit <= 0) {
            return null;
        }
        JSONObject body = new JSONObject();
        body.set("vector", toVectorArray(vector));
        body.set("limit", limit);
        body.set("with_payload", false);
        body.set("with_vector", false);
        try {
            ResponseEntity<String> response =
                    exchange(HttpMethod.POST, collectionUrl() + "/points/search", body);
            String payload = response.getBody();
            if (payload == null) {
                log.error("Qdrant 检索返回空响应体");
                return null;
            }
            JSONArray result = JSONUtil.parseObj(payload).getJSONArray("result");
            if (result == null) {
                log.error("Qdrant 检索返回缺少 result 字段: {}", payload);
                return null;
            }
            List<Long> ids = new ArrayList<>(result.size());
            for (Object obj : result) {
                if (obj instanceof JSONObject hit) {
                    Long id = hit.getLong("id");
                    // 哨兵点的向量是占位符，分数没有意义 —— 它只存元信息，不参与任何检索
                    if (id != null && id != SENTINEL_ID) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Qdrant 集合不存在（索引还没建）: {} —— 本次回退 2-gram 召回", collectionUrl());
            return null;
        } catch (RestClientException e) {
            log.error("Qdrant 检索失败（本次回退 2-gram 召回）: {}", collectionUrl(), e);
            return null;
        } catch (Exception e) {
            log.error("解析 Qdrant 检索返回失败（本次回退 2-gram 召回）", e);
            return null;
        }
    }

    /**
     * 按 id 批量取 payload（补齐时用来判断「这条要不要重算」）。
     *
     * @return id → payload；失败返回 {@code null}（调用方按「判断不了」处理：宁可重算，不可漏写）
     */
    public Map<Long, Map<String, Object>> retrievePayloads(Collection<Long> ids) {
        if (!config.isEnabled() || ids == null || ids.isEmpty()) {
            return null;
        }
        JSONObject body = new JSONObject();
        body.set("ids", new JSONArray(new ArrayList<>(ids)));
        body.set("with_payload", true);
        body.set("with_vector", false);
        try {
            ResponseEntity<String> response = exchange(HttpMethod.POST, collectionUrl(), body);
            String payload = response.getBody();
            if (payload == null) {
                return null;
            }
            JSONArray result = JSONUtil.parseObj(payload).getJSONArray("result");
            if (result == null) {
                return null;
            }
            Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Object obj : result) {
                if (!(obj instanceof JSONObject point)) {
                    continue;
                }
                Long id = point.getLong("id");
                JSONObject p = point.getJSONObject("payload");
                if (id != null) {
                    byId.put(id, p == null ? Map.of() : p);
                }
            }
            return byId;
        } catch (Exception e) {
            log.warn("Qdrant 批量读取 payload 失败（补齐时无法跳过未变更的点，将按需重算）", e);
            return null;
        }
    }

    /**
     * 滚动列出集合里全部点 id（清理孤儿点用）。
     *
     * @return 全部 id；失败返回 {@code null}（调用方跳过清理 —— 读不到就不删，绝不猜着删）
     */
    public List<Long> scrollAllIds() {
        if (!config.isEnabled()) {
            return null;
        }
        List<Long> all = new ArrayList<>();
        Object offset = null;
        try {
            while (true) {
                JSONObject body = new JSONObject();
                body.set("limit", 1000);
                body.set("with_payload", false);
                body.set("with_vector", false);
                if (offset != null) {
                    body.set("offset", offset);
                }
                ResponseEntity<String> response =
                        exchange(HttpMethod.POST, collectionUrl() + "/points/scroll", body);
                String payload = response.getBody();
                if (payload == null) {
                    return null;
                }
                JSONObject result = JSONUtil.parseObj(payload).getJSONObject("result");
                if (result == null) {
                    return null;
                }
                JSONArray points = result.getJSONArray("points");
                if (points != null) {
                    for (Object obj : points) {
                        if (obj instanceof JSONObject point) {
                            Long id = point.getLong("id");
                            if (id != null) {
                                all.add(id);
                            }
                        }
                    }
                }
                offset = result.get("next_page_offset");
                if (offset == null) {
                    return all;
                }
            }
        } catch (HttpClientErrorException.NotFound e) {
            // 集合还没建 = 一个点都没有 = 孤儿自然也是 0 个
            return all;
        } catch (Exception e) {
            log.warn("Qdrant 滚动读取点列表失败（孤儿点清理跳过）", e);
            return null;
        }
    }

    /** 删除一批点（补齐/重建时清理已不存在的物品）。<b>哨兵点永不删除</b> —— 它不是物品 */
    public boolean deletePoints(Collection<Long> ids) {
        if (!config.isEnabled() || ids == null || ids.isEmpty()) {
            return false;
        }
        List<Long> targets = ids.stream().filter(id -> id != SENTINEL_ID).toList();
        if (targets.isEmpty()) {
            return false;
        }
        JSONObject body = new JSONObject();
        body.set("points", new JSONArray(new ArrayList<>(targets)));
        try {
            exchange(HttpMethod.POST, collectionUrl() + "/points/delete", body);
            log.info("Qdrant 已删除 {} 个孤儿点: ids={}", targets.size(), targets);
            return true;
        } catch (RestClientException e) {
            log.error("Qdrant 删除点失败: ids={}", targets, e);
            return false;
        }
    }

    // ===================== 私有 =====================

    private String collectionUrl() {
        return config.getBaseUrl() + "/collections/" + config.getCollection();
    }

    private ResponseEntity<String> exchange(HttpMethod method, String url, JSONObject body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? null : new HttpEntity<>(body.toString(), headers);
        return qdrantRestTemplate.exchange(url, method, entity, String.class);
    }

    /** float[] → JSON 数组。Hutool 不保证直接 set(float[]) 会序列化成数组，显式转最稳 */
    private static JSONArray toVectorArray(float[] vector) {
        JSONArray arr = new JSONArray();
        for (float v : vector) {
            arr.add(v);
        }
        return arr;
    }

    private static List<Long> idsOf(List<Point> points) {
        return points.stream().map(Point::itemId).toList();
    }
}
