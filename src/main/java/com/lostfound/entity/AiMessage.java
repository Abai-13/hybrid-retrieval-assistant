package com.lostfound.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话里的一条消息 —— 用户原话或助手那一轮的结果。
 *
 * <p>一轮请求产生<b>两行</b>（USER + ASSISTANT），由唯一键
 * {@code uk_conv_request_role(conversation_id, request_id, role)} 保证
 * 「同一个 requestId 在同一会话里、同一角色只有一行」。
 *
 * <p><b>这条唯一键就是幂等的实现方式</b>：
 * <ul>
 *   <li>重放：请求带的 requestId 已经有 ASSISTANT 行且状态 OK → 直接把那一轮的
 *       结果再返回一次，不重复调模型（网络重试、用户点重试都会走到这里）；</li>
 *   <li>重跑：只有 USER 行、ASSISTANT 行是 FAILED 或不存在 → 这是"上次没做完"，
 *       应该真的重新跑一遍，然后 UPDATE 那一行而不是追加第三行。</li>
 * </ul>
 */
@Data
@TableName("ai_message")
public class AiMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    /** 这一轮正常完成 */
    public static final String STATUS_OK = "OK";
    /** 这一轮没做完（模型失败/超时/坏 JSON）—— 落库是为了刷新后仍能看到并重试，不是伪装成"没匹配到" */
    public static final String STATUS_FAILED = "FAILED";
    /**
     * 这一轮<b>正在处理中</b> —— 本轮开始时先占位，结束时改成 OK / FAILED。
     *
     * <p>它解决的是「同一个 requestId 被并发发两次」：先到的那条先占住这一行
     * （唯一键保证只有一个人能占），后到的一看状态是处理中，就知道该明确拒绝，
     * 而不是**再调一次模型**、再写一遍消息。
     *
     * <p>占位不是锁死：占位时间超过一轮的最大耗时（预算 + 余量）后视为陈旧，
     * 后来者可以接管 —— 否则进程在模型调用中途挂掉，这一轮就永远卡在"处理中"，
     * 用户重试多少次都进不来。
     */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** 客户端生成的请求标识。同一轮的重试**必须复用**它，否则会多出一轮 */
    private String requestId;

    /** USER / ASSISTANT */
    private String role;

    /** 用户原话，或助手这轮的回复文字 */
    private String content;

    /** OK / FAILED */
    private String status;

    /** 助手消息才有：本轮判定的意图（SEARCH / ASK_MORE / EXPLAIN_ITEM / EXCLUDE_ITEM / PLATFORM / SMALLTALK / RETRACT） */
    private String intent;

    /**
     * 助手消息的结构化载荷（JSON）：卡片、诊断、当时的条件快照。
     * <p>⚠️ 卡片里<b>只存 itemId / score / reason</b>：标题、类别、地点、状态在读取时
     * 从数据库回填。理由是"历史消息里的卡片也必须显示最新状态" ——
     * 一条上轮还显示「未认领」的物品被认领走之后，不能继续承诺可以认领。
     */
    private String payloadJson;

    /**
     * 乐观锁版本号 —— 让「处理中 → 终态」成为一次 CAS。
     *
     * <p>为什么一条消息也要版本号：一轮结束时要写终态，而同一轮的另一个并发请求
     * （或者用户点了重试）可能已经先写完了。没有 CAS，晚到的那条**失败结果会把已完成的
     * 结果覆盖成 FAILED** —— 用户明明拿到了答案，界面却告诉他这次失败了。
     */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 最后更新时间 —— 由数据库在每次 UPDATE 时自动维护。
     *
     * <p>用来判断"处理中"的占位是否已经陈旧（见 {@link #STATUS_IN_PROGRESS}）。
     * 不能用 {@code created_at}：接管一次占位并没有新建行，时间戳不会前进，
     * 那样"陈旧判定"会一直基于最初那次插入，越往后越容易误判。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
