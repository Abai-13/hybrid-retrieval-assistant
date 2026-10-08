package com.lostfound.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.dto.ClaimApplyRequest;
import com.lostfound.dto.ClaimCompleteResult;
import com.lostfound.dto.ClaimDetailView;
import com.lostfound.dto.ClaimPageQuery;
import com.lostfound.dto.MyClaimView;
import com.lostfound.dto.ReceivedClaimView;
import com.lostfound.entity.ClaimApplication;

/**
 * 认领申请 —— 提交与查询。
 * <p>
 * 审核（通过/驳回）、撤回、确认归还属于下一个批次，见 CLAUDE.md 的认领模块约定。
 */
public interface ClaimService {

    /**
     * 提交认领申请。
     * <p>成功时物品会被推进到「认领中」，并留下一条流转日志 —— 三件事在同一事务里。
     *
     * @param applicantId 申请人（从 JWT 解析，不接受前端传入）
     * @return 新建的申请，含自增 id
     */
    ClaimApplication apply(Long applicantId, ClaimApplyRequest request);

    /** 我发起的申请（申请人视角，只能看到自己的） */
    Page<MyClaimView> pageByApplicant(Long applicantId, ClaimPageQuery query);

    /** 我发布的物品收到的申请（发布者视角，含申请人提交的特征证明） */
    Page<ReceivedClaimView> pageByOwner(Long ownerId, ClaimPageQuery query);

    /**
     * 审核通过一条认领申请：{@code PENDING → APPROVED}。
     *
     * <p>⚠️ <b>通过审核不等于物品已经归还</b> —— 这一步<b>不动物品状态</b>，
     * 物品仍然停在「认领中」。真正的归还由申请人确认（{@code APPROVED → COMPLETED}）时才发生。
     * 把这两件事分开，是因为"审核通过"和"东西真的交出去了"之间
     * 还有线下交接这段现实时间，中间可能联系不上、可能反悔。
     *
     * @param ownerId 必须是该物品的发布者
     */
    void approve(Long claimId, Long ownerId);

    /**
     * 驳回一条认领申请：{@code PENDING → REJECTED}。
     *
     * <p>驳回后如果这条物品上<b>再没有任何活跃申请</b>，物品会退回「待认领」——
     * 否则它会永远停在「认领中」，下一个失主以为有人在走流程，其实早就没人了。
     *
     * @param reason 驳回理由，必填
     */
    void reject(Long claimId, Long ownerId, String reason);

    /**
     * 确认收到物品：{@code APPROVED → COMPLETED}，并把物品从「认领中」置为「已认领」。
     *
     * <p>这一步才是「东西真的回到失主手里了」，所以它比审核通过更重 ——
     * 同一个事务里还要把该物品下<b>其他还挂着的 PENDING 申请自动关闭</b>，
     * 否则它们会一直显示「待审核」，而东西早就没了。
     *
     * <p><b>幂等</b>：如果申请已经是 COMPLETED 且操作人正是申请人本人，直接返回成功、不重复执行。
     * 这是最容易发生的重复点击，回一句"状态不允许"对用户毫无意义。
     *
     * @param applicantId 必须是该申请的申请人（收到物品的一方）
     */
    ClaimCompleteResult complete(Long claimId, Long applicantId);

    /**
     * 申请人撤回一条<b>还没被审核</b>的申请：{@code PENDING → CANCELLED}。
     *
     * <p>和驳回一样，撤回后如果该物品上再没有活跃申请，物品要退回「待认领」。
     * 已经通过审核的申请不能撤回 —— 那一步意味着双方已经接上头了，要反悔得线下说，
     * 不能靠一个按钮让对方的物品状态无声无息地变回去。
     */
    void cancel(Long claimId, Long applicantId);

    /**
     * 申请详情，含完整流转时间线。
     * <p>只有<b>申请人本人</b>和<b>物品发布者</b>能看 —— 特征证明是验真凭据，
     * 给第三方看了就等于公开答案。
     */
    ClaimDetailView getDetail(Long claimId, Long currentUserId);

    /**
     * 当前用户是否有权看到该物品的联系方式。
     * <p>放行两种人：<b>物品发布者本人</b>、以及<b>已通过（或已完成）审核的申请人</b>。
     * 其他所有人（游客、审核中的申请人、被驳回的人）都看不到 ——
     * 联系方式一旦公开，失主直接打电话就行了，整个认领流程就没有存在意义。
     *
     * @param currentUserId 未登录传 null，直接返回 false
     */
    boolean canSeeContact(Long itemId, Long currentUserId);

    /**
     * 当前用户在某物品上的<b>活跃</b>申请状态（PENDING / APPROVED），没有则返回 null。
     * <p>详情页用它来显示「审核中」，而不是继续给一个「我要认领」按钮。
     * <p>不需要处理"多条"的情况：{@code uk_item_applicant_active} 唯一键保证
     * 同一人对同一物品最多只有一条活跃申请。
     */
    String activeClaimStatus(Long itemId, Long currentUserId);
}
