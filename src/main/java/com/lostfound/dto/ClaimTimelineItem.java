package com.lostfound.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 认领流转时间线上的一条。
 * <p>
 * 直接由 {@code claim_log} 的一行映射而来 —— 时间线不是另存一份数据，
 * 而是把那张只追加的流水表按顺序读出来。
 *
 * <p>这里<b>不带操作人昵称</b>，只给 {@code operatorId} 和 {@code operatorRole}：
 * 前端拿到当前登录用户 id 就能判断"这条是不是我自己做的"，
 * 而角色已经足够说明"是申请人、发布者、还是系统自动执行的"，
 * 不必为此再多查一次用户表。
 */
@Data
@AllArgsConstructor
public class ClaimTimelineItem {

    /** 变更前状态；首次提交为 null */
    private String fromStatus;

    /** 变更后状态 */
    private String toStatus;

    /** 操作人ID */
    private Long operatorId;

    /** APPLICANT / OWNER / SYSTEM */
    private String operatorRole;

    /** 备注，如驳回理由、自动关闭理由 */
    private String remark;

    /** 操作时间 */
    private LocalDateTime createdAt;
}
