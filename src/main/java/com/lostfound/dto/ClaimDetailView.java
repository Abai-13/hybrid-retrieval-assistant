package com.lostfound.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单条认领申请的详情 —— 含完整流转时间线。
 *
 * <p><b>为什么这次只用了一个 DTO，不像列表那样拆成申请人/发布者两份</b>：
 * 一条申请只有一个申请人，能看这条详情的也只有两个当事人（申请人本人、物品发布者），
 * 不存在"第三方看到别人的特征证明"这种横向泄露。列表那边拆两份是因为列表会一次返回多条、
 * 字段里带不带证明会决定整批数据能不能外泄；单条详情没有这个问题，拆开只是徒增两个几乎一样的类。
 * <p>访问范围仍然由 Service 卡死：不是这两个人一律 403。
 *
 * <p>时间线来自 {@code claim_log}（只追加的流水表），按发生顺序排列 ——
 * 「谁在什么时候把状态从什么改成了什么」全都在里面，这是流程唯一可信的过程记录。
 */
@Data
public class ClaimDetailView {

    private Long claimId;

    /** PENDING / APPROVED / REJECTED / CANCELLED / COMPLETED */
    private String status;

    /** 申请人提交的特征证明 */
    private String proofText;

    private String proofImage;

    /** 申请人本次认领留的联系方式 */
    private String contact;

    /** 驳回理由（含系统自动关闭的理由） */
    private String rejectReason;

    private LocalDateTime createdAt;

    private LocalDateTime reviewedAt;

    private LocalDateTime completedAt;

    // ---- 当事人 ----

    private Long applicantId;

    private String applicantNickname;

    private Long ownerId;

    // ---- 物品摘要 ----

    private Long itemId;

    private String itemTitle;

    private String itemCategory;

    private String itemLocation;

    private String itemImageUrl;

    private String itemStatus;

    /** 完整流转过程，按发生顺序 */
    private List<ClaimTimelineItem> timeline;
}
