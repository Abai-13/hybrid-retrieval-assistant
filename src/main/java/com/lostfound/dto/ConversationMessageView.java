package com.lostfound.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话里的一条消息（给前端渲染用）。
 *
 * <p>{@code requestId} 一并返回是为了<b>刷新之后还能重试</b>：
 * 重试必须复用同一个 requestId，否则后端会当成新的一轮，
 * 多出一条重复消息、多花一次模型调用。
 */
@Data
public class ConversationMessageView {

    /** USER / ASSISTANT */
    private String role;

    private String text;

    /** 助手消息才有：可点击的结果卡片（已用数据库最新数据回填） */
    private List<ConversationCard> cards;

    /** OK / FAILED。FAILED 的助手消息会带 degradeReason，前端据此显示「重试」 */
    private String status;

    /** 本轮意图，仅用于诊断展示 */
    private String intent;

    /** 降级原因，正常为 null */
    private String degradeReason;

    /** 这一轮对应的请求标识 —— 重试时原样带回 */
    private String requestId;

    /** 该轮结束时的「当前条件」快照 */
    private List<SearchCondition> conditions;

    /**
     * 用户消息才有：这一轮带下来的**结构化撤回指令**。
     * <p>前端刷新后要能照着它重建「重试」—— 撤回是点 × 这种确定操作，
     * 重试必须原样重放指令，绝不能退化成让模型去猜一句自然语言。
     */
    private RetractRequest retract;

    private TurnDiagnostics diagnostics;

    private LocalDateTime createdAt;
}
