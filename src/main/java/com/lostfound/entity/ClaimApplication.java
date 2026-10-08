package com.lostfound.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 认领申请 —— 失主对某条招领（FOUND）物品发起的认领请求。
 *
 * <p><b>一个物品可以被多人申请</b>（1:N），这是它独立成表、而不是往 {@link Item} 里塞字段的原因：
 * 把「申请人」「证明」塞进 item，就成了一对一字段存一对多数据，只能保留最后一条，历史全丢。
 *
 * <p><b>状态流转</b>见 {@code ClaimServiceImpl} 里的 {@code ALLOWED_TRANSITIONS}：
 * <pre>
 * PENDING ──通过──> APPROVED ──申请人确认收到──> COMPLETED
 *    │
 *    ├──驳回──> REJECTED
 *    └──撤回──> CANCELLED
 * </pre>
 * 后三者是终态。被驳回/撤回后<b>可以重新申请</b> —— 靠 {@code uk_item_applicant_active}
 * 这个唯一键实现「同一人对同一物品只能有一条<i>活跃</i>申请」，终态的旧记录不占用唯一性。
 */
@Data
@TableName("claim_application")
public class ClaimApplication {

    // 状态常量 —— 与 sql/schema.sql 中 status 列的注释保持一致
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被申请的物品ID */
    private Long itemId;

    /** 申请人（失主）ID */
    private Long applicantId;

    /**
     * 物品发布者（拾到者）ID —— 冗余自 item.user_id，避免查「待我审核」时 JOIN item。
     * <p>⚠️ 它等于<b>提交那一刻</b>的 item.user_id。只要 item 不支持转让归属，这个冗余就是安全的；
     * 将来若加了「转让」功能，这里会失真 —— 届时必须一并处理。
     */
    private Long ownerId;

    /** 物品特征证明 —— 审核的依据，也是防冒领的关键。<b>不对外公开</b>（公开即可被照抄冒领） */
    private String proofText;

    /** 证明图片URL */
    private String proofImage;

    /** 申请人为「本次认领」留的联系方式；为空时回落到 user.phone */
    private String contact;

    /** PENDING / APPROVED / REJECTED / CANCELLED / COMPLETED */
    private String status;

    /** 驳回理由（业务上驳回必填，DB 层宽松） */
    private String rejectReason;

    /** 审核时间 —— 业务时间，只能由 Service 显式写入，DB 不能给默认值 */
    private LocalDateTime reviewedAt;

    /** 完成归还时间 —— 同上 */
    private LocalDateTime completedAt;

    /*
     * ⚠️ active_flag / approved_flag 是数据库的 VIRTUAL 生成列，由 MySQL 自己维护。
     *    绝对不要在这里声明字段 —— MyBatis-Plus 插入时会带上实体里的所有字段，
     *    往生成列里写值会直接报错（生成列不可插入）。
     *    它们的作用是配合唯一键做约束，不是业务数据：
     *      active_flag   = status 属于 PENDING/APPROVED 时为 1，否则 NULL
     *      approved_flag = status 为 APPROVED 时为 1，否则 NULL
     *    唯一索引里 NULL 不参与去重，于是「被驳回后可以重新申请」和「一个物品只能有一条已通过申请」
     *    这两条规则都由数据库兜底，不依赖应用层自觉。
     */

    /** 乐观锁版本号 —— 保护<b>申请级</b>并发（如两人同时审核同一条申请），与 item.version 职责分开 */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
