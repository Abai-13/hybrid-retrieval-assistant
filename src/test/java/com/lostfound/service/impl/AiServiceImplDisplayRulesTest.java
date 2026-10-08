package com.lostfound.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.lostfound.dto.MatchResult;
import com.lostfound.entity.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 展示规则（最低分过滤 + 条数截断）与 score 解析的单元测试 —— 纯函数，不碰大模型。
 *
 * <p>之所以专门为它开一个测试类：AI 链路原本<b>一个单元测试都没有</b>，
 * 全靠 146 条评测兜着。而评测有三个致命的不方便 —— 要花钱、跑一次好几分钟、
 * 还没法断言边界（谁能靠调模型测出 79 和 80 的区别？）。把展示规则抽成纯函数就是为了这个。
 */
class AiServiceImplDisplayRulesTest {

    private static MatchResult m(long id, int score) {
        MatchResult r = new MatchResult();
        r.setItemId(id);
        r.setScore(score);
        r.setTitle("物品" + id);
        r.setReason("理由" + id);
        return r;
    }

    private static List<Long> idsOf(List<MatchResult> list) {
        return list.stream().map(MatchResult::getItemId).toList();
    }

    // ===================== 阈值边界 =====================

    @Test
    @DisplayName("阈值边界：79 被过滤，80 保留，81 保留（阈值是「大于等于」）")
    void thresholdBoundary() {
        List<MatchResult> all = List.of(m(1, 81), m(2, 80), m(3, 79));

        AiServiceImpl.DisplaySelection sel = AiServiceImpl.applyDisplayRules(all, 80, 5);

        assertEquals(List.of(1L, 2L), idsOf(sel.shown()), "恰好 80 分必须保留");
        assertEquals(1, sel.belowThreshold(), "只有 79 那条被挡下");
    }

    @Test
    @DisplayName("边界对：79 与 81 之间只差阈值两分，结果必须不同")
    void thresholdJustBelowAndAbove() {
        assertEquals(0, AiServiceImpl.applyDisplayRules(List.of(m(1, 79)), 80, 5).shown().size());
        assertEquals(1, AiServiceImpl.applyDisplayRules(List.of(m(1, 81)), 80, 5).shown().size());
    }

    @Test
    @DisplayName("最低分设成 0 = 关闭过滤，全部保留（评测拿分数分布时必须用这个）")
    void zeroThresholdDisablesFiltering() {
        List<MatchResult> all = List.of(m(1, 100), m(2, 0), m(3, 50));

        AiServiceImpl.DisplaySelection sel = AiServiceImpl.applyDisplayRules(all, 0, 5);

        assertEquals(3, sel.shown().size());
        assertEquals(0, sel.belowThreshold());
    }

    // ===================== 条数截断 =====================

    @Test
    @DisplayName("条数截断：超出的截掉，保留的是分数最高的那几条且顺序不变")
    void truncatesKeepingHighestScoresInOrder() {
        // 入参已按分数降序（toMatchResults 排过）
        List<MatchResult> all = List.of(m(1, 95), m(2, 90), m(3, 85), m(4, 80));

        AiServiceImpl.DisplaySelection sel = AiServiceImpl.applyDisplayRules(all, 80, 2);

        assertEquals(List.of(1L, 2L), idsOf(sel.shown()), "应保留分数最高的两条，顺序仍是降序");
        // 截断掉的两条不是"低于阈值"，不能被算进 belowThreshold —— 否则前端会提示错误的条数
        assertEquals(0, sel.belowThreshold());
    }

    @Test
    @DisplayName("过滤与截断同时发生：先按分数筛，再截前 N 条")
    void filterThenTruncate() {
        List<MatchResult> all = List.of(m(1, 95), m(2, 60), m(3, 90), m(4, 85));

        AiServiceImpl.DisplaySelection sel = AiServiceImpl.applyDisplayRules(all, 80, 2);

        assertEquals(List.of(1L, 3L), idsOf(sel.shown()));
        assertEquals(1, sel.belowThreshold());
    }

