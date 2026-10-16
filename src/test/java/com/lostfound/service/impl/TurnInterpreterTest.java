package com.lostfound.service.impl;

import cn.hutool.json.JSONUtil;
import com.lostfound.common.LlmTransport;
import com.lostfound.dto.SearchSummary;
import com.lostfound.dto.TurnIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型返回值的校验 —— <b>模型说的话只是"建议"，能不能采纳由这里决定</b>。
 *
 * <p>这部分特意与 HTTP 调用分开测：调用失败是网络问题，而这里守的是
 * "模型返回了一个看起来合法、其实越界的结果"—— 比如把序号指到不存在的第 9 张卡、
 * 一次返回 30 条事实、field 里塞一整句话。这类错误不会抛异常，
 * 它们会安安静静地写进用户的长期记忆里。
 */
class TurnInterpreterTest {

    private static LlmTransport.Result result(String content) {
        return new LlmTransport.Result(content, 120, 30);
    }

    // ===================== 正常解析 =====================

    @Test
    @DisplayName("正常返回：意图、事实、确定性、主体、追问文案都解析出来")
    void parsesFullResponse() {
        TurnInterpreter.Interpretation interpretation = TurnInterpreter.parse(result("""
                {"intent":"SEARCH","referenceIndex":null,"subject":"手机","subjectChanged":false,
                 "facts":[{"field":"颜色","value":"黑色","certainty":"CERTAIN"},
                          {"field":"地点","value":"图书馆","certainty":"UNCERTAIN"}],
                 "removedFields":[],"reply":""}
                """));

        assertFalse(interpretation.failed());
        assertEquals(TurnIntent.SEARCH, interpretation.intent());
        assertEquals("手机", interpretation.subject());
        assertEquals(2, interpretation.facts().size());
        assertEquals(SearchSummary.UNCERTAIN, interpretation.facts().get(1).getCertainty());
        assertEquals(120, interpretation.promptTokens());
        assertEquals(30, interpretation.completionTokens());
    }

    @Test
    @DisplayName("「第二个为什么像」：序号原样带出来，由上层去上一轮卡片里解析")
    void parsesReferenceIndex() {
        TurnInterpreter.Interpretation interpretation = TurnInterpreter.parse(result("""
                {"intent":"EXPLAIN_ITEM","referenceIndex":2,"facts":[],"removedFields":[],"reply":""}
                """));

        assertEquals(TurnIntent.EXPLAIN_ITEM, interpretation.intent());
        assertEquals(2, interpretation.referenceIndex());
    }

    // ===================== 越界的返回值 =====================

    @Test
    @DisplayName("★ 意图缺失或不在白名单里：整轮判失败，【绝不兜底成 SEARCH】")
    void missingOrUnknownIntentFailsTheTurn() {
        // 曾经这里兜底成 SEARCH（"多搜一次总比装傻好"），但那是错的：
        // 模型返回 {} 或拼错意图名时，系统会装作听懂了、照常去搜，
        // **还把这轮当成成功轮次把条件写进用户的记忆** —— 用户看到一堆莫名其妙的结果，
        // 却没有任何地方提示"这次其实没理解你"
        assertThrows(Exception.class,
                () -> TurnInterpreter.parse(result("{\"intent\":\"找个东西\"}")));
        assertThrows(Exception.class, () -> TurnInterpreter.parse(result("{}")));
        assertThrows(Exception.class, () -> TurnInterpreter.parse(result("{\"intent\":null}")));
        assertThrows(Exception.class, () -> TurnInterpreter.parse(result("{\"intent\":\"search\"}")));
    }

    @Test
    @DisplayName("★ 非法序号（0 / 负数）一律作废成 null，交由上层追问而不是猜一条")
    void illegalReferenceIndexBecomesNull() {
        assertNull(TurnInterpreter.parse(result(
                "{\"intent\":\"EXPLAIN_ITEM\",\"referenceIndex\":0}")).referenceIndex());
        assertNull(TurnInterpreter.parse(result(
                "{\"intent\":\"EXPLAIN_ITEM\",\"referenceIndex\":-3}")).referenceIndex());
    }

