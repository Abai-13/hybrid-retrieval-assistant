package com.lostfound.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 认领状态流转日志 —— 记录「谁、在什么时间、把状态从什么改成了什么」。
 *
 * <p><b>为什么需要它</b>：{@link ClaimApplication} 只保存<b>当前状态</b>。
 * 一条被驳回的申请事后只能看到 {@code REJECTED}，看不到是谁、什么时候、为什么驳回的。
 * 而认领流程的价值恰恰在「过程可信」—— 日志表是唯一能证明过程发生过的证据。
 *
 * <p><b>纪律：本表只追加，永不 UPDATE、永不 DELETE。</b>
 * 一旦允许改历史，它就失去了作为证据的意义。
 */
@Data
@TableName("claim_log")
public class ClaimLog {

    /** 操作人角色 —— 申请人 */
    public static final String ROLE_APPLICANT = "APPLICANT";
    /** 操作人角色 —— 物品发布者（审核人） */
    public static final String ROLE_OWNER = "OWNER";
    /** 操作人角色 —— 系统（如超时自动关闭，本期未实现，先占位） */
    public static final String ROLE_SYSTEM = "SYSTEM";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属申请ID（不设 item_id 冗余 —— 时间线永远从一条申请出发，没有"按物品反查日志"的场景） */
    private Long claimId;

    /** 变更前状态；首次提交时为 null */
    private String fromStatus;

    /** 变更后状态 */
    private String toStatus;

    /** 操作人ID */
    private Long operatorId;

    /** APPLICANT / OWNER / SYSTEM */
    private String operatorRole;

    /** 备注，如驳回理由 */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
