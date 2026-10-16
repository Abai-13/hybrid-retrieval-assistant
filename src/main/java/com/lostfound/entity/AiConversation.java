package com.lostfound.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次「找物会话」—— 短期记忆的边界。
 *
 * <p><b>为什么必须落库而不是放前端或内存</b>：会话里存的是「用户说过什么」，
 * 它天然属于登录账号。放在页面变量或进程内存里，刷新就丢、重启就丢，
 * 而且等于把这份数据的所有权交给了不可信的输入。
 *
 * <p><b>摘要为什么是结构化 JSON 而不是一段自然语言</b>：见 {@link #summaryJson}。
 *
 * <p><b>version 是给并发用的，不是给业务用的</b>：同一会话的两条消息几乎同时到达时，
 * 两条都基于同一份旧摘要计算，谁后写谁就把前一条覆盖掉了。有了它，
 * 后写的那条会因为 {@code WHERE version = ?} 匹配不到而失败 —— 失败是显式的 409，
 * 不是静默的数据丢失。
 */
@Data
@TableName("ai_conversation")
public class AiConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户 —— <b>所有会话操作的唯一所有权依据</b>。
     * <p>接口路径里的会话 id 是客户端传来的，属于不可信输入：
     * 每个读写都必须拿它和当前登录用户比对，比对不通过就是 403/404。
     */
    private Long userId;

    /**
     * 结构化找物摘要（JSON），见 {@code ConversationSummary}。
     * <p><b>为什么不存一段自然语言总结</b>：自然语言没法可靠地做「纠正覆盖旧值」
     * 和「撤回某一项」—— 那需要再问一次模型，而模型的答复可能和上一次不一致。
     * 结构化字段让「加 / 改 / 删」都是确定性的代码行为，模型只负责提出变更建议。
     */
    private String summaryJson;

    /**
     * 正在处理的那一轮的 requestId；空闲时为 null。**会话级占用**。
     *
     * <p>为什么光有 version 不够：版本号只能保证"不覆盖别人的写入"，
     * 保证不了"同一段对话同时只跑一轮"。两个请求各自读同一份摘要、
     * 各自去调模型，后完成的那个会带着旧摘要覆盖新结果（409 能挡住，
     * 但两轮模型调用已经白花了，用户还得再点一次重试）。
     * 有了这个显式占用，"这一轮正在跑"就是一条能查、能等、也能在超时后接管的事实。
     *
     * <p>⚠️ 它的读写**只走 {@link com.lostfound.mapper.AiConversationMapper} 里的条件 SQL**，
     * 不走实体更新 —— 判断和写入必须是同一条语句。
     */
    private String activeRequestId;

    /** 占用的开始时间；超过一轮的最大耗时即视为陈旧，允许后来者接管 */
    private LocalDateTime activeSince;

    /**
     * 乐观锁版本号。
     * <p>⚠️ 更新摘要必须走 {@code updateById}（由 {@code @Version} 自动加
     * {@code WHERE version = ?}），<b>不要用 {@code LambdaUpdateWrapper} 手写条件绕开它</b> ——
     * 那样版本号就不会自增，下一次并发依然会撞上。
     */
    @Version
    private Integer version;

    /** 过期时间 ＝ 最后一次活动 + 保留天数。过期的会话会被清理，并要求用户重新开始 */
    private LocalDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