    @Test
    @DisplayName("截断：只取前 N 条，且【不重排】—— 排序是 toMatchResults 的职责，这里只按给定顺序截")
    void truncationTakesFirstNAndDoesNotReorder() {
        // 入参已按分数降序（toMatchResults 排过），这里照契约传
        List<MatchResult> all = List.of(m(1, 99), m(3, 95), m(4, 81), m(2, 80));

        AiServiceImpl.DisplaySelection sel = AiServiceImpl.applyDisplayRules(all, 0, 3);

        assertEquals(List.of(1L, 3L, 4L), idsOf(sel.shown()), "取前 3 条，顺序原样保留");
    }

    // ===================== 配置校验（非法配置必须启动失败，不静默钳位） =====================

    private static AiServiceImpl serviceWith(int interactiveMax, int autoMin, int autoMax) {
        AiServiceImpl service = new AiServiceImpl(null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "interactiveMaxResults", interactiveMax);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "autoMatchMinScore", autoMin);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "autoMatchMaxResults", autoMax);
        return service;
    }

    @Test
    @DisplayName("配置校验：边界值都合法（条数 1/5，门槛 0/100）")
    void validBoundariesPass() {
        assertDoesNotThrow(() -> serviceWith(1, 0, 1).validateMatchRules());
        assertDoesNotThrow(() -> serviceWith(5, 100, 5).validateMatchRules());
        assertDoesNotThrow(() -> serviceWith(5, 80, 5).validateMatchRules());
    }

    @Test
    @DisplayName("配置校验：主动匹配的条数越界 → 抛异常，消息点名字段与当前值")
    void invalidInteractiveMaxResultsFailsFast() {
        for (int bad : new int[]{0, -1, 6, 1000}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> serviceWith(bad, 80, 5).validateMatchRules());
            assertTrue(ex.getMessage().contains("ai.match.interactive.max-results"), ex.getMessage());
            assertTrue(ex.getMessage().contains(String.valueOf(bad)), ex.getMessage());
        }
    }

    @Test
    @DisplayName("配置校验：自动匹配的门槛越界 → 抛异常（哪怕现在还没有代码读它）")
    void invalidAutoMinScoreFailsFast() {
        for (int bad : new int[]{-1, 101, 800}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> serviceWith(5, bad, 5).validateMatchRules());
            assertTrue(ex.getMessage().contains("ai.match.auto.min-score"), ex.getMessage());
            assertTrue(ex.getMessage().contains(String.valueOf(bad)), ex.getMessage());
        }
    }

    @Test
    @DisplayName("配置校验：自动匹配的条数越界 → 抛异常")
    void invalidAutoMaxResultsFailsFast() {
        for (int bad : new int[]{0, -1, 6, 1000}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> serviceWith(5, 80, bad).validateMatchRules());
            assertTrue(ex.getMessage().contains("ai.match.auto.max-results"), ex.getMessage());
            assertTrue(ex.getMessage().contains(String.valueOf(bad)), ex.getMessage());
        }
    }

    @Test
    @DisplayName("配置校验：三处都错时一次报全，不用改一个重启一次")
    void reportsAllProblemsAtOnce() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> serviceWith(0, 150, 0).validateMatchRules());

        assertTrue(ex.getMessage().contains("ai.match.interactive.max-results"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ai.match.auto.min-score"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ai.match.auto.max-results"), ex.getMessage());
    }

    @Test
    @DisplayName("配置校验：越界值不会被悄悄夹到合法范围（800 不会变成 100 就放行）")
    void outOfRangeValueIsNotSilentlyClamped() {
        // 给"不要静默钳位"立的桩：以前 -5 会被夹成 0 继续跑，现在必须直接起不来
        assertThrows(IllegalStateException.class, () -> serviceWith(5, -5, 5).validateMatchRules());
    }

    // ===================== 空结果 =====================

    @Test
    @DisplayName("空输入：展示空、被过滤数 0（不是 null，避免调用方 NPE）")
    void emptyInput() {
        AiServiceImpl.DisplaySelection sel = AiServiceImpl.applyDisplayRules(List.of(), 80, 5);

        assertTrue(sel.shown().isEmpty());
        assertEquals(0, sel.belowThreshold());
    }

    @Test
    @DisplayName("全部低于阈值：展示为空，但被过滤数如实报出 —— 前端据此说「暂未发现高度匹配」而不是「没找到」")
    void allBelowThreshold() {
        List<MatchResult> all = List.of(m(1, 70), m(2, 50));

        AiServiceImpl.DisplaySelection sel = AiServiceImpl.applyDisplayRules(all, 80, 5);

        assertTrue(sel.shown().isEmpty());
        assertEquals(2, sel.belowThreshold());
    }

    // ===================== score 解析（缺失 / 非法不得静默） =====================

    private static List<Item> candidates(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> {
            Item item = new Item();
            item.setId(id);
            item.setTitle("物品" + id);
            item.setType(Item.TYPE_FOUND);
            item.setStatus(Item.STATUS_UNCLAIMED);
            return item;
        }).toList();
    }

    private static JSONArray matches(String json) {
        return JSONUtil.parseArray(json);
    }

    @Test
    @DisplayName("缺失 score：按 0 分处理，但结果不丢（要能被后面的过滤如实统计出来）")
    void missingScoreFallsBackToZero() {
        JSONArray arr = matches("""
                [{"itemId":1,"reason":"没给分"}]
                """);

        List<MatchResult> list = AiServiceImpl.toMatchResults(arr, candidates(1));

        assertEquals(1, list.size(), "缺失 score 不该把这条结果整个丢掉");
        assertEquals(0, list.get(0).getScore());
        // 缺失的 0 分在阈值下会被挡下，且会计入 belowThresholdCount —— 不会无声消失
        assertEquals(1, AiServiceImpl.applyDisplayRules(list, 80, 5).belowThreshold());
    }

    @Test
    @DisplayName("非法 score：非数字字符串 → 0 分")
    void nonNumericScoreFallsBackToZero() {
        JSONArray arr = matches("""
                [{"itemId":1,"score":"很高"}]
                """);

        assertEquals(0, AiServiceImpl.toMatchResults(arr, candidates(1)).get(0).getScore());
    }

    @Test
    @DisplayName("非法 score：超出 0~100 → 0 分（不拿脏数据当高分）")
    void outOfRangeScoreFallsBackToZero() {
        assertEquals(0, AiServiceImpl.toMatchResults(
                matches("""
                        [{"itemId":1,"score":150}]
                        """), candidates(1)).get(0).getScore());
        assertEquals(0, AiServiceImpl.toMatchResults(
                matches("""
                        [{"itemId":1,"score":-5}]
                        """), candidates(1)).get(0).getScore());
    }

    @Test
    @DisplayName("合法 score：数字或数字字符串都能解析")
    void numericScoreIsParsed() {
        assertEquals(90, AiServiceImpl.toMatchResults(
                matches("""
                        [{"itemId":1,"score":90}]
                        """), candidates(1)).get(0).getScore());
        assertEquals(90, AiServiceImpl.toMatchResults(
                matches("""
                        [{"itemId":1,"score":"90"}]
                        """), candidates(1)).get(0).getScore());
    }

    @Test
    @DisplayName("幻觉防护没被改坏：itemId 不在候选列表里的仍然丢弃")
    void hallucinatedItemIdStillDropped() {
        JSONArray arr = matches("""
                [{"itemId":999,"score":99,"reason":"编的"}]
                """);

        assertTrue(AiServiceImpl.toMatchResults(arr, candidates(1)).isEmpty());
    }

    @Test
    @DisplayName("解析后仍按分数降序，且本地回填的字段来自候选人（模型说了不算）")
    void keepsLocalFieldsAndSortsDesc() {
        JSONArray arr = matches("""
                [{"itemId":1,"score":60,"reason":"低"},{"itemId":2,"score":95,"reason":"高"}]
                """);

        List<MatchResult> list = AiServiceImpl.toMatchResults(arr, candidates(1, 2));

        assertEquals(List.of(2L, 1L), idsOf(list), "应按分数降序");
        assertEquals("物品2", list.get(0).getTitle(), "标题必须来自本地库，不用模型返回的");
    }
}