    @Test
    @DisplayName("★ 声称换了物品却没给新主体：不能当真（否则会把用户的条件白白清空）")
    void subjectChangedWithoutSubjectIsIgnored() {
        TurnInterpreter.Interpretation interpretation = TurnInterpreter.parse(result(
                "{\"intent\":\"SEARCH\",\"subjectChanged\":true,\"subject\":null}"));

        assertFalse(interpretation.subjectChanged());
    }

    @Test
    @DisplayName("字段名或值为空的事实整条丢弃；超长的截断")
    void dropsUnusableFactsAndTruncates() {
        TurnInterpreter.Interpretation interpretation = TurnInterpreter.parse(result("""
                {"intent":"SEARCH","facts":[
                  {"field":"","value":"黑色"},
                  {"field":"颜色","value":""},
                  {"field":"颜色","value":"黑"},
                  {"field":"这是个特别长的字段名它根本不该通过校验","value":"x"}],
                 "removedFields":[],"reply":""}
                """));

        assertEquals(2, interpretation.facts().size(), "只有字段名和值都有的两条能进来");
        assertEquals(SearchSummary.MAX_FIELD_LEN,
                interpretation.facts().get(1).getField().length(), "超长字段名被截断");
    }

    @Test
    @DisplayName("事实条数有上限 —— 一次说八件事的用户不存在，多了多半是模型在补全")
    void capsFactCount() {
        StringBuilder facts = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            facts.append(i > 0 ? "," : "").append("{\"field\":\"字段").append(i)
                    .append("\",\"value\":\"值").append(i).append("\"}");
        }
        TurnInterpreter.Interpretation interpretation = TurnInterpreter.parse(
                result("{\"intent\":\"SEARCH\",\"facts\":[" + facts + "],\"reply\":\"\"}"));

