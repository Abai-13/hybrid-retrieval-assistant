package com.lostfound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.common.BusinessException;
import com.lostfound.common.PageLimits;
import com.lostfound.common.ResultCode;
import com.lostfound.dto.ClaimApplyRequest;
import com.lostfound.dto.ClaimCompleteResult;
import com.lostfound.dto.ClaimDetailView;
import com.lostfound.dto.ClaimPageQuery;
import com.lostfound.dto.ClaimTimelineItem;
import com.lostfound.dto.MyClaimView;
import com.lostfound.dto.ReceivedClaimView;
import com.lostfound.entity.ClaimApplication;
import com.lostfound.entity.ClaimLog;
import com.lostfound.entity.Item;
import com.lostfound.mapper.ClaimApplicationMapper;
import com.lostfound.mapper.ClaimLogMapper;
import com.lostfound.service.ClaimService;
import com.lostfound.service.ItemService;
import com.lostfound.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认领流程 —— 状态机、流转日志与申请提交/查询。
 *
 * <p><b>为什么先把状态机写成一张显式的转换表</b>：认领流程的正确性几乎全部押在
 * 「状态只能按规则变」这一条上。写成表而不是散在各方法里的 if，好处是规则集中、
 * 可以被单元测试穷举、新增状态时只改一处。
 *
 * <p><b>核心纪律</b>：任何状态变更都必须经过 {@link #assertTransition} 校验，
 * 并且必须写入一条 {@link ClaimLog} —— 少了日志，状态就变成"凭空出现"的，流程无法复盘。
 *
 * <p>本批次（P1）只做「提交申请 + 两种列表查询」，审核/驳回/撤回/确认归还属于下一批次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimServiceImpl implements ClaimService {

    /**
     * 申请状态机：当前状态 → 允许变成的状态。
     *
     * <pre>
     * PENDING ──通过──> APPROVED ──申请人确认收到──> COMPLETED
     *    │
     *    ├──驳回──> REJECTED
     *    └──撤回──> CANCELLED
     * </pre>
     *
     * <p>REJECTED / CANCELLED / COMPLETED 是终态，<b>故意不出现在这张表里</b> ——
     * 从终态出发的任何转换都会因为查不到而失败，不需要额外维护一份"终态清单"。
     *
     * <p>被驳回或撤回后可以<b>重新申请</b>，但那是新的一条记录，不是把旧记录改回来。
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            ClaimApplication.STATUS_PENDING, Set.of(
                    ClaimApplication.STATUS_APPROVED,
                    ClaimApplication.STATUS_REJECTED,
                    ClaimApplication.STATUS_CANCELLED),
            ClaimApplication.STATUS_APPROVED, Set.of(
                    ClaimApplication.STATUS_COMPLETED));

    /** 合法状态全集 —— 用于挡住外部传进来的任意字符串 */
    private static final Set<String> ALL_STATUSES = Set.of(
            ClaimApplication.STATUS_PENDING,
            ClaimApplication.STATUS_APPROVED,
            ClaimApplication.STATUS_REJECTED,
            ClaimApplication.STATUS_CANCELLED,
            ClaimApplication.STATUS_COMPLETED);

    /** 状态的中文名 —— 只用于把异常信息写成人话，不参与任何逻辑判断 */
    private static final Map<String, String> STATUS_LABELS = Map.of(
            ClaimApplication.STATUS_PENDING, "待审核",
            ClaimApplication.STATUS_APPROVED, "已通过",
            ClaimApplication.STATUS_REJECTED, "已驳回",
            ClaimApplication.STATUS_CANCELLED, "已撤回",
            ClaimApplication.STATUS_COMPLETED, "已完成");

    /** 重复申请的提示语 —— Service 预检和数据库唯一键兜底共用同一句，避免两处措辞不一致 */
    private static final String DUPLICATE_APPLY_MSG = "你已提交过该物品的认领申请，请等待审核";

    /**
     * 物品归还后，其他申请被自动关闭时写入的理由。
     * <p>它不是"驳回"——不给理由会让人以为自己的申请被否了，而这只是物品已经没了。
     */
    private static final String AUTO_CLOSE_REASON = "该物品已完成归还，其他申请自动关闭";

    private final ClaimApplicationMapper claimApplicationMapper;
    private final ClaimLogMapper claimLogMapper;
    private final ItemService itemService;
    private final UserService userService;

    // ===================== 提交申请 =====================

    /**
     * {@inheritDoc}
     * <p>
     * <b>事务边界</b>：插申请 + 翻物品状态 + 写日志，三件事必须同生共死。
     * 只做一半的后果很具体 —— 比如申请插进去了但物品还停在 UNCLAIMED，
     * 别人就会以为没人申请，继续重复提交。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClaimApplication apply(Long applicantId, ClaimApplyRequest request) {
        Long itemId = request.getItemId();

        // 这里读的是带缓存的详情。物品的身份信息（发布者、类型）永不变，可以放心用缓存；
        // 「能不能申请」所依赖的状态是否最新，由后面 changeStatusByClaim 的 CAS 兜底。
        Item item = itemService.getById(itemId);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "物品不存在");
        }
        // 只有招领能被认领 —— 寻物启事是「我在找东西」，没有可被认领的标的物
        if (!Item.TYPE_FOUND.equals(item.getType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "只有招领信息可以申请认领");
        }
        // 自己捡到的东西不用自己认领
        if (item.getUserId().equals(applicantId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能认领自己发布的物品");
        }
        // 已经归还完毕的物品不再接受申请
        if (Item.STATUS_CLAIMED.equals(item.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "该物品已被认领");
        }
        // 同一人对同一物品只能有一条活跃申请。
        // 被驳回/撤回（终态）之后可以重新申请，所以这里只筛活跃状态。
        Long activeCount = claimApplicationMapper.selectCount(
                new LambdaQueryWrapper<ClaimApplication>()
                        .eq(ClaimApplication::getItemId, itemId)
                        .eq(ClaimApplication::getApplicantId, applicantId)
                        .in(ClaimApplication::getStatus,
                                ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_APPROVED));
        if (activeCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT, DUPLICATE_APPLY_MSG);
        }

        ClaimApplication claim = new ClaimApplication();
        claim.setItemId(itemId);
        claim.setApplicantId(applicantId);
        // owner_id 冗余自 item.user_id —— 前提是物品不支持转让，见实体上的说明
        claim.setOwnerId(item.getUserId());
        claim.setProofText(request.getProofText());
        claim.setProofImage(request.getProofImage());
        claim.setContact(request.getContact());
        claim.setStatus(ClaimApplication.STATUS_PENDING);
        try {
            claimApplicationMapper.insert(claim);
        } catch (DuplicateKeyException e) {
            // 上面的 selectCount 只是"友好提示"（先查再插本身不是原子的）。
            // 真正兜底的是数据库的唯一键 uk_item_applicant_active：
            // 两个请求同时通过预检时，第二个会在这里被撞掉，转成同样的提示而不是 500。
            log.warn("并发重复提交认领申请被唯一键挡下: itemId={}, applicantId={}", itemId, applicantId);
            throw new BusinessException(ResultCode.CONFLICT, DUPLICATE_APPLY_MSG);
        }

        // 物品推进到「认领中」。已在 CLAIMING 时该方法幂等返回；
        // 若状态在这期间被别人改成了 CLAIMED，它会抛 409 让整个事务回滚。
        itemService.changeStatusByClaim(itemId, Item.STATUS_UNCLAIMED, Item.STATUS_CLAIMING);

        // 首次提交：没有"变更前状态"，记为 null
        recordLog(claim.getId(), null, ClaimApplication.STATUS_PENDING,
                applicantId, ClaimLog.ROLE_APPLICANT, null);

        log.info("认领申请提交: claimId={}, itemId={}, applicantId={}",
                claim.getId(), itemId, applicantId);
        return claim;
    }

    // ===================== 查询 =====================

    @Override
    public Page<MyClaimView> pageByApplicant(Long applicantId, ClaimPageQuery query) {
        Page<ClaimApplication> page = selectPage(applicantScope(applicantId, query), query);

        List<Long> itemIds = page.getRecords().stream()
                .map(ClaimApplication::getItemId).distinct().toList();
        Map<Long, Item> itemMap = itemService.mapByIds(itemIds);

        Page<MyClaimView> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(claim -> toMyClaimView(claim, itemMap.get(claim.getItemId())))
                .toList());
        return result;
    }

    @Override
    public Page<ReceivedClaimView> pageByOwner(Long ownerId, ClaimPageQuery query) {
        Page<ClaimApplication> page = selectPage(ownerScope(ownerId, query), query);

        List<Long> itemIds = page.getRecords().stream()
                .map(ClaimApplication::getItemId).distinct().toList();
        List<Long> applicantIds = page.getRecords().stream()
                .map(ClaimApplication::getApplicantId).distinct().toList();

        Map<Long, Item> itemMap = itemService.mapByIds(itemIds);
        Map<Long, String> nicknameMap = userService.mapNicknamesByIds(applicantIds);

        Page<ReceivedClaimView> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(claim -> toReceivedClaimView(claim,
                        itemMap.get(claim.getItemId()),
                        nicknameMap.get(claim.getApplicantId())))
                .toList());
        return result;
    }

    // ===================== 审核 =====================

    /**
     * 审核通过。
     *
     * <p><b>注意这里完全没有碰 item</b>：通过审核只是「认定这个人说的是对的」，
     * 东西还在发布者手上。物品从「认领中」变「已认领」要等申请人确认收到，那是下一批的事。
     * 把这两步合并会造出一个假象 —— 审核一通过，全站就显示「已认领」，
     * 而实际上双方可能还没见面。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long claimId, Long ownerId) {
        ClaimApplication claim = loadForReview(claimId, ownerId);

        // 一个物品最多只能有一条「已通过」的申请 —— 东西只有一件，不可能通过两个人。
        // 数据库上有唯一键 uk_item_approved 兜底，这里先查一次是为了给一句人话，
        // 而不是让用户看到一个唯一键冲突。
        Long alreadyApproved = claimApplicationMapper.selectCount(
                new LambdaQueryWrapper<ClaimApplication>()
                        .eq(ClaimApplication::getItemId, claim.getItemId())
                        .ne(ClaimApplication::getId, claimId)
                        .eq(ClaimApplication::getStatus, ClaimApplication.STATUS_APPROVED));
        if (alreadyApproved > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该物品已有其它申请通过审核");
        }

        transitionClaim(claim, ClaimApplication.STATUS_APPROVED, ownerId, ClaimLog.ROLE_OWNER, null);
    }

    /** 驳回。驳回理由写进申请本身（给申请人看），同时写进流转日志（留痕） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long claimId, Long ownerId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请填写驳回理由，申请人需要知道原因");
        }
        ClaimApplication claim = loadForReview(claimId, ownerId);
        transitionClaim(claim, ClaimApplication.STATUS_REJECTED, ownerId, ClaimLog.ROLE_OWNER, reason);
        revertItemIfNoActiveClaim(claim.getItemId());
    }

    /**
     * {@inheritDoc}
     * <p><b>事务边界</b>：申请转 CANCELLED 和（可能的）物品状态退回必须一起成功或一起失败，
     * 否则会出现"申请撤回了但物品还挂着认领中"的中间态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long claimId, Long applicantId) {
        ClaimApplication claim = claimApplicationMapper.selectById(claimId);
        if (claim == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "认领申请不存在");
        }
        if (applicantId == null || !claim.getApplicantId().equals(applicantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能撤回自己提交的认领申请");
        }
        // 状态机只允许 PENDING → CANCELLED；已通过的申请撤回不了
        transitionClaim(claim, ClaimApplication.STATUS_CANCELLED,
                applicantId, ClaimLog.ROLE_APPLICANT, null);
        revertItemIfNoActiveClaim(claim.getItemId());
    }

    @Override
    public ClaimDetailView getDetail(Long claimId, Long currentUserId) {
        ClaimApplication claim = claimApplicationMapper.selectById(claimId);
        if (claim == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "认领申请不存在");
        }
        // 访问范围只有两个当事人。特征证明是验真凭据，第三方看到就等于公开答案。
        boolean isApplicant = claim.getApplicantId().equals(currentUserId);
        boolean isOwner = claim.getOwnerId().equals(currentUserId);
        if (!isApplicant && !isOwner) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只有申请人和物品发布者可以查看这条申请");
        }

        ClaimDetailView view = new ClaimDetailView();
        view.setClaimId(claim.getId());
        view.setStatus(claim.getStatus());
        view.setProofText(claim.getProofText());
        view.setProofImage(claim.getProofImage());
        view.setContact(claim.getContact());
        view.setRejectReason(claim.getRejectReason());
        view.setCreatedAt(claim.getCreatedAt());
        view.setReviewedAt(claim.getReviewedAt());
        view.setCompletedAt(claim.getCompletedAt());
        view.setApplicantId(claim.getApplicantId());
        view.setOwnerId(claim.getOwnerId());
        view.setItemId(claim.getItemId());
        view.setApplicantNickname(userService.getNicknameById(claim.getApplicantId()));

        Item item = itemService.getById(claim.getItemId());
        if (item != null) {
            view.setItemTitle(item.getTitle());
            view.setItemCategory(item.getCategory());
            view.setItemLocation(item.getLocation());
            view.setItemImageUrl(item.getImageUrl());
            view.setItemStatus(item.getStatus());
        }

        // 时间线就是把 claim_log 按发生顺序读出来 —— 不另存一份数据，
        // 因为那张表本来就是"只追加、不修改"的流水，它本身就是最可信的过程记录
        List<ClaimLog> logs = claimLogMapper.selectList(
                new LambdaQueryWrapper<ClaimLog>()
                        .eq(ClaimLog::getClaimId, claimId)
                        .orderByAsc(ClaimLog::getId));
        view.setTimeline(logs.stream().map(ClaimServiceImpl::toTimelineItem).toList());
        return view;
    }

    private static ClaimTimelineItem toTimelineItem(ClaimLog logRecord) {
        return new ClaimTimelineItem(logRecord.getFromStatus(), logRecord.getToStatus(),
                logRecord.getOperatorId(), logRecord.getOperatorRole(),
                logRecord.getRemark(), logRecord.getCreatedAt());
    }

    /**
     * 一条申请关闭（驳回 / 撤回 / 完成）之后，检查这条物品上还有没有活跃申请；
     * 没有了就把物品从「认领中」退回「待认领」。
     *
     * <p>不处理的话物品会永远停在「认领中」—— 后面的失主以为有人在走流程，直接放弃申请。
     * <p>{@code revertToUnclaimedIfClaiming} 是幂等的，所以这里判断粗略一点也不会出错。
     */
    private void revertItemIfNoActiveClaim(Long itemId) {
        Long remaining = claimApplicationMapper.selectCount(
                new LambdaQueryWrapper<ClaimApplication>()
                        .eq(ClaimApplication::getItemId, itemId)
                        .in(ClaimApplication::getStatus,
                                ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_APPROVED));
        if (remaining == 0) {
            itemService.revertToUnclaimedIfClaiming(itemId);
        }
    }

    // ===================== 详情与时间线 =====================

    // ===================== 确认收到（完成归还） =====================

    /**
     * {@inheritDoc}
     * <p>
     * <b>事务边界</b>：申请转 COMPLETED、物品转 CLAIMED、自动关闭其他申请，
     * 三件事描述的是同一个事实（东西交还完了），必须同生共死。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClaimCompleteResult complete(Long claimId, Long applicantId) {
        ClaimApplication claim = claimApplicationMapper.selectById(claimId);
        if (claim == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "认领申请不存在");
        }
        if (applicantId == null || !claim.getApplicantId().equals(applicantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只有申请人本人可以确认收到物品");
        }

        // 幂等：重复点击 / 网络重试时申请已经是 COMPLETED，当作成功返回，不再执行任何写操作。
        // 这里不能用状态机去判 —— 状态机只会回一句"状态不允许"，而用户的心智是"我已经确认过了"。
        if (ClaimApplication.STATUS_COMPLETED.equals(claim.getStatus())) {
            return new ClaimCompleteResult(claimId, ClaimApplication.STATUS_COMPLETED,
                    Item.STATUS_CLAIMED, 0);
        }

        // 其余状态一律交给状态机：只有 APPROVED → COMPLETED 是合法的
        transitionClaim(claim, ClaimApplication.STATUS_COMPLETED,
                applicantId, ClaimLog.ROLE_APPLICANT, null);

        // 物品「认领中」→「已认领」。
        // CAS 失败会抛 409 让整个事务回滚 —— 那种情况说明物品状态被人手工改过，
        // 此时宁可报错，也不要写出一份"申请已完成但物品还没认领"的自相矛盾数据。
        itemService.changeStatusByClaim(claim.getItemId(), Item.STATUS_CLAIMING, Item.STATUS_CLAIMED);

        // ⚠️ 必须在上面两步之后再查：那时当前申请已经是 COMPLETED，
        // 不会出现在"该物品下还挂着的 PENDING"里，不需要额外写排除条件
        int closedOthers = closeOtherPendingClaims(claim.getItemId(), applicantId);

        log.info("确认收到物品: claimId={}, itemId={}, 自动关闭其他申请 {} 条",
                claimId, claim.getItemId(), closedOthers);
        return new ClaimCompleteResult(claimId, ClaimApplication.STATUS_COMPLETED,
                Item.STATUS_CLAIMED, closedOthers);
    }

    /**
     * 把某物品下还挂着的其他 PENDING 申请统一关闭为 REJECTED。
     *
     * @param triggeredBy 触发这次流程的用户（即确认收到的申请人）。
     *                    系统动作没有"操作人"，但这次流转确实是由他发起的，
     *                    所以日志里的 operator_id 记他，再用 operator_role=SYSTEM 表明
     *                    "这是系统代为执行的，不是他手动去驳别人的申请"。
     * @return 实际关闭成功的条数
     */
    private int closeOtherPendingClaims(Long itemId, Long triggeredBy) {
        List<ClaimApplication> pendingList = claimApplicationMapper.selectList(
                new LambdaQueryWrapper<ClaimApplication>()
                        .eq(ClaimApplication::getItemId, itemId)
                        .eq(ClaimApplication::getStatus, ClaimApplication.STATUS_PENDING));
        if (pendingList.isEmpty()) {
            return 0;
        }

        int closed = 0;
        for (ClaimApplication other : pendingList) {
            other.setStatus(ClaimApplication.STATUS_REJECTED);
            other.setRejectReason(AUTO_CLOSE_REASON);
            other.setReviewedAt(LocalDateTime.now());

            // 走 @Version：这条申请可能刚好被发布者手动处理过，甚至刚被通过
            // （当前申请转成 COMPLETED 之后，uk_item_approved 就空出来了，别人确实可能在这中间通过另一条）。
            // 影响行数为 0 就跳过 —— 别人已经做出决定，我们不能覆盖。
            // 也绝不能因为一条清理动作失败，就让失主确认不了收货。
            if (claimApplicationMapper.updateById(other) == 0) {
                log.warn("自动关闭时该申请已被他人处理，跳过: claimId={}", other.getId());
                continue;
            }
            recordLog(other.getId(), ClaimApplication.STATUS_PENDING,
                    ClaimApplication.STATUS_REJECTED, triggeredBy, ClaimLog.ROLE_SYSTEM,
                    AUTO_CLOSE_REASON);
            closed++;
        }
        return closed;
    }

    /**
     * 取出待审核的申请并校验审核权限。
     * <p>越权检查在 Service 层 —— 拦截器只回答「你是谁」，这里才回答「这条申请归不归你审」。
     */
    private ClaimApplication loadForReview(Long claimId, Long ownerId) {
        ClaimApplication claim = claimApplicationMapper.selectById(claimId);
        if (claim == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "认领申请不存在");
        }
        if (ownerId == null || !claim.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能审核自己发布的物品的认领申请");
        }
        return claim;
    }

    /**
     * 状态流转的唯一出口：校验 → 改状态 → 乐观锁落库 → 写日志。
     *
     * <p>所有审核动作都走这里，是为了保证「状态变了就一定有一条日志」，
     * 不会有哪个分支忘了留痕。
     *
     * <p><b>并发冲突的处理</b>：两个人（或者同一个人的两次点击）同时审核同一条申请时，
     * 两边读到的 version 相同，先提交的那个改成功，后一个 {@code updateById} 影响行数为 0 ——
     * 这时抛 409 让用户刷新看最新状态，而不是覆盖别人的决定。
     */
    private void transitionClaim(ClaimApplication claim, String targetStatus,
                                 Long operatorId, String operatorRole, String remark) {
        String fromStatus = claim.getStatus();
        // 状态机校验：只允许 PENDING→APPROVED/REJECTED、APPROVED→COMPLETED
        assertTransition(fromStatus, targetStatus);

        claim.setStatus(targetStatus);
        if (ClaimApplication.STATUS_REJECTED.equals(targetStatus)) {
            claim.setRejectReason(remark);
        }
        if (ClaimApplication.STATUS_APPROVED.equals(targetStatus)
                || ClaimApplication.STATUS_REJECTED.equals(targetStatus)) {
            claim.setReviewedAt(LocalDateTime.now());
        }
        if (ClaimApplication.STATUS_COMPLETED.equals(targetStatus)) {
            claim.setCompletedAt(LocalDateTime.now());
        }

        int rows;
        try {
            // @Version 乐观锁：version 被别人改过的话这里会返回 0
            rows = claimApplicationMapper.updateById(claim);
        } catch (DuplicateKeyException e) {
            // 上面的预检查不是原子的，并发下真正兜底的是数据库唯一键 uk_item_approved
            // （同一物品不能有两条「已通过」）。
            // ⚠️ 必须在这里翻译成业务异常：让它漏到全局兜底就会变成一个 500，
            //    而「这个物品已经有人通过了」明明是客户端该知道的事，不是服务器故障。
            log.warn("状态流转被数据库唯一键挡下: claimId={}, target={}", claim.getId(), targetStatus);
            throw new BusinessException(ResultCode.CONFLICT, "该物品已有其它申请通过审核");
        }
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该申请已被处理，请刷新后重试");
        }
        recordLog(claim.getId(), fromStatus, targetStatus, operatorId, operatorRole, remark);
    }

    // ===================== 详情页要用的两个小判断 =====================

    @Override
    public boolean canSeeContact(Long itemId, Long currentUserId) {
        if (currentUserId == null) {
            return false;
        }
        // COMPLETED 也算：东西已经交还了，申请人一直知道联系方式，没必要在完成那一刻收回去
        Long count = claimApplicationMapper.selectCount(
                new LambdaQueryWrapper<ClaimApplication>()
                        .eq(ClaimApplication::getItemId, itemId)
                        .eq(ClaimApplication::getApplicantId, currentUserId)
                        .in(ClaimApplication::getStatus,
                                ClaimApplication.STATUS_APPROVED, ClaimApplication.STATUS_COMPLETED));
        return count > 0;
    }

    @Override
    public String activeClaimStatus(Long itemId, Long currentUserId) {
        if (currentUserId == null) {
            return null;
        }
        ClaimApplication claim = claimApplicationMapper.selectOne(
                new LambdaQueryWrapper<ClaimApplication>()
                        .eq(ClaimApplication::getItemId, itemId)
                        .eq(ClaimApplication::getApplicantId, currentUserId)
                        .in(ClaimApplication::getStatus,
                                ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_APPROVED));
        return claim != null ? claim.getStatus() : null;
    }

    // ===================== 查询条件（权限就落在这里） =====================

    /**
     * 申请人视角的查询条件 —— 强制 {@code applicant_id = 当前用户}。
     * <p>包级可见是<b>故意的</b>：权限范围必须被单元测试钉在 SQL 上，
     * 否则「只能看自己的」这句话就只是注释里的一个愿望。
     */
    LambdaQueryWrapper<ClaimApplication> applicantScope(Long applicantId, ClaimPageQuery query) {
        return buildWrapper(ClaimApplication::getApplicantId, applicantId, query);
    }

    /** 发布者视角的查询条件 —— 强制 {@code owner_id = 当前用户}。理由同上 */
    LambdaQueryWrapper<ClaimApplication> ownerScope(Long ownerId, ClaimPageQuery query) {
        return buildWrapper(ClaimApplication::getOwnerId, ownerId, query);
    }

    /**
     * 构建分页查询条件。
     * <p>⚠️ 顺序有讲究：<b>范围条件必须最先加</b>，排在 order by 之后的条件在
     * MyBatis-Plus 里会被拼到 ORDER BY 后面，直接是条废 SQL。
     */
    private LambdaQueryWrapper<ClaimApplication> buildWrapper(SFunction<ClaimApplication, ?> scopeColumn,
                                                              Long scopeValue,
                                                              ClaimPageQuery query) {
        LambdaQueryWrapper<ClaimApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(scopeColumn, scopeValue);

        if (StringUtils.hasText(query.getStatus())) {
            // 状态取值必须合法 —— 否则就是拿任意字符串去查库
            assertValidStatus(query.getStatus());
            wrapper.eq(ClaimApplication::getStatus, query.getStatus());
        }
        return wrapper.orderByDesc(ClaimApplication::getCreatedAt);
    }

    private Page<ClaimApplication> selectPage(LambdaQueryWrapper<ClaimApplication> wrapper,
                                              ClaimPageQuery query) {
        return claimApplicationMapper.selectPage(
                new Page<>(PageLimits.page(query.getPage()), PageLimits.size(query.getSize())),
                wrapper);
    }

    // ===================== 视图装配 =====================

    /** 包级可见，便于直接单测字段映射（不需要连带 mock 分页查询） */
    MyClaimView toMyClaimView(ClaimApplication claim, Item item) {
        MyClaimView view = new MyClaimView();
        view.setClaimId(claim.getId());
        view.setItemId(claim.getItemId());
        view.setStatus(claim.getStatus());
        view.setProofText(claim.getProofText());
        view.setRejectReason(claim.getRejectReason());
        view.setCreatedAt(claim.getCreatedAt());
        view.setReviewedAt(claim.getReviewedAt());
        view.setCompletedAt(claim.getCompletedAt());

        if (item != null) {
            view.setItemTitle(item.getTitle());
            view.setItemType(item.getType());
            view.setItemCategory(item.getCategory());
            view.setItemLocation(item.getLocation());
            view.setItemImageUrl(item.getImageUrl());
            view.setItemStatus(item.getStatus());
        }
        return view;
    }

    /** 包级可见，理由同上 */
    ReceivedClaimView toReceivedClaimView(ClaimApplication claim, Item item, String applicantNickname) {
        ReceivedClaimView view = new ReceivedClaimView();
        view.setClaimId(claim.getId());
        view.setItemId(claim.getItemId());
        view.setStatus(claim.getStatus());
        view.setRejectReason(claim.getRejectReason());
        view.setCreatedAt(claim.getCreatedAt());
        view.setReviewedAt(claim.getReviewedAt());
        view.setCompletedAt(claim.getCompletedAt());
        view.setApplicantId(claim.getApplicantId());
        view.setApplicantNickname(applicantNickname);
        view.setProofText(claim.getProofText());
        view.setProofImage(claim.getProofImage());
        view.setContact(claim.getContact());

        if (item != null) {
            view.setItemTitle(item.getTitle());
            view.setItemCategory(item.getCategory());
            view.setItemImageUrl(item.getImageUrl());
            view.setItemStatus(item.getStatus());
        }
        return view;
    }

    // ===================== 状态机 =====================

    /**
     * 校验状态值本身是否合法（挡外部输入）。
     * <p>现有代码里 {@code item.status} 是<b>任意字符串直接入库</b>，是明确的教训，新流程不重复这个坑。
     */
    static void assertValidStatus(String status) {
        if (!ALL_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "非法的申请状态: " + status);
        }
    }

    /**
     * 校验一次状态转换是否被允许。
     *
     * @param from 变更前的状态（应为刚从数据库读到的最新状态，不是请求里带上来的）
     * @param to   目标状态
     */
    static void assertTransition(String from, String to) {
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "该申请当前为「" + labelOf(from) + "」，不能执行此操作");
        }
    }

    /** 状态的中文名；未知状态原样返回，便于排障时看到原始值 */
    static String labelOf(String status) {
        return STATUS_LABELS.getOrDefault(status, String.valueOf(status));
    }

    // ===================== 流转日志 =====================

    /**
     * 追加一条状态流转日志。
     *
     * <p><b>为什么不 catch 异常</b>：日志与状态变更属于<b>同一次业务动作</b>，
     * 写日志失败就该让整个事务回滚 —— 状态变了但没有日志，等于过程不可信，
     * 这比操作失败更糟。
     *
     * @param fromStatus 变更前状态；首次提交传 null
     * @param remark     备注，如驳回理由
     */
    void recordLog(Long claimId, String fromStatus, String toStatus,
                   Long operatorId, String operatorRole, String remark) {
        ClaimLog logRecord = new ClaimLog();
        logRecord.setClaimId(claimId);
        logRecord.setFromStatus(fromStatus);
        logRecord.setToStatus(toStatus);
        logRecord.setOperatorId(operatorId);
        logRecord.setOperatorRole(operatorRole);
        logRecord.setRemark(remark);
        claimLogMapper.insert(logRecord);

        log.info("认领流转: claimId={}, {} -> {}, operator={}({})",
                claimId, fromStatus, toStatus, operatorId, operatorRole);
    }
}
