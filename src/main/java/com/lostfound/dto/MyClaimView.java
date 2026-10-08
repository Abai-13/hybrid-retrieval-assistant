package com.lostfound.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我发起的认领申请」列表项 —— <b>申请人视角</b>。
 *
 * <p>物品的标题/分类/地点在这里被摊平进来，是为了让前端一次请求就能渲染列表，
 * 不用拿着 itemId 再去逐个查物品（N+1）。
 *
 * <p>注意这里<b>没有 applicantId、也没有别人的任何信息</b> —— 申请人只看得到自己这一条。
 * 这不是巧合，是刻意的字段裁剪：DTO 里不存在的字段，接口就不可能泄露出去。
 */
@Data
public class MyClaimView {

    /** 申请ID */
    private Long claimId;

    /** 申请状态 */
    private String status;

    /** 我提交的特征证明（自己的，可以看） */
    private String proofText;

    /** 被驳回时的理由 */
    private String rejectReason;

    /** 申请时间 */
    private LocalDateTime createdAt;

    /** 审核时间 */
    private LocalDateTime reviewedAt;

    /** 完成归还时间 */
    private LocalDateTime completedAt;

    // ---- 物品摘要 ----

    private Long itemId;

    private String itemTitle;

    /** LOST / FOUND */
    private String itemType;

    private String itemCategory;

    private String itemLocation;

    private String itemImageUrl;

    /** UNCLAIMED / CLAIMING / CLAIMED —— 让申请人能看出物品当前走到哪一步了 */
    private String itemStatus;
}