        assertEquals(TurnInterpreter.MAX_FACTS, interpretation.facts().size());
    }

    @Test
    @DisplayName("撤回字段：去空白、超长截断、条数有上限")
    void sanitizesRemovedFields() {
        TurnInterpreter.Interpretation interpretation = TurnInterpreter.parse(result("""
                {"intent":"SEARCH","facts":[],"removedFields":["  颜色  ","",null]}
                """));

        assertEquals(List.of("颜色"), interpretation.removedFields());
    }

    @Test
    @DisplayName("reply 缺失时是空串而不是 null —— 上层判空时不用到处写 null 检查")
    void missingReplyBecomesEmptyString() {
        assertEquals("", TurnInterpreter.parse(result("{\"intent\":\"ASK_MORE\"}")).reply());
    }

    @Test
    @DisplayName("模型返回的不是合法 JSON：parse 抛出，由上层转成可重试的失败（不是「没找到」）")
    void malformedJsonThrows() {
        assertThrows(Exception.class,
                () -> TurnInterpreter.parse(result("```json\n{\"intent\":\"SEARCH\"}\n```")));
    }

    @Test
    @DisplayName("上下文拼装：条件、历史、卡片列表都有长度上限，不把整段聊天原样重发")
    void buildsBoundedContext() {
        SearchSummary summary = SearchSummary.fromJson("""
                {"conditions":[{"field":"主体","value":"手机","certainty":"CERTAIN","source":"我丢了手机"},
                               {"field":"地点","value":"图书馆","certainty":"UNCERTAIN","source":"可能在图书馆"}],
                 "excludedItemIds":[9]}
                """);

        String conditions = TurnInterpreter.describeConditions(summary);
        assertTrue(conditions.contains("手机") && conditions.contains("图书馆"));
        assertTrue(conditions.contains("不确定"), "不确定性必须让模型看到，否则它会当成硬条件");
        assertTrue(conditions.contains("9"), "排除项也要让模型看到，免得它又把这条推荐回来");

        List<TurnInterpreter.HistoryTurn> history = List.of(
                new TurnInterpreter.HistoryTurn("我丢了一部手机", "请问是什么颜色？", List.of()),
                new TurnInterpreter.HistoryTurn("黑色", "找到了两条", List.of("黑色手机A", "黑色手机B")));
        String rendered = TurnInterpreter.describeHistory(history);
        assertTrue(rendered.contains("用户：我丢了一部手机"));
        assertTrue(rendered.contains("助手：找到了两条"));

        String displayed = TurnInterpreter.describeDisplayed(List.of("黑色手机A", "黑色手机B"));
        assertTrue(displayed.startsWith("1. 黑色手机A"), "编号从 1 开始 —— 用户说的「第二个」就是这个 2");
        assertTrue(displayed.contains("2. 黑色手机B"));

        String longText = "长".repeat(500);
        assertTrue(TurnInterpreter.truncate(longText, 100).length() <= 101);
    }

    @Test
    @DisplayName("没有条件/没有历史/没有卡片时给出人类可读的说明，而不是空字符串")
    void describesEmptyContext() {
        assertTrue(TurnInterpreter.describeConditions(SearchSummary.empty()).contains("还没有"));
        assertTrue(TurnInterpreter.describeHistory(List.of()).contains("还没有"));
        assertTrue(TurnInterpreter.describeDisplayed(List.of()).contains("没有展示"));
    }

    @Test
    @DisplayName("提示词里必须包含「只记用户明确说过的」这条约束 —— 它是摘要可信的前提")
    void promptForbidsInference() {
        // 用反射读常量，防止有人"顺手"删掉那条约束
        String prompt = readConstant("INTERPRET_TEMPLATE");

        assertTrue(prompt.contains("本轮用户消息"), "少了这条，模型会开始替用户补充条件");
        assertTrue(prompt.contains("绝不要"), "历史里出现过的条件不能被当成用户这一轮说的，再写一遍");
        assertTrue(prompt.contains("UNCERTAIN"), "不确定性必须能表达");
        assertTrue(prompt.contains("removedFields"), "撤回必须能表达");
        assertTrue(prompt.contains("referenceIndex"), "指代必须能表达");
        assertTrue(prompt.contains("subjectChanged"), "换物品必须能表达");
        assertTrue(prompt.contains("不知道") || prompt.contains("不要编"),
                "不知道的学校规定不能让模型编 —— 它一编，用户就会当成真的去跑一趟");
    }

    @Test
    @DisplayName("★ 平台问答的提示词里要有【真实的认领规则】，不能只给几个功能名")
    void promptCarriesRealPlatformRules() {
        String prompt = readConstant("INTERPRET_TEMPLATE");

        // 只有功能名的话，模型会拿常识补按钮名和流程，用户照着做就找不到入口
        assertTrue(prompt.contains("特征证明"), "认领要凭特征证明，这条不写模型不会知道");
        assertTrue(prompt.contains("发布者"), "审核人是物品发布者");
        assertTrue(prompt.contains("确认收到"), "审核通过≠已归还，要线下交接后确认");
        assertTrue(prompt.contains("联系方式"), "联系方式可见性要按现有权限说明");
        assertTrue(prompt.contains("我的申请") || prompt.contains("收到的申请"),
                "要给出真实存在的入口名，不能让模型编按钮");
        assertTrue(prompt.contains("只能依据"), "必须限制它只能依据给定事实回答");
    }

    private static String readConstant(String name) {
        try {
            java.lang.reflect.Field field = TurnInterpreter.class.getDeclaredField(name);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            return fail("找不到常量 " + name + " —— 它可能被改名或删掉了: " + JSONUtil.toJsonStr(e));
        }
    }
}
