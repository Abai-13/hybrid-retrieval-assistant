package com.lostfound.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 找物摘要的增 / 改 / 撤 —— 第一阶段最核心的一段纯逻辑，不依赖模型和数据库。
 *
 * <p>这几条断言的共同点：<b>用户说错了要能改回来</b>。
 * 「其实是在食堂」之后如果「图书馆」还留在条件里，检索就同时受两个地点约束，
 * 结果是一条都搜不到 —— 而用户看到的只是"没找到"，根本不知道是自己上一句话没被覆盖掉。
 */
class SearchSummaryTest {

    private static SearchFact fact(String field, String value, String certainty) {
        SearchFact f = new SearchFact();
        f.setField(field);
        f.setValue(value);
        f.setCertainty(certainty);
        return f;
    }

    // ===================== 补充 =====================

    @Test
    @DisplayName("补充：新条件追加，原有条件都留着")
    void merge_addsNewCondition() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("主体", "手机", null)), List.of(), "我丢了一部手机", false)
                .merge(List.of(fact("颜色", "黑色", null), fact("品牌", "苹果", null)),
                        List.of(), "黑色苹果手机", false);

        assertEquals(3, summary.viewConditions().size());
        assertEquals("手机", summary.currentValue("主体"));
        assertEquals("黑色", summary.currentValue("颜色"));
        assertEquals("苹果", summary.currentValue("品牌"));
    }

    @Test
    @DisplayName("来源记的是【用户原话】，不是模型的转述")
    void merge_keepsUserWordsAsSource() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("地点", "图书馆", null)), List.of(), "我昨天在三楼图书馆丢的", false);

        assertEquals("我昨天在三楼图书馆丢的", summary.viewConditions().get(0).getSource());
    }

    // ===================== 纠正 =====================

    @Test
    @DisplayName("★ 纠正：地点改了，旧地点必须消失（否则两个地点同时约束，一条都搜不到）")
    void merge_correctionOverwritesOldValue() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("主体", "手机", null), fact("地点", "图书馆", null)),
                        List.of(), "手机丢在图书馆", false)
                .merge(List.of(fact("地点", "食堂", null)), List.of(), "其实是食堂", false);

        assertEquals(2, summary.viewConditions().size(), "同 field 覆盖，不该多出一条");
        assertEquals("食堂", summary.currentValue("地点"));
        assertFalse(summary.buildQuery().contains("图书馆"), "旧地点不能残留在检索描述里");
        assertEquals("手机 食堂", summary.buildQuery());
    }

    @Test
    @DisplayName("纠正时来源跟着更新 —— 摘要是「现在的说法」，不是最早那句话")
    void merge_correctionUpdatesSource() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("颜色", "黑色", null)), List.of(), "黑色的", false)
                .merge(List.of(fact("颜色", "白色", null)), List.of(), "记错了，是白色", false);

        assertEquals("记错了，是白色", summary.currentValue("颜色") == null
                ? null : summary.viewConditions().get(0).getSource());
    }

    // ===================== 撤回 =====================

    @Test
    @DisplayName("★ 撤回：「颜色记不清了」→ 颜色条件整个删掉，不再参与检索")
    void merge_retractRemovesCondition() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("主体", "钥匙", null), fact("颜色", "银色", null)),
                        List.of(), "银色钥匙", false)
                .merge(List.of(), List.of("颜色"), "颜色记不清了", false);

        assertNull(summary.currentValue("颜色"));
        assertEquals("钥匙", summary.buildQuery(), "撤回后检索描述里不该再有颜色");
    }

    @Test
    @DisplayName("撤回不存在的条件是无害的（幂等），不会抛出也不会误删别的")
    void merge_retractUnknownFieldIsNoop() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("主体", "钥匙", null)), List.of(), "钥匙", false)
                .merge(List.of(), List.of("颜色"), "颜色记不清了", false);

        assertEquals("钥匙", summary.currentValue("主体"));
    }

    @Test
    @DisplayName("同一轮里既撤回又补充：先删后加，结果是新值（顺序反了会把刚加的删掉）")
    void merge_retractAndAddInSameTurn() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("颜色", "黑色", null)), List.of(), "黑色", false)
                .merge(List.of(fact("颜色", "白色", null)), List.of("颜色"), "不是黑色，是白色", false);

        assertEquals("白色", summary.currentValue("颜色"));
    }

    // ===================== 不确定性 =====================

    @Test
    @DisplayName("★ 「可能在图书馆」保存为不确定，但【照常参与检索】—— 不能因为不确定就当没听见")
    void merge_uncertainLocationStillSearches() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("主体", "书包", null), fact("地点", "图书馆", SearchSummary.UNCERTAIN)),
                        List.of(), "书包可能在图书馆", false);

        assertEquals(SearchSummary.UNCERTAIN, summary.currentValue("地点") == null
                ? null : summary.viewConditions().get(1).getCertainty());
        assertTrue(summary.buildQuery().contains("图书馆"),
                "不确定是给用户看的标记，不是过滤条件 —— 丢掉它等于少搜一个地方");
    }

    @Test
    @DisplayName("没写 certainty 时按「用户确定」处理（用户正常陈述时不带限定词）")
    void merge_defaultsToCertain() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("颜色", "黑色", null)), List.of(), "黑色", false);

        assertEquals(SearchSummary.CERTAIN, summary.viewConditions().get(0).getCertainty());
    }

    // ===================== 换物品 =====================

    @Test
    @DisplayName("★ 换物品时清空旧条件与旧排除项 —— 上一件的条件对新的一件毫无意义")
    void merge_subjectChangedResetsEverything() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("主体", "手机", null), fact("颜色", "黑色", null)),
                        List.of(), "黑色手机", false);
        summary.excludeItem(42L);

        SearchSummary next = summary.merge(List.of(fact("主体", "钥匙", null)), List.of(), "我丢的是钥匙", true);

        assertEquals(1, next.viewConditions().size());
        assertEquals("钥匙", next.currentValue("主体"));
        assertNull(next.currentValue("颜色"), "旧物品的条件必须清掉");
        assertTrue(next.getExcludedItemIds().isEmpty(), "旧物品的排除项也要清掉");
        assertEquals("钥匙", next.buildQuery());
    }

    @Test
    @DisplayName("不换物品时，排除项跟着摘要活下去（否则用户否掉的物品下次又冒出来）")
    void excludeItem_survivesMerge() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("主体", "手机", null)), List.of(), "手机", false);
        summary.excludeItem(7L);
        SearchSummary next = summary.merge(List.of(fact("颜色", "黑色", null)), List.of(), "黑色", false);

        assertEquals(List.of(7L), next.getExcludedItemIds());
    }

    @Test
    @DisplayName("同一个物品重复排除不会重复记录；排除项有上限")
    void excludeItem_deduplicatesAndBounds() {
        SearchSummary summary = SearchSummary.empty();
        summary.excludeItem(1L);
        summary.excludeItem(1L);
        assertEquals(1, summary.getExcludedItemIds().size());

        for (long i = 2; i <= SearchSummary.MAX_EXCLUSIONS + 5; i++) {
            summary.excludeItem(i);
        }
        assertTrue(summary.getExcludedItemIds().size() <= SearchSummary.MAX_EXCLUSIONS);
    }

    // ===================== 边界与坏数据 =====================

    @Test
    @DisplayName("超长的字段名/值被截断，空值整条丢弃 —— 模型提出的东西不能直接进摘要")
    void merge_boundsFieldAndValue() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("颜".repeat(50), "黑".repeat(500), null),
                        fact("  ", "x", null), fact("颜色", "  ", null)),
                        List.of(), "x".repeat(500), false);

        assertEquals(1, summary.viewConditions().size(), "只有字段名和值都合法的那条能进");
        SearchCondition condition = summary.viewConditions().get(0);
        assertEquals(SearchSummary.MAX_FIELD_LEN, condition.getField().length());
        assertEquals(SearchSummary.MAX_VALUE_LEN, condition.getValue().length());
        assertEquals(SearchSummary.MAX_SOURCE_LEN, condition.getSource().length());
    }

    @Test
    @DisplayName("条件条数有上限，防止模型一路「提建议」把摘要撑爆")
    void merge_boundsConditionCount() {
        SearchSummary summary = SearchSummary.empty();
        for (int i = 0; i < SearchSummary.MAX_CONDITIONS + 10; i++) {
            summary = summary.merge(List.of(fact("字段" + i, "值" + i, null)), List.of(), "x", false);
        }
        assertEquals(SearchSummary.MAX_CONDITIONS, summary.viewConditions().size());
    }

    @Test
    @DisplayName("空的 / 坏掉的 JSON 退化成空摘要并记日志，不抛异常（否则整个会话读不出来）")
    void fromJson_toleratesBadData() {
        assertTrue(SearchSummary.fromJson(null).isEmpty());
        assertTrue(SearchSummary.fromJson("   ").isEmpty());
        assertTrue(SearchSummary.fromJson("{不是 JSON").isEmpty());
        assertTrue(SearchSummary.fromJson("{\"conditions\":\"不是数组\"}").isEmpty());
    }

    @Test
    @DisplayName("JSON 往返：存进库里再读出来，条件、确定性、来源、排除项一个都不能少")
    void jsonRoundTrip() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("主体", "手机", null), fact("地点", "图书馆", SearchSummary.UNCERTAIN)),
                        List.of(), "手机可能在图书馆", false);
        summary.excludeItem(99L);

        SearchSummary restored = SearchSummary.fromJson(summary.toJson());

        assertEquals(summary.getConditions(), restored.getConditions());
        assertEquals(List.of(99L), restored.getExcludedItemIds());
    }

    @Test
    @DisplayName("检索描述：主体排最前、重复值去重、有长度上限")
    void buildQuery_isStableAndBounded() {
        SearchSummary summary = SearchSummary.empty()
                .merge(List.of(fact("颜色", "黑色", null), fact("主体", "手机", null)),
                        List.of(), "黑色手机", false);

        assertEquals("手机 黑色", summary.buildQuery(), "主体在最前，顺序稳定便于复现和排查");

        SearchSummary longOne = SearchSummary.empty()
                .merge(List.of(fact("主体", "长".repeat(40), null),
                        fact("颜色", "长".repeat(40), null),
                        fact("品牌", "长".repeat(40), null),
                        fact("地点", "长".repeat(40), null),
                        fact("特征", "长".repeat(40), null),
                        fact("型号", "长".repeat(40), null)), List.of(), "x", false);
        assertTrue(longOne.buildQuery().length() <= SearchSummary.MAX_QUERY_LEN);
    }
}
