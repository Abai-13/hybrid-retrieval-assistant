package com.lostfound.dto;

import java.util.Set;

/**
 * 本轮「用户到底想干什么」—— 由大模型判断，后端只做校验和兜底。
 *
 * <p><b>为什么意图靠模型而不是关键词表</b>：「不是第二个，再找找」「换一个」
 * 「这个不太像」表达的是同一件事，而「第二个还行」是相反的意思。
 * 关键词表要覆盖这些说法，就得列出一张永远不够长的表，
 * 而它一旦漏了，表现是"系统没听懂" —— 用户会以为是自己的问题。
 * 模型来判，代码只负责保证它给的结果在法律范围内（见 {@link #isValid}）。
 */
public final class TurnIntent {

    /** 信息够了，去检索 */
    public static final String SEARCH = "SEARCH";
    /** 还缺关键信息，先追问一句 */
    public static final String ASK_MORE = "ASK_MORE";
    /** 用户在问某个已展示物品"为什么像" */
    public static final String EXPLAIN_ITEM = "EXPLAIN_ITEM";
    /** 用户在否掉某个已展示物品 —— 后端把它记进排除项并重新检索 */
    public static final String EXCLUDE_ITEM = "EXCLUDE_ITEM";
    /** 问平台怎么用（怎么提交认领、怎么看进度） */
    public static final String PLATFORM = "PLATFORM";
    /** 寒暄或与找物无关 */
    public static final String SMALLTALK = "SMALLTALK";
    /**
     * 用户点了「当前条件」上的 × 撤回某个条件。
     * <p><b>模型永远不会返回它</b> —— 这是前端传下来的结构化指令，
     * 由后端直接产生。点 × 是确定操作，不该有被模型理解错的可能。
     */
    public static final String RETRACT = "RETRACT";

    /** 模型能返回的意图白名单 */
    private static final Set<String> MODEL_INTENTS =
            Set.of(SEARCH, ASK_MORE, EXPLAIN_ITEM, EXCLUDE_ITEM, PLATFORM, SMALLTALK);

    private TurnIntent() {
    }

    /**
     * 模型给的意图是否可用。
     *
     * <p>⚠️ <b>不合法就是"这轮不可用"，没有兜底意图</b>。
     * 曾经这里把非法意图兜底成 {@link #SEARCH}（想着"多搜一次总比装傻好"），
     * 但审查时发现这是错的：模型返回 {@code {}} 或拼错意图名时，
     * 系统会装作听懂了、照常去搜、还把这轮**当成成功轮次把条件写进摘要**。
     * 用户看到的是一堆莫名其妙的结果，却没有任何地方提示"这次其实没理解你"。
     * 正确做法是明确失败、保留条件、允许重试 —— 见
     * {@code TurnInterpreter.Interpretation#FAILED_OUTPUT}。
     */
    public static boolean isValid(String intent) {
        return intent != null && MODEL_INTENTS.contains(intent);
    }
}
