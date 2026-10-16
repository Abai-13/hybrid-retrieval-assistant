package com.lostfound.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lostfound.common.LlmTransport;
import com.lostfound.config.LlmConfig;
import com.lostfound.dto.SearchCondition;
import com.lostfound.dto.SearchFact;
import com.lostfound.dto.SearchSummary;
import com.lostfound.dto.TurnIntent;
import com.lostfound.entity.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把「用户这一轮说了什么」翻译成结构化语义 —— <b>一轮里的第一次模型调用</b>。
 *
 * <p>它只做理解，不做检索、不碰数据库。输出经过校验后交给
 * {@link ConversationServiceImpl} 决定下一步（追问 / 检索 / 解释 / 排除）。
 *
 * <p><b>为什么理解必须交给模型，而不是写关键词表</b>：
 * 「其实是在食堂」要能覆盖旧地点、「颜色记不清了」要能撤回颜色、
 * 「不是第二个」要能指到上一轮的第二张卡 —— 这三件事长得完全不像，
 * 靠关键词表就得为每种说法写一条规则，而用户永远有第四种说法。
 * 模型的输出是<b>建议</b>：字段名、长度、取值范围由这里校验，
 * 合并与落库由后端做，模型改不了既定事实。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnInterpreter {

    private final LlmConfig llmConfig;
    private final RestTemplate llmRestTemplate;

    /** 与 {@code AiServiceImpl} 共用同一个配置键：重试规则只有一处语义 */
    @Value("${llm.max-retries:2}")
    private int maxRetries;

    /** 单轮最多接受几条新事实 —— 一次说八件事的用户不存在，多了多半是模型在自行补全 */
    static final int MAX_FACTS = 8;
    /** 单轮最多撤回几个条件 */
    static final int MAX_REMOVALS = 6;
    /** 主体名长度上限 */
    static final int MAX_SUBJECT_LEN = 20;
    /** 回复文本长度上限 */
    static final int MAX_REPLY_LEN = 300;
    /** 历史对话里每条消息的截断长度 */
    static final int MAX_HISTORY_TEXT_LEN = 120;
    /** 最多带几轮历史（计划里定的「默认最近 4 轮」） */
    public static final int DEFAULT_HISTORY_TURNS = 4;

    private static final String INTERPRET_SYSTEM_PROMPT = """
            你是校园失物招领平台的找物助手。你的任务是理解用户这一轮说了什么，并输出结构化的 JSON。
            你不负责检索物品，也不负责编造任何信息。只输出 JSON。""";

    /**
     * 理解提示词。
     *
     * <p>三条规则是踩过之后写死的，不能删：
     * <ol>
     *   <li><b>只记用户明确说过的</b>。只要允许"合理推断"，摘要里很快就会混进
     *       模型自己想象的颜色和地点，而用户完全不知道系统在按什么找东西；</li>
     *   <li><b>来源要能被追溯</b>。所以这里不让模型写来源 —— 来源由后端直接取用户原话，
     *       模型一旦转述，就不再是"用户说过什么"的证据了；</li>
     *   <li><b>不知道就说不知道</b>。校园规定、办公地点、电话这类信息模型多半不知道，
     *       它一编，用户就会当成真的去跑一趟。</li>
     * </ol>
     */
    private static final String INTERPRET_TEMPLATE = """
            【当前已知条件】
            %s

            【最近对话】
            %s

            【上一轮展示的物品（按当时顺序编号）】
            %s

            【本轮用户消息】
            %s

            请只输出 JSON：
            {"intent":"SEARCH","referenceIndex":null,"subject":null,"subjectChanged":false,
             "facts":[{"field":"","value":"","certainty":"CERTAIN"}],"removedFields":[],"reply":""}

            【先定意图：按下面的优先级，从上往下第一个命中的就是答案】
            A. 用户在否掉某条已展示的物品（「不是第二个」「这个不对」「换一个」「上面那条不像」）
               → intent=EXCLUDE_ITEM，referenceIndex 填他指的那条。
               ⚠️ 这条优先级最高：句子里同时出现「再找找」「继续找」「再帮我找找」时，
                  只要他在**否定某一条**，就是 EXCLUDE_ITEM，**不是 SEARCH**。
            B. 用户在问某条已展示的物品为什么像 → intent=EXPLAIN_ITEM，referenceIndex 填编号。
            C. 用户在问平台怎么操作、谁负责、能不能、需要什么（申请认领 / 审核 / 确认收到 / 发布 / 联系）
               → intent=PLATFORM。哪怕这句话里夹着别的内容、或者开头是「如果找到了」，
                 只要它在问流程或规则，就是 PLATFORM。
            D. 信息够去找 → SEARCH；还缺关键信息 → ASK_MORE。

            规则：
            1. intent 只能是：SEARCH / ASK_MORE / EXPLAIN_ITEM / EXCLUDE_ITEM / PLATFORM / SMALLTALK。
               SMALLTALK 只用于寒暄或与找东西无关的话。
            2. facts 只放**本轮用户消息里**说过的信息。
               ⚠️「最近对话」和「当前已知条件」只是背景 —— 那里的条件**绝不要**再写一遍。
                  重复写不会加重它，反而会把这个条件的来源记成本轮这句话，来源就错了。
               把说过的每一项都**单独列出来**，不要合并、不要漏：
               「黑色的苹果手机」要给出 颜色=黑色 **和** 品牌=苹果（两个都要）；
               「一把银色车钥匙，挂着红色绳子」要给出 颜色=银色、主体=车钥匙、特征=红色绳子。
               field 用简短的中文名：主体 / 颜色 / 品牌 / 型号 / 地点 / 时间 / 特征 等。
            3. certainty 只有一个判据：**本句话里有没有限定词**。
               「可能 / 好像 / 大概 / 记不清 / 不确定」→ UNCERTAIN；
               用户直接说出来的（「银色车钥匙」「挂着红色绳子」）→ **CERTAIN**。
               ⚠️ 不要因为"物品还没找到"就把用户明说的特征标成不确定。
            4. 用户纠正了某个条件（「其实是食堂」）→ 照常写进 facts，field 和旧条件保持一致，系统会覆盖旧值。
            5. 用户明确撤回某个条件（「颜色记不清了」「不用管颜色了」）→ 把那个 field 写进 removedFields，
               不要放进 facts。
            6. referenceIndex 只能取上面「上一轮展示的物品」里出现过的编号；没有就填 null。
            7. 只有用户**明确表示要换另一件东西**（「我另外还丢了…」「现在换找…」「不说这个了，我要找…」）
               才填 subjectChanged=true。
               ⚠️ 只是补充了一个地点或颜色、或者把同一件东西换了个说法（「车钥匙」→「钥匙」），
                  **绝对不是换物品**：subject 可以填，但 subjectChanged 必须是 false。
                  误填 true 会把用户之前说过的颜色、特征、地点全部清空。
               如果你拿不准他是不是要换，就用 ASK_MORE 先问一句。
            8. intent 为 ASK_MORE / PLATFORM / SMALLTALK 时，reply 写你要对用户说的话；
               其余情况 reply 留空字符串。
               ASK_MORE 只问**一个**最影响检索结果的信息，不要一次问一串。
            10. PLATFORM 时**只能依据下面这几条平台事实回答**，
                不要补充任何这里没写的细节（审核要几天、在哪里领取、办公时间、电话 ——
                这些你都不知道，一律直说不知道、建议去问失物招领处）。
                平台事实：
                · 物品分「寻物」和「招领」两类，用「发布物品」页发布；
                · 想认领就在物品详情页点「申请认领」，必须填写只有物主才知道的特征证明
                  （比如证件号码、物品上的划痕、壁纸、贴纸这类细节）；
                · 申请提交后由**物品发布者**审核，可以驳回（驳回必须写理由）；
                · 审核通过不等于已经归还，双方还要线下交接，交接后由申请人在「我的申请」里
                  点「确认收到」，物品才算完成归还；
                · 发布者在「收到的申请」里审核与查看证明；
                · 联系方式不会对所有人公开，只在认领流程里给到物品发布者和审核通过的申请人；
                · 审核进度以站内通知为准。
                用户如果想做的操作不在上面（比如让你直接替他改状态、绕过审核、要别人的联系方式），
                说明你做不到，并告诉他该走哪个入口。
            """;

    /** 模型调用本身没成功（超时、网络、5xx）—— 服务的问题 */
    public static final String FAILED_CALL = "CALL_FAILED";
    /**
     * 模型调通了，但返回的结构不可用（不是 JSON、缺 intent、intent 不在白名单里）。
     * <p>和 {@link #FAILED_CALL} 分开，是因为两者给用户的建议不同：
     * 前者"服务暂时不可用，等会儿再试"，后者"这次没听懂，重试一下"。
     */
    public static final String FAILED_OUTPUT = "BAD_OUTPUT";

    /** 理解结果 —— 已经过校验，可以安全地用于合并摘要 */
    public record Interpretation(String intent, Integer referenceIndex, String subject,
                                 boolean subjectChanged, List<SearchFact> facts,
                                 List<String> removedFields, String reply,
                                 int promptTokens, int completionTokens,
                                 String failureKind, String failureDetail) {

        public boolean failed() {
            return failureKind != null;
        }

        static Interpretation fail(String kind, String detail) {
            return new Interpretation(null, null, null, false, List.of(), List.of(), "",
                    0, 0, kind, detail);
        }
    }

    /** 解释结果（「第二个为什么像」） */
    public record Explanation(String answer, Long itemId, int promptTokens, int completionTokens,
                              String failureKind, String failureDetail) {
        public boolean failed() {
            return failureKind != null;
        }
    }

    /** 供模型理解上下文的一轮历史 */
    public record HistoryTurn(String userText, String assistantText, List<String> displayedItems) {
    }

    /**
     * 理解用户这一轮。
     *
     * @return 解析并校验后的结果；失败时 {@link Interpretation#failed()} 为 true，
     * 调用方据此把这轮标成可重试的失败，<b>绝不能降级成"没找到"</b>
     */
    public Interpretation interpret(SearchSummary summary, List<HistoryTurn> history,
                                   List<String> referenceItems, String message, long deadlineEpochMs) {
        String prompt = String.format(INTERPRET_TEMPLATE,
                describeConditions(summary), describeHistory(history),
                describeDisplayed(referenceItems), message);

        LlmTransport.Result call = LlmTransport.call(llmRestTemplate, llmConfig,
                buildBody(INTERPRET_SYSTEM_PROMPT, prompt), maxRetries, deadlineEpochMs);
        if (call == null) {
            return Interpretation.fail(FAILED_CALL, "理解本轮意图的模型调用失败（超时/网络错误）");
        }
        try {
            return parse(call);
        } catch (Exception e) {
            log.warn("理解模型的返回不是可用的 JSON: {}", call.content());
            return Interpretation.fail(FAILED_OUTPUT,
                    "模型返回的不是合法 JSON：" + truncate(call.content(), 160));
        }
    }

    /**
     * 解析并校验模型返回。
     *
     * <p>两条不同的底线：
     * <ul>
     *   <li><b>关键结构必须存在</b> —— 必须有 intent，且在白名单内。
     *       缺了它这轮就是"没听懂"，宁可失败让用户重试，也不能兜底成 SEARCH：
     *       兜底会让系统装作理解了，把莫名其妙的结果和条件一起写进用户的记忆；</li>
     *   <li><b>非关键字段可以缺、可以越界</b> —— facts / removedFields / reply
     *       缺了当成空，超长截断，单条不合法就丢掉那一条。
     *       这些是"能少不能错"的内容，为它们整轮失败不值得。</li>
     * </ul>
     */
    static Interpretation parse(LlmTransport.Result call) {
        JSONObject json = JSONUtil.parseObj(call.content());
        String rawIntent = json.getStr("intent");
        if (!TurnIntent.isValid(rawIntent)) {
            log.warn("模型没有返回可识别的意图: content={}", call.content());
            throw new IllegalStateException("模型没有返回可识别的意图（intent=" + rawIntent + "）");
        }
        String intent = rawIntent;

        Integer referenceIndex = json.getInt("referenceIndex");
        if (referenceIndex != null && referenceIndex < 1) {
            referenceIndex = null;
        }

        String subject = clip(json.getStr("subject"), MAX_SUBJECT_LEN);
        boolean subjectChanged = Boolean.TRUE.equals(json.getBool("subjectChanged", false))
                // 没有新主体就谈不上"换了物品"，否则会把用户的条件白白清空
                && subject != null;

        List<SearchFact> facts = new ArrayList<>();
        JSONArray rawFacts = json.getJSONArray("facts");
        if (rawFacts != null) {
            for (Object o : rawFacts) {
                if (facts.size() >= MAX_FACTS) {
                    break;
                }
                if (o instanceof JSONObject f) {
                    SearchFact fact = new SearchFact();
                    fact.setField(clip(f.getStr("field"), SearchSummary.MAX_FIELD_LEN));
                    fact.setValue(clip(f.getStr("value"), SearchSummary.MAX_VALUE_LEN));
                    fact.setCertainty(f.getStr("certainty"));
                    if (fact.getField() != null && fact.getValue() != null) {
                        facts.add(fact);
                    }
                }
            }
        }

        List<String> removed = new ArrayList<>();
        JSONArray rawRemoved = json.getJSONArray("removedFields");
        if (rawRemoved != null) {
            for (Object o : rawRemoved) {
                if (removed.size() >= MAX_REMOVALS) {
                    break;
                }
                // ⚠️ JSON 里的 null 到了 Hutool 这边不是 Java null，而是 JSONNull，
                //    直接 String.valueOf 会得到字符串 "null" —— 于是摘要里就多出
                //    一个叫「null」的幽灵字段，而且它会一直待在"要删掉的字段"里。
                if (o == null || o instanceof cn.hutool.json.JSONNull) {
                    continue;
                }
                String field = clip(String.valueOf(o), SearchSummary.MAX_FIELD_LEN);
                // 模型偶尔会把 null 写成字符串
                if (field != null && !"null".equalsIgnoreCase(field)) {
                    removed.add(field);
                }
            }
        }

        String reply = clip(json.getStr("reply"), MAX_REPLY_LEN);

        return new Interpretation(intent, referenceIndex, subject, subjectChanged,
                facts, removed, reply == null ? "" : reply,
                call.promptTokens(), call.completionTokens(), null, null);
    }

    /**
     * 「第二个为什么像」—— 把这一条目击的公开资料和当前条件交给模型，让它说清
     * 像在哪、以及哪里还需要用户自己核对。
     *
     * <p>提示词里刻意禁止两件事：<b>不要说"这一定是你的"</b>（系统没有这个判断依据），
     * <b>不要给联系方式</b>（那属于认领流程，不在助手的职责里）。
     */
    public Explanation explain(Item item, SearchSummary summary, String question, long deadlineEpochMs) {
        String prompt = String.format("""
                【用户想了解的这条招领信息】
                标题：%s
                类别：%s
                拾获地点：%s
                补充描述：%s
                当前状态：%s

                【用户自己描述过的条件】
                %s

                【用户的问题】
                %s

                请只输出 JSON：{"answer":"……"}
                answer 要求：
                - 不超过 120 字，口语化
                - 说清①这条信息里有哪些地方和用户的描述对得上 ②有哪些不确定、需要用户自己核对的
                - 信息里没写的（比如物品上没有的特征），不要猜、不要说"应该是"
                - 不要说"这一定是你的物品"，也不要给出任何联系方式（联系方式在认领流程里才能看到）
                - 如果这条信息明显和用户描述对不上，直接说明哪里对不上
                """,
                nvl(item.getTitle()), nvl(item.getCategory()), nvl(item.getLocation()),
                truncate(nvl(item.getDescription()), 120), statusText(item.getStatus()),
                summary.buildQuery(), question);

        LlmTransport.Result call = LlmTransport.call(llmRestTemplate, llmConfig,
                buildBody(INTERPRET_SYSTEM_PROMPT, prompt), maxRetries, deadlineEpochMs);
        if (call == null) {
            return new Explanation(null, item.getId(), 0, 0, FAILED_CALL,
                    "解释这条匹配的模型调用失败（超时/网络错误）");
        }
        try {
            JSONObject json = JSONUtil.parseObj(call.content());
            String answer = clip(json.getStr("answer"), MAX_REPLY_LEN);
            if (answer == null) {
                // 解释里**只有 answer 一个字段**，缺了就没有任何可用的东西
                return new Explanation(null, item.getId(), call.promptTokens(), call.completionTokens(),
                        FAILED_OUTPUT, "模型没有返回 answer 字段：" + truncate(call.content(), 160));
            }
            return new Explanation(answer, item.getId(), call.promptTokens(), call.completionTokens(),
                    null, null);
        } catch (Exception e) {
            return new Explanation(null, item.getId(), call.promptTokens(), call.completionTokens(),
                    FAILED_OUTPUT, "模型返回的不是合法 JSON：" + truncate(call.content(), 160));
        }
    }

    // ===================== 上下文拼装 =====================

    private JSONObject buildBody(String systemPrompt, String userPrompt) {
        JSONObject body = new JSONObject();
        body.set("model", llmConfig.getModel());
        body.set("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        // 理解要可复现：同一句话不该每次得到不同的意图
        body.set("temperature", 0);
        body.set("response_format", Map.of("type", "json_object"));
        body.set("max_tokens", llmConfig.getMaxTokens());
        return body;
    }

    /** 把摘要渲染成模型能读的紧凑形式 —— 只给 field/value/certainty，来源是给人看的，不必挤占 token */
    static String describeConditions(SearchSummary summary) {
        if (summary == null || summary.viewConditions().isEmpty()) {
            return "（还没有任何条件）";
        }
        StringBuilder sb = new StringBuilder();
        for (SearchCondition c : summary.viewConditions()) {
            sb.append("- ").append(c.getField()).append("：").append(c.getValue());
            if (SearchSummary.UNCERTAIN.equals(c.getCertainty())) {
                sb.append("（用户自己也不确定）");
            }
            sb.append('\n');
        }
        if (!summary.getExcludedItemIds().isEmpty()) {
            sb.append("- 用户已明确否掉的物品 id：").append(summary.getExcludedItemIds()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 历史对话 —— <b>真的有长度上限</b>。
     * <p>计划里说的"候选与历史共用模型上下文预算"落在这里：历史最多
     * {@value #DEFAULT_HISTORY_TURNS} 轮、每条再截断到
     * {@value #MAX_HISTORY_TEXT_LEN} 字。不带上限地重发全部聊天记录，
     * 到第十轮光上下文就能把 token 预算吃掉一大半，而前十轮里真正还有用的
     * 其实只有摘要里已经记下的那些。
     */
    static String describeHistory(List<HistoryTurn> history) {
        if (history == null || history.isEmpty()) {
            return "（还没有对话）";
        }
        StringBuilder sb = new StringBuilder();
        for (HistoryTurn turn : history) {
            sb.append("用户：").append(truncate(nvl(turn.userText()), MAX_HISTORY_TEXT_LEN)).append('\n');
            if (turn.assistantText() != null && !turn.assistantText().isBlank()) {
                sb.append("助手：").append(truncate(turn.assistantText(), MAX_HISTORY_TEXT_LEN)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 用户此刻**能看到的卡片**列表 —— 「第二个」指的就是这个列表里的第 2 条。
     *
     * <p>注意传进来的不是"上一轮的消息"，而是"最近一次真的展示了卡片的那一轮"：
     * 用户问「第二个为什么像」时，他指的是屏幕上正显示的那几张卡。
     * 如果中间夹了一轮纯文字回复（比如他先问了句平台怎么用），
     * 卡片并没有消失，指代当然也不该失效。
     */
    static String describeDisplayed(List<String> displayed) {
        if (displayed == null || displayed.isEmpty()) {
            return "（当前没有展示中的物品）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < displayed.size(); i++) {
            sb.append(i + 1).append(". ").append(displayed.get(i)).append('\n');
        }
        return sb.toString();
    }

    static String statusText(String status) {
        return switch (nvl(status)) {
            case Item.STATUS_UNCLAIMED -> "待认领";
            case Item.STATUS_CLAIMING -> "认领中（已有人提交申请，尚未交接）";
            case Item.STATUS_CLAIMED -> "已被认领（已归还给他人）";
            default -> status;
        };
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 拼给模型看的文本时的截断：加省略号，让模型知道这里被截过 */
    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 数据字段的截断：<b>不加省略号</b>。
     *
     * <p>两者的区别不是美观问题：{@code MAX_FIELD_LEN} 这类上限是<b>契约</b>，
     * 截断后多出来的那个「…」会让结果变成 max+1，于是同一个限制在
     * 解释器里是 13 个字、在摘要里是 12 个字 —— 上下限对不上的地方，
     * 早晚会在某次比较里冒出来。
     */
    static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : (v.length() <= max ? v : v.substring(0, max));
    }
}
