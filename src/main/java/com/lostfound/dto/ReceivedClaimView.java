package com.lostfound.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我发布的物品收到的申请」列表项 —— <b>发布者（审核人）视角</b>。
 *
 * <p>和 {@link MyClaimView} 的关键区别：这里带着 {@code proofText} / {@code proofImage}，
 * 也就是<b>别人提交的特征证明</b>。
 *
 * <p>⚠️ 正因为带着证明，这个视图的访问范围必须死死限制在「物品发布者本人」——
 * 特征证明是验真凭据，泄露给第三方就等于公开了标准答案，任何人都能照抄着去冒领。
 * 同理，申请人之间的证明也必须互相不可见。
 *
 * <p>另外这里<b>不包含申请人的账号手机号 / 用户名</b>，只有他自己在申请时填的
 * {@code contact} —— 那是他为"本次认领"主动提供的联系方式，给物品发布者是合理的；
 * 账号级信息则是另一回事，不在本视图的范围内。
 */
@Data
public class ReceivedClaimView {

    /** 申请ID */
    private Long claimId;

    /** 申请状态 */
    private String status;

    /** 申请时间 */
    private LocalDateTime createdAt;

    private LocalDateTime reviewedAt;

    private LocalDateTime completedAt;

    /** 驳回理由（如果已驳回） */
    private String rejectReason;

    // ---- 申请人 ----

    private Long applicantId;

    private String applicantNickname;

    /** 申请人填的特征证明 */
    private String proofText;

    /** 证明图片 */
    private String proofImage;

    /** 申请人为本次认领留的联系方式 */
    private String contact;

    // ---- 物品摘要 ----

    private Long itemId;

    private String itemTitle;

    private String itemCategory;

    private String itemImageUrl;

    private String itemStatus;
}
