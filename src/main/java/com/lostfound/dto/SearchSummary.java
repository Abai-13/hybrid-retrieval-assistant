package com.lostfound.dto;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次找物会话的「当前条件」—— 会话记忆的主体。
 *
 * <p><b>存结构化字段而不是一段自然语言总结</b>，是因为摘要要支持三种确定性操作：
 * <ul>
 *   <li><b>补充</b>：新 field 追加；</li>
 *   <li><b>纠正</b>：「其实是在食堂」→ 同 field 覆盖旧值，旧地点必须消失
 *       —— 自然语言总结做不到"保证旧值消失"，只能再问一次模型，而模型每次答的可能不一样；</li>
 *   <li><b>撤回</b>：「颜色记不清了」→ 按 field 删除。</li>
 * </ul>
 * 这三种操作全部在 {@link #merge} 里用普通代码完成，模型只负责提出"这一轮用户说了哪些事实"。
 *
 * <p><b>为什么还要记排除项</b>：用户说「不是第二个，再找找」时，
 * 光把这条物品从这一轮的候选里剔掉是不够的 —— 下一次搜索它还会被召回，
 * 用户纠正过的错误就白纠正了。排除项必须跟着摘要一起活下去。
 */
@Data
public class SearchSummary {

    /** 主体条件（"我丢的是手机"里的「手机」）的固定字段名 —— 换主体的判断靠它 */
    public static final String FIELD_SUBJECT = "主体";

    /** 用户确定提供的事实 */
    public static final String CERTAIN = "CERTAIN";
    /** 用户自己也不确定的事实（「可能在图书馆」）—— 照常检索，但界面要说清 */
    public static final String UNCERTAIN = "UNCERTAIN";

    /** 条件条数上限。防止模型一直"提建议"把摘要撑爆 */
    public static final int MAX_CONDITIONS = 16;
    /** 条件名长度上限 */
    public static final int MAX_FIELD_LEN = 12;
    /** 条件值长度上限 */
    public static final int MAX_VALUE_LEN = 40;
    /** 来源原话长度上限 */
    public static final int MAX_SOURCE_LEN = 60;
    /** 排除项条数上限 */
    public static final int MAX_EXCLUSIONS = 50;
    /** 检索描述的总长度上限（拼起来的 query） */
    public static final int MAX_QUERY_LEN = 200;

    /** 已确认的条件，按加入顺序 */
    private List<SearchCondition> conditions = new ArrayList<>();

    /** 用户明确否掉的物品 id —— 后续召回必须一直躲开它们 */
    private List<Long> excludedItemIds = new ArrayList<>();

    public static SearchSummary empty() {
        return new SearchSummary();
    }

    /**
     * 从库里的 JSON 还原。
     * <p>解析失败一律退化成空摘要并记日志 —— 坏数据清掉当前条件固然可惜，
     * 但让整个会话读不出来更糟。这条路径在真实环境不该发生（写进去的一定是
     * {@link #toJson()} 的产物），所以它出现即异常，要能被看见。
     */
    public static SearchSummary fromJson(String json) {
        SearchSummary summary = new SearchSummary();
        if (json == null || json.isBlank()) {
            return summary;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            JSONArray conds = obj.getJSONArray("conditions");
            if (conds != null) {
                for (Object o : conds) {
                    if (o instanceof JSONObject c) {
                        SearchCondition cond = new SearchCondition(
                                c.getStr("field"), c.getStr("value"),
                                c.getStr("certainty"), c.getStr("source"));
                        if (isUsable(cond)) {
                            summary.conditions.add(cond);
                        }
                    }
                }
            }
            JSONArray excluded = obj.getJSONArray("excludedItemIds");
            if (excluded != null) {
                for (Object o : excluded) {
                    if (o instanceof Number n) {
                        summary.excludedItemIds.add(n.longValue());
                    }
                }
            }
        } catch (Exception e) {
            // 不该发生；发生了必须留下痕迹，不能静默把用户的记忆抹掉
            org.slf4j.LoggerFactory.getLogger(SearchSummary.class)
                    .error("会话摘要 JSON 解析失败，本次按空摘要继续: {}", json, e);
            return empty();
        }
        return summary;
    }

    public String toJson() {
        JSONObject obj = new JSONObject();
        JSONArray conds = new JSONArray();
        for (SearchCondition c : conditions) {
            conds.add(JSONUtil.parseObj(JSONUtil.toJsonStr(c)));
        }
        obj.set("conditions", conds);
        obj.set("excludedItemIds", excludedItemIds);
        return obj.toString();
    }

    /**
     * 把模型这一轮提出的事实合并进摘要 —— <b>补充 / 纠正 / 撤回三件事的唯一入口</b>。
     *
     * @param facts         模型提取的事实（已由 {@code TurnInterpreter} 校验过字段名和长度）
     * @param removedFields 用户明确撤回的条件名（「颜色记不清了」→ ["颜色"]）
     * @param source        这一轮的用户原话，作为新事实的来源
     * @param reset         true ＝ 用户换了另一件物品，先清空旧条件与旧排除项
     */
    public SearchSummary merge(List<SearchFact> facts, List<String> removedFields,
                               String source, boolean reset) {
        SearchSummary next = new SearchSummary();
        if (!reset) {
            for (SearchCondition c : conditions) {
                next.conditions.add(new SearchCondition(c.getField(), c.getValue(), c.getCertainty(), c.getSource()));
            }
            next.excludedItemIds.addAll(excludedItemIds);
        }

        // ① 撤回先于补充：「其实不是黑色的」和「其实颜色是白的」可能同时出现，
        //    先删后加才能得到正确结果；反过来做会把刚加上的值又删掉。
        if (removedFields != null) {
            for (String field : removedFields) {
                next.removeField(field);
            }
        }

        // ② 补充 / 纠正：同 field 覆盖，新 field 追加
        if (facts != null) {
            for (SearchFact fact : facts) {
                next.put(fact, source);
            }
        }
        return next;
    }

    /** 单条写入：同 field 覆盖已有的（这就是"纠正"），否则追加 */
    void put(SearchFact fact, String source) {
        if (fact == null) {
            return;
        }
        String field = trim(fact.getField(), MAX_FIELD_LEN);
        String value = trim(fact.getValue(), MAX_VALUE_LEN);
        if (field == null || value == null) {
            return;
        }
        String certainty = UNCERTAIN.equalsIgnoreCase(fact.getCertainty()) ? UNCERTAIN : CERTAIN;

        for (SearchCondition existing : conditions) {
            if (existing.getField().equals(field)) {
                // 值没变、确定性也没变 → **连来源一起保留**。
                //
                // ⚠️ 这条不是优化，是正确性：模型经常把"背景里出现过的条件"当成
                //    "本轮用户说的"再返回一次（真实模型实测发生过 —— 用户问了一句平台操作，
                //    模型顺手把前面的颜色、特征又提了一遍）。如果照单更新来源，
                //    那些条件的来源就会变成那句平台问答，之后再也没法回答
                //    "这个条件是从哪句话来的"。来源一旦被污染，整个摘要的可信度就没了。
                if (value.equals(existing.getValue()) && certainty.equals(existing.getCertainty())) {
                    return;
                }
                // 值真的变了 —— 这就是「纠正」：覆盖旧值，同时更新来源
                existing.setValue(value);
                existing.setCertainty(certainty);
                existing.setSource(trim(source, MAX_SOURCE_LEN));
                return;
            }
        }
        if (conditions.size() >= MAX_CONDITIONS) {
            return;
        }
        conditions.add(new SearchCondition(field, value, certainty, trim(source, MAX_SOURCE_LEN)));
    }

    /** 撤回：按条件名删除（「颜色记不清了」）。删不存在的条件是无害的，幂等 */
    public void removeField(String field) {
        String f = trim(field, MAX_FIELD_LEN);
        if (f == null) {
            return;
        }
        conditions.removeIf(c -> c.getField().equals(f));
    }

    /** 记一个被用户明确否掉的物品 */
    public void excludeItem(Long itemId) {
        if (itemId == null || excludedItemIds.contains(itemId)) {
            return;
        }
        if (excludedItemIds.size() >= MAX_EXCLUSIONS) {
            excludedItemIds.remove(0);
        }
        excludedItemIds.add(itemId);
    }

    public String currentValue(String field) {
        for (SearchCondition c : conditions) {
            if (c.getField().equals(field)) {
                return c.getValue();
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    /**
     * 拼出这一轮真正用于检索的描述 —— <b>「完整有效描述」</b>。
     *
     * <p>这是第一阶段的核心做法：每次搜索都用<b>合并后的全部条件</b>，
     * 而不是只用用户刚说的那句。「其实是在食堂」如果只拿这一句去召回，
     * 主体和颜色全丢了，必然搜出一堆无关物品。
     *
     * <p>主体排在最前（权重上 2-gram 覆盖率不分位置，但顺序稳定便于复现和排查），
     * 相同值去重，整体截断。
     */
    public String buildQuery() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        String subject = currentValue(FIELD_SUBJECT);
        if (subject != null && !subject.isBlank()) {
            seen.put(subject, Boolean.TRUE);
        }
        for (SearchCondition c : conditions) {
            if (c.getValue() != null && !c.getValue().isBlank()) {
                seen.putIfAbsent(c.getValue(), Boolean.TRUE);
            }
        }
        String query = String.join(" ", seen.keySet());
        return query.length() <= MAX_QUERY_LEN ? query : query.substring(0, MAX_QUERY_LEN);
    }

    /** 给界面看的条件视图（顺序＝加入顺序，主体在不在最前由调用方决定） */
    public List<SearchCondition> viewConditions() {
        return List.copyOf(conditions);
    }

    private static boolean isUsable(SearchCondition c) {
        return c != null && c.getField() != null && !c.getField().isBlank()
                && c.getValue() != null && !c.getValue().isBlank();
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
