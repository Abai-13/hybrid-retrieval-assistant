package com.lostfound.dto;

import lombok.Data;

import java.util.List;

/**
 * 一轮对话的返回体 —— 前端拿到它就够渲染了。
 *
 * <p><b>为什么失败也用 200 返回</b>：这是延续本项目既有 AI 链路的做法
 * （见 {@code AiQueryResponse.degradeReason}）—— 模型超时是**预期内的结果**，
 * 不是接口坏了。用 500 表达它，前端就只能弹一句"服务器错误"，
 * 而真正需要传达的是「这次没查成，条件还在，可以重试」。
 * 但失败**必须可辨识**：{@link #status} 为 FAILED、{@link #degradeReason} 非空，
 * 前端据此显示重试入口，绝不允许渲染成"没有找到"。
 *
 * <p>真正属于客户端错误的（会话不是你的、会话过期、请求参数不对）仍然走
 * 403 / 409 / 400，那些重试没有意义。
 */
@Data
public class ConversationTurnView {

    /** 助手消息正常完成 */
    public static final String STATUS_OK = "OK";
    /** 助手消息没完成 —— 条件保留、可重试，但绝不能说成"没匹配到" */
    public static final String STATUS_FAILED = "FAILED";

    /** 理解这一轮的模型调用失败（超时/网络/非法返回），本轮的"用户说了什么"没解析出来 */
    public static final String DEGRADE_UNDERSTAND_FAILED = "UNDERSTAND_FAILED";
    /** 理解成功，但精排/解释那次调用失败 */
    public static final String DEGRADE_MATCH_FAILED = "MATCH_FAILED";
    /** 整轮 90 秒预算耗尽，剩余时间不足以再发一次请求 */
    public static final String DEGRADE_TURN_TIMEOUT = "TURN_TIMEOUT";
    /**
     * 模型<b>调通了但返回的结构不可用</b>（不是 JSON、缺 intent、缺 matches/answer）。
     *
     * <p>和"调用失败"分开统计的意义：前者是服务不稳定，后者多半是提示词或模型换了版本。
     * 混在一起，排查时只能看到"失败率上升"，却不知道该去修网络还是修提示词。
     *
     * <p>⚠️ 它绝不能退化成"没找到" —— 那是把模型的锅安在平台数据上，
     * 用户会因为一次坏返回而以为平台上根本没有自己的东西。
     */
    public static final String DEGRADE_BAD_MODEL_OUTPUT = "BAD_MODEL_OUTPUT";

    private Long conversationId;

    /** 本轮结束后的会话版本 —— 前端下次发消息时可比对，判断自己看到的是不是最新状态 */
    private Integer version;

    /** 助手回复文字 */
    private String text;

    /** 结果卡片 */
    private List<ConversationCard> cards;

    /** 本轮结束后的「当前条件」 */
    private List<SearchCondition> conditions;

    /** 本轮意图 */
    private String intent;

    /** OK / FAILED */
    private String status;

    /** 降级原因；正常为 null */
    private String degradeReason;

    /** 本轮对应的 requestId —— 前端点「重试」时原样带回 */
    private String requestId;

    private TurnDiagnostics diagnostics;

    /**
     * 这是对一个**已完成 requestId 的幂等重放**，不是新跑的一轮。
     *
     * <p>重放回来的 {@link #text} / {@link #cards} / {@link #conditions} / {@link #version}
     * 全部是**那一轮的历史快照**，与当前会话状态无关 ——
     * 前端绝不能拿它覆盖页面上较新的条件、版本与卡片顺序，
     * 也不该把同一条回复再追加一遍（见 AiChat.vue 的 applyTurn）。
     *
     * <p>为什么必须显式区分：不带标记时，"重放最早那一轮"的响应长成
     * 「当前版本 + 旧条件 + 旧卡片」的样子，页面照着应用就会出现
     * 「version 已经是白色那轮的，条件却退回黑色」这种相互矛盾的状态，
     * 下一句「第二个」也会按错的那批卡片解析。
     */
    private boolean replayed;

    public boolean failed() {
        return STATUS_FAILED.equals(status);
    }
}
