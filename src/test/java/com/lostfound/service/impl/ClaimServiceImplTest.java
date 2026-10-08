package com.lostfound.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lostfound.common.BusinessException;
import com.lostfound.common.ResultCode;
import com.lostfound.dto.ClaimApplyRequest;
import com.lostfound.dto.ClaimCompleteResult;
import com.lostfound.dto.ClaimDetailView;
import com.lostfound.dto.ClaimPageQuery;
import com.lostfound.dto.MyClaimView;
import com.lostfound.dto.ReceivedClaimView;
import com.lostfound.entity.ClaimApplication;
import com.lostfound.entity.ClaimLog;
import com.lostfound.entity.Item;
import com.lostfound.mapper.ClaimApplicationMapper;
import com.lostfound.mapper.ClaimLogMapper;
import com.lostfound.service.ItemService;
import com.lostfound.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClaimServiceImpl 的状态机与流转日志测试（纯 Mockito）。
 *
 * <p>状态机是整个认领流程的正确性枢纽，所以这里把它<b>穷举</b>掉：
 * 合法的每一条通路、非法的每一种组合、以及终态出发的所有尝试。
 */
@ExtendWith(MockitoExtension.class)
class ClaimServiceImplTest {

    @Mock
    private ClaimApplicationMapper claimApplicationMapper;

    @Mock
    private ClaimLogMapper claimLogMapper;

    @Mock
    private ItemService itemService;

    @Mock
    private UserService userService;

    @InjectMocks
    private ClaimServiceImpl claimService;

    private static final Long ITEM_ID = 1L;
    private static final Long APPLICANT = 100L;
    private static final Long OWNER = 200L;

    /**
     * 检查 LambdaQueryWrapper 拼出来的 SQL 需要 MyBatis-Plus 的 TableInfo 缓存，
     * 纯单元测试里没人初始化它，手动灌一次。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ClaimApplication.class);
    }

    private Item buildItem(String type, String status, Long ownerId) {
        Item item = new Item();
        item.setId(ITEM_ID);
        item.setUserId(ownerId);
        item.setTitle("银色圆头车钥匙");
        item.setType(type);
        item.setCategory("其他");
        item.setLocation("图书馆一楼");
        item.setStatus(status);
        return item;
    }

    private ClaimApplyRequest buildRequest() {
        ClaimApplyRequest request = new ClaimApplyRequest();
        request.setItemId(ITEM_ID);
        request.setProofText("钥匙扣上挂着一只灰色小恐龙挂件，齿痕在背面");
        return request;
    }

    /**
     * 让物品查得到。
     * <p>刻意和 {@link #givenNoActiveClaim()} 分开：Mockito 的严格模式会把"stub 了但没被调用"
     * 判成测试失败，而前置校验失败的用例根本走不到查活跃申请那一步。
     */
    private void givenItem(Item item) {
        when(itemService.getById(ITEM_ID)).thenReturn(item);
    }

    /** 当前用户对该物品没有活跃申请 */
    private void givenNoActiveClaim() {
        when(claimApplicationMapper.selectCount(any())).thenReturn(0L);
    }

    /** 全部 5 个状态 —— 用来和终态做笛卡尔积，穷举"从终态出发"的所有尝试 */
    private static final List<String> ALL_STATUSES = List.of(
            ClaimApplication.STATUS_PENDING,
            ClaimApplication.STATUS_APPROVED,
            ClaimApplication.STATUS_REJECTED,
            ClaimApplication.STATUS_CANCELLED,
            ClaimApplication.STATUS_COMPLETED);

    private static final List<String> TERMINAL_STATUSES = List.of(
            ClaimApplication.STATUS_REJECTED,
            ClaimApplication.STATUS_CANCELLED,
            ClaimApplication.STATUS_COMPLETED);

    private BusinessException assertRejected(String from, String to) {
        return assertThrows(BusinessException.class, () -> ClaimServiceImpl.assertTransition(from, to));
    }

    // ===================== 合法转换 =====================

    @Test
    @DisplayName("状态机：PENDING 可以通过 / 驳回 / 撤回")
    void pending_canGoTo_approved_rejected_cancelled() {
        assertDoesNotThrow(() -> ClaimServiceImpl.assertTransition(
                ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_APPROVED));
        assertDoesNotThrow(() -> ClaimServiceImpl.assertTransition(
                ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_REJECTED));
        assertDoesNotThrow(() -> ClaimServiceImpl.assertTransition(
                ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_CANCELLED));
    }

    @Test
    @DisplayName("状态机：APPROVED 只能走向 COMPLETED")
    void approved_canOnlyGoTo_completed() {
        assertDoesNotThrow(() -> ClaimServiceImpl.assertTransition(
                ClaimApplication.STATUS_APPROVED, ClaimApplication.STATUS_COMPLETED));
    }

    // ===================== 非法转换 =====================

    @Test
    @DisplayName("状态机：PENDING 不能直接完成（必须先通过审核）")
    void pending_cannotJumpTo_completed() {
        BusinessException ex = assertRejected(
                ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_COMPLETED);
        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("状态机：PENDING 不能变回 PENDING 之外的未定义目标")
    void pending_cannotGoTo_itself() {
        assertRejected(ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_PENDING);
    }

    @Test
    @DisplayName("状态机：APPROVED 不能回退到 PENDING，也不能被驳回/撤回")
    void approved_cannotGoBack() {
        assertRejected(ClaimApplication.STATUS_APPROVED, ClaimApplication.STATUS_PENDING);
        assertRejected(ClaimApplication.STATUS_APPROVED, ClaimApplication.STATUS_REJECTED);
        assertRejected(ClaimApplication.STATUS_APPROVED, ClaimApplication.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("状态机：三个终态出发的任何转换都必须被拒绝（穷举）")
    void terminalStatuses_areFinal() {
        for (String from : TERMINAL_STATUSES) {
            for (String to : ALL_STATUSES) {
                BusinessException ex = assertRejected(from, to);
                assertEquals(ResultCode.BAD_REQUEST, ex.getCode(),
                        "终态 " + from + " → " + to + " 应当被拒绝");
            }
        }
    }

    @Test
    @DisplayName("状态机：未知状态出发也一律拒绝（不会因为查不到规则而放行）")
    void unknownStatus_cannotTransition() {
        assertRejected("HACK", ClaimApplication.STATUS_APPROVED);
    }

    // ===================== 状态值校验 =====================

    @Test
    @DisplayName("状态值校验：5 个合法值通过，任意字符串被挡下")
    void assertValidStatus_acceptsOnlyKnownStatuses() {
        for (String status : ALL_STATUSES) {
            assertDoesNotThrow(() -> ClaimServiceImpl.assertValidStatus(status));
        }

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ClaimServiceImpl.assertValidStatus("HACK"));
        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("异常信息用中文状态名，不把 PENDING 这类内部值直接甩给用户")
    void transitionMessage_isHumanReadable() {
        BusinessException ex = assertRejected(
                ClaimApplication.STATUS_APPROVED, ClaimApplication.STATUS_PENDING);

        assertTrue(ex.getMessage().contains("已通过"), "应包含状态中文名: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("APPROVED"), "不应暴露内部枚举值: " + ex.getMessage());
    }

    // ===================== 流转日志 =====================

    @Test
    @DisplayName("流转日志：完整落库 from/to/操作人/角色")
    void recordLog_persistsAllFields() {
        claimService.recordLog(7L, ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_APPROVED,
                100L, ClaimLog.ROLE_OWNER, null);

        ArgumentCaptor<ClaimLog> captor = ArgumentCaptor.forClass(ClaimLog.class);
        verify(claimLogMapper).insert(captor.capture());

        ClaimLog saved = captor.getValue();
        assertEquals(7L, saved.getClaimId().longValue());
        assertEquals(ClaimApplication.STATUS_PENDING, saved.getFromStatus());
        assertEquals(ClaimApplication.STATUS_APPROVED, saved.getToStatus());
        assertEquals(100L, saved.getOperatorId().longValue());
        assertEquals(ClaimLog.ROLE_OWNER, saved.getOperatorRole());
        assertNull(saved.getRemark());
    }

    @Test
    @DisplayName("流转日志：首次提交 fromStatus 为 null，备注可带驳回理由")
    void recordLog_supportsNullFromStatusAndRemark() {
        claimService.recordLog(8L, null, ClaimApplication.STATUS_PENDING,
                200L, ClaimLog.ROLE_APPLICANT, null);
        claimService.recordLog(8L, ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_REJECTED,
                100L, ClaimLog.ROLE_OWNER, "特征描述与实物不符");

        ArgumentCaptor<ClaimLog> captor = ArgumentCaptor.forClass(ClaimLog.class);
        verify(claimLogMapper, times(2)).insert(captor.capture());

        List<ClaimLog> saved = captor.getAllValues();
        assertNull(saved.get(0).getFromStatus(), "首次提交的 fromStatus 应为 null");
        assertEquals(ClaimApplication.STATUS_PENDING, saved.get(0).getToStatus());
        assertEquals("特征描述与实物不符", saved.get(1).getRemark());
    }

    @Test
    @DisplayName("流转日志：写日志失败必须抛出（不能吞掉，否则事务不会回滚）")
    void recordLog_doesNotSwallowException() {
        when(claimLogMapper.insert(any(ClaimLog.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () ->
                claimService.recordLog(9L, ClaimApplication.STATUS_PENDING,
                        ClaimApplication.STATUS_APPROVED, 100L, ClaimLog.ROLE_OWNER, null));
    }

    // ===================== 提交申请：前置校验 =====================

    @Test
    @DisplayName("提交申请：物品不存在 → 404")
    void apply_itemNotFound_shouldThrow404() {
        when(itemService.getById(ITEM_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.apply(APPLICANT, buildRequest()));

        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
        verify(claimApplicationMapper, never()).insert(any(ClaimApplication.class));
    }

    @Test
    @DisplayName("提交申请：寻物启事不能被认领 → 400")
    void apply_lostItem_shouldThrow400() {
        givenItem(buildItem(Item.TYPE_LOST, Item.STATUS_UNCLAIMED, OWNER));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.apply(APPLICANT, buildRequest()));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(claimApplicationMapper, never()).insert(any(ClaimApplication.class));
    }

    @Test
    @DisplayName("提交申请：不能认领自己发布的物品 → 400")
    void apply_ownItem_shouldThrow400() {
        givenItem(buildItem(Item.TYPE_FOUND, Item.STATUS_UNCLAIMED, APPLICANT));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.apply(APPLICANT, buildRequest()));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(claimApplicationMapper, never()).insert(any(ClaimApplication.class));
    }

    @Test
    @DisplayName("提交申请：物品已被认领走 → 409")
    void apply_alreadyClaimedItem_shouldThrow409() {
        givenItem(buildItem(Item.TYPE_FOUND, Item.STATUS_CLAIMED, OWNER));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.apply(APPLICANT, buildRequest()));

        assertEquals(ResultCode.CONFLICT, ex.getCode());
        verify(claimApplicationMapper, never()).insert(any(ClaimApplication.class));
    }

    @Test
    @DisplayName("提交申请：已有活跃申请 → 409（被驳回后可以再申请，活跃的不行）")
    void apply_duplicateActiveClaim_shouldThrow409() {
        when(itemService.getById(ITEM_ID)).thenReturn(buildItem(Item.TYPE_FOUND, Item.STATUS_UNCLAIMED, OWNER));
        when(claimApplicationMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.apply(APPLICANT, buildRequest()));

        assertEquals(ResultCode.CONFLICT, ex.getCode());
        verify(claimApplicationMapper, never()).insert(any(ClaimApplication.class));
    }

    // ===================== 提交申请：正常路径 =====================

    @Test
    @DisplayName("提交申请：插入 PENDING、owner_id 取自物品、物品推进到认领中、留下日志")
    void apply_success_shouldInsertChangeItemAndLog() {
        givenItem(buildItem(Item.TYPE_FOUND, Item.STATUS_UNCLAIMED, OWNER));
        givenNoActiveClaim();
        when(claimApplicationMapper.insert(any(ClaimApplication.class))).thenAnswer(inv -> {
            inv.<ClaimApplication>getArgument(0).setId(555L);
            return 1;
        });

        ClaimApplication created = claimService.apply(APPLICANT, buildRequest());

        // ① 申请本身
        ArgumentCaptor<ClaimApplication> claimCaptor = ArgumentCaptor.forClass(ClaimApplication.class);
        verify(claimApplicationMapper).insert(claimCaptor.capture());
        ClaimApplication saved = claimCaptor.getValue();
        assertEquals(ClaimApplication.STATUS_PENDING, saved.getStatus());
        assertEquals(APPLICANT, saved.getApplicantId());
        assertEquals(OWNER, saved.getOwnerId(), "owner_id 必须取自物品发布者");
        assertEquals(ITEM_ID, saved.getItemId());
        assertEquals(555L, created.getId().longValue());

        // ② 物品状态随流程推进
        verify(itemService).changeStatusByClaim(ITEM_ID, Item.STATUS_UNCLAIMED, Item.STATUS_CLAIMING);

        // ③ 过程留痕：首次提交，fromStatus 为 null
        ArgumentCaptor<ClaimLog> logCaptor = ArgumentCaptor.forClass(ClaimLog.class);
        verify(claimLogMapper).insert(logCaptor.capture());
        ClaimLog logRecord = logCaptor.getValue();
        assertEquals(555L, logRecord.getClaimId().longValue());
        assertNull(logRecord.getFromStatus(), "首次提交没有变更前状态");
        assertEquals(ClaimApplication.STATUS_PENDING, logRecord.getToStatus());
        assertEquals(ClaimLog.ROLE_APPLICANT, logRecord.getOperatorRole());
    }

    @Test
    @DisplayName("提交申请：物品已经是 CLAIMING（前面有人申请过）时仍可申请")
    void apply_itemAlreadyClaiming_shouldStillSucceed() {
        givenItem(buildItem(Item.TYPE_FOUND, Item.STATUS_CLAIMING, OWNER));
        givenNoActiveClaim();

        claimService.apply(APPLICANT, buildRequest());

        verify(claimApplicationMapper).insert(any(ClaimApplication.class));
        // 状态翻转交给 changeStatusByClaim 的幂等分支处理，这里照样要调，缓存才有机会被清
        verify(itemService).changeStatusByClaim(ITEM_ID, Item.STATUS_UNCLAIMED, Item.STATUS_CLAIMING);
    }

    @Test
    @DisplayName("提交申请：并发撞上唯一键 → 转成 409 提示，而不是 500")
    void apply_duplicateKeyRace_shouldThrow409() {
        givenItem(buildItem(Item.TYPE_FOUND, Item.STATUS_UNCLAIMED, OWNER));
        givenNoActiveClaim();
        when(claimApplicationMapper.insert(any(ClaimApplication.class)))
                .thenThrow(new DuplicateKeyException("uk_item_applicant_active"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.apply(APPLICANT, buildRequest()));

        assertEquals(ResultCode.CONFLICT, ex.getCode());
        verify(itemService, never()).changeStatusByClaim(any(), any(), any());
    }

    // ===================== 查询范围（权限） =====================

    @Test
    @DisplayName("我的申请：SQL 强制 applicant_id = 当前用户，且不带 owner_id")
    void applicantScope_scopesToCurrentUser() {
        String sql = claimService.applicantScope(APPLICANT, new ClaimPageQuery()).getSqlSegment();

        assertTrue(sql.contains("applicant_id"), "必须按申请人过滤，实际 SQL: " + sql);
        assertFalse(sql.contains("owner_id"), "不应带发布者条件，实际 SQL: " + sql);
    }

    @Test
    @DisplayName("收到的申请：SQL 强制 owner_id = 当前用户，且不带 applicant_id")
    void ownerScope_scopesToCurrentUser() {
        String sql = claimService.ownerScope(OWNER, new ClaimPageQuery()).getSqlSegment();

        assertTrue(sql.contains("owner_id"), "必须按物品发布者过滤，实际 SQL: " + sql);
        assertFalse(sql.contains("applicant_id"), "不应带申请人条件，实际 SQL: " + sql);
    }

    @Test
    @DisplayName("查询：非法状态筛选直接 400（不拿任意字符串去查库）")
    void scopeQuery_invalidStatus_shouldThrow400() {
        ClaimPageQuery query = new ClaimPageQuery();
        query.setStatus("HACK");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.applicantScope(APPLICANT, query));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
    }

    // ===================== 审核：通过 =====================

    private ClaimApplication buildClaim(Long id, Long ownerId, String status) {
        ClaimApplication claim = new ClaimApplication();
        claim.setId(id);
        claim.setItemId(ITEM_ID);
        claim.setApplicantId(APPLICANT);
        claim.setOwnerId(ownerId);
        claim.setStatus(status);
        claim.setVersion(0);
        return claim;
    }

    /** 该物品上还没有别的「已通过」申请 —— 审核通过的前置条件 */
    private void givenNoOtherApprovedClaim() {
        when(claimApplicationMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("审核通过：PENDING → APPROVED，写审核时间与日志，且【不动物品状态】")
    void approve_success_shouldNotTouchItem() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        givenNoOtherApprovedClaim();
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(1);

        claimService.approve(7L, OWNER);

        ArgumentCaptor<ClaimApplication> captor = ArgumentCaptor.forClass(ClaimApplication.class);
        verify(claimApplicationMapper).updateById(captor.capture());
        assertEquals(ClaimApplication.STATUS_APPROVED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getReviewedAt(), "审核通过要记审核时间");

        ArgumentCaptor<ClaimLog> logCaptor = ArgumentCaptor.forClass(ClaimLog.class);
        verify(claimLogMapper).insert(logCaptor.capture());
        assertEquals(ClaimApplication.STATUS_PENDING, logCaptor.getValue().getFromStatus());
        assertEquals(ClaimApplication.STATUS_APPROVED, logCaptor.getValue().getToStatus());
        assertEquals(ClaimLog.ROLE_OWNER, logCaptor.getValue().getOperatorRole());

        // ★ 核心约定：审核通过 ≠ 已经归还。物品必须仍停在「认领中」
        verify(itemService, never()).changeStatusByClaim(any(), any(), any());
        verify(itemService, never()).revertToUnclaimedIfClaiming(any());
    }

    @Test
    @DisplayName("审核通过：乐观锁冲突 → 409，且不写日志")
    void approve_versionConflict_shouldThrow409() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        givenNoOtherApprovedClaim();
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.approve(7L, OWNER));

        assertEquals(ResultCode.CONFLICT, ex.getCode());
        verify(claimLogMapper, never()).insert(any(ClaimLog.class));
    }

    @Test
    @DisplayName("审核通过：该物品已有别的申请通过了 → 409 人话，不是唯一键冲突")
    void approve_itemAlreadyHasApprovedClaim_shouldThrow409() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        when(claimApplicationMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.approve(7L, OWNER));

        assertEquals(ResultCode.CONFLICT, ex.getCode());
        assertEquals("该物品已有其它申请通过审核", ex.getMessage());
        verify(claimApplicationMapper, never()).updateById(any(ClaimApplication.class));
    }

    @Test
    @DisplayName("审核通过：并发撞上数据库唯一键 uk_item_approved → 翻译成 409，绝不能漏成 500")
    void approve_duplicateKeyRace_shouldThrow409Not500() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        givenNoOtherApprovedClaim();
        // 预检查通过之后，另一个人抢先通过了别的申请 —— 只有数据库能挡住
        when(claimApplicationMapper.updateById(any(ClaimApplication.class)))
                .thenThrow(new DuplicateKeyException("uk_item_approved"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.approve(7L, OWNER));

        assertEquals(ResultCode.CONFLICT, ex.getCode());
        assertNotNull(ex.getMessage());
        verify(claimLogMapper, never()).insert(any(ClaimLog.class));
    }

    @Test
    @DisplayName("审核通过：不是物品发布者 → 403")
    void approve_notOwner_shouldThrow403() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.approve(7L, 999L));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
        verify(claimApplicationMapper, never()).updateById(any(ClaimApplication.class));
    }

    @Test
    @DisplayName("审核通过：未登录（ownerId 为 null）→ 403，不能靠 null 绕过归属校验")
    void approve_nullOperator_shouldThrow403() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.approve(7L, null));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
    }

    @Test
    @DisplayName("审核通过：申请不存在 → 404")
    void approve_notFound_shouldThrow404() {
        when(claimApplicationMapper.selectById(7L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.approve(7L, OWNER));

        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("审核通过：申请已经是终态（如已驳回）→ 400，状态机拦下")
    void approve_alreadyTerminal_shouldThrow400() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_REJECTED));
        givenNoOtherApprovedClaim();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.approve(7L, OWNER));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(claimApplicationMapper, never()).updateById(any(ClaimApplication.class));
    }

    // ===================== 审核：驳回 =====================

    @Test
    @DisplayName("驳回：写驳回理由、审核时间与日志；没有其它活跃申请时物品退回待认领")
    void reject_success_shouldRevertItemWhenNoActiveClaimLeft() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(1);
        when(claimApplicationMapper.selectCount(any())).thenReturn(0L);

        claimService.reject(7L, OWNER, "特征描述与实物不符");

        ArgumentCaptor<ClaimApplication> captor = ArgumentCaptor.forClass(ClaimApplication.class);
        verify(claimApplicationMapper).updateById(captor.capture());
        assertEquals(ClaimApplication.STATUS_REJECTED, captor.getValue().getStatus());
        assertEquals("特征描述与实物不符", captor.getValue().getRejectReason());
        assertNotNull(captor.getValue().getReviewedAt());

        ArgumentCaptor<ClaimLog> logCaptor = ArgumentCaptor.forClass(ClaimLog.class);
        verify(claimLogMapper).insert(logCaptor.capture());
        assertEquals("特征描述与实物不符", logCaptor.getValue().getRemark(),
                "驳回理由也要写进流转日志，否则事后查不出为什么驳的");

        verify(itemService).revertToUnclaimedIfClaiming(ITEM_ID);
    }

    @Test
    @DisplayName("驳回：还有别的活跃申请 → 物品保持「认领中」，不能退回去")
    void reject_shouldKeepItemClaimingWhenOtherClaimsRemain() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(1);
        when(claimApplicationMapper.selectCount(any())).thenReturn(1L);

        claimService.reject(7L, OWNER, "不符合");

        verify(itemService, never()).revertToUnclaimedIfClaiming(any());
    }

    @Test
    @DisplayName("驳回：理由为空 → 400，库里什么都不动")
    void reject_blankReason_shouldThrow400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.reject(7L, OWNER, "   "));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(claimApplicationMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("驳回：不是物品发布者 → 403")
    void reject_notOwner_shouldThrow403() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.reject(7L, 999L, "不是你的物品"));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
        verify(itemService, never()).revertToUnclaimedIfClaiming(any());
    }

    @Test
    @DisplayName("驳回：已是终态 → 400")
    void reject_alreadyTerminal_shouldThrow400() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_CANCELLED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.reject(7L, OWNER, "理由"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("驳回：乐观锁冲突 → 409，且不写日志、不退物品")
    void reject_versionConflict_shouldThrow409() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.reject(7L, OWNER, "理由"));

        assertEquals(ResultCode.CONFLICT, ex.getCode());
        verify(claimLogMapper, never()).insert(any(ClaimLog.class));
        verify(itemService, never()).revertToUnclaimedIfClaiming(any());
    }

    // ===================== 确认收到（完成归还） =====================

    private ClaimApplication pendingOther(Long id, Long applicantId) {
        ClaimApplication other = buildClaim(id, OWNER, ClaimApplication.STATUS_PENDING);
        other.setApplicantId(applicantId);
        return other;
    }

    @Test
    @DisplayName("确认收到：APPROVED → COMPLETED，物品转 CLAIMED，写完成时间与日志")
    void complete_success() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_APPROVED));
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(1);
        when(claimApplicationMapper.selectList(any())).thenReturn(List.of());

        ClaimCompleteResult result = claimService.complete(7L, APPLICANT);

        assertEquals(ClaimApplication.STATUS_COMPLETED, result.getStatus());
        assertEquals(Item.STATUS_CLAIMED, result.getItemStatus());
        assertEquals(0, result.getClosedOthers());

        ArgumentCaptor<ClaimApplication> captor = ArgumentCaptor.forClass(ClaimApplication.class);
        verify(claimApplicationMapper).updateById(captor.capture());
        assertEquals(ClaimApplication.STATUS_COMPLETED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getCompletedAt(), "完成归还要记完成时间");

        ArgumentCaptor<ClaimLog> logCaptor = ArgumentCaptor.forClass(ClaimLog.class);
        verify(claimLogMapper).insert(logCaptor.capture());
        assertEquals(ClaimApplication.STATUS_APPROVED, logCaptor.getValue().getFromStatus());
        assertEquals(ClaimApplication.STATUS_COMPLETED, logCaptor.getValue().getToStatus());
        assertEquals(ClaimLog.ROLE_APPLICANT, logCaptor.getValue().getOperatorRole());

        // 物品跟着转「已认领」—— 这是全项目唯一的入口
        verify(itemService).changeStatusByClaim(ITEM_ID, Item.STATUS_CLAIMING, Item.STATUS_CLAIMED);
    }

    @Test
    @DisplayName("确认收到：已经是 COMPLETED 且是本人 → 幂等返回成功，一次写都不做")
    void complete_alreadyCompleted_shouldBeIdempotent() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_COMPLETED));

        ClaimCompleteResult result = claimService.complete(7L, APPLICANT);

        assertEquals(ClaimApplication.STATUS_COMPLETED, result.getStatus());
        assertEquals(Item.STATUS_CLAIMED, result.getItemStatus());
        verify(claimApplicationMapper, never()).updateById(any(ClaimApplication.class));
        verify(claimApplicationMapper, never()).selectList(any());
        verify(itemService, never()).changeStatusByClaim(any(), any(), any());
        verify(claimLogMapper, never()).insert(any(ClaimLog.class));
    }

    @Test
    @DisplayName("确认收到：不是申请人本人 → 403（发布者也不行）")
    void complete_notApplicant_shouldThrow403() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_APPROVED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.complete(7L, OWNER));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
        verify(itemService, never()).changeStatusByClaim(any(), any(), any());
    }

    @Test
    @DisplayName("确认收到：未登录（null）→ 403")
    void complete_nullOperator_shouldThrow403() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_APPROVED));

        assertEquals(ResultCode.FORBIDDEN,
                assertThrows(BusinessException.class, () -> claimService.complete(7L, null)).getCode());
    }

    @Test
    @DisplayName("确认收到：申请不存在 → 404")
    void complete_notFound_shouldThrow404() {
        when(claimApplicationMapper.selectById(7L)).thenReturn(null);

        assertEquals(ResultCode.NOT_FOUND,
                assertThrows(BusinessException.class, () -> claimService.complete(7L, APPLICANT)).getCode());
    }

    @Test
    @DisplayName("确认收到：还没通过审核（PENDING）→ 400，状态机拦下")
    void complete_pendingStatus_shouldThrow400() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));

        assertEquals(ResultCode.BAD_REQUEST,
                assertThrows(BusinessException.class, () -> claimService.complete(7L, APPLICANT)).getCode());
        verify(itemService, never()).changeStatusByClaim(any(), any(), any());
    }

    @Test
    @DisplayName("确认收到：自动关闭其他 PENDING 申请，理由与 SYSTEM 角色都写进日志")
    void complete_shouldAutoCloseOtherPendingClaims() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_APPROVED));
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(1);
        when(claimApplicationMapper.selectList(any()))
                .thenReturn(List.of(pendingOther(21L, 201L), pendingOther(22L, 202L)));

        ClaimCompleteResult result = claimService.complete(7L, APPLICANT);

        assertEquals(2, result.getClosedOthers());

        // 三条日志：1 条完成归还 + 2 条自动关闭
        ArgumentCaptor<ClaimLog> captor = ArgumentCaptor.forClass(ClaimLog.class);
        verify(claimLogMapper, times(3)).insert(captor.capture());
        List<ClaimLog> logs = captor.getAllValues();

        for (ClaimLog autoLog : logs.subList(1, 3)) {
            assertEquals(ClaimApplication.STATUS_PENDING, autoLog.getFromStatus());
            assertEquals(ClaimApplication.STATUS_REJECTED, autoLog.getToStatus());
            assertEquals(ClaimLog.ROLE_SYSTEM, autoLog.getOperatorRole(), "自动关闭必须是 SYSTEM 角色");
            assertEquals("该物品已完成归还，其他申请自动关闭", autoLog.getRemark());
            // operator_id 记的是「触发这次流程的人」（申请人），用 role 区分这不是他手动去驳别人
            assertEquals(APPLICANT, autoLog.getOperatorId());
        }
    }

    @Test
    @DisplayName("确认收到：某条 PENDING 已被他人抢先处理 → 跳过它，不影响本次确认")
    void complete_autoCloseSkipsClaimHandledByOthers() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_APPROVED));
        // 第 1 次是当前申请（成功），第 2 次是被别人处理掉的那条（失败），第 3 次正常
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(1, 0, 1);
        when(claimApplicationMapper.selectList(any()))
                .thenReturn(List.of(pendingOther(21L, 201L), pendingOther(22L, 202L)));

        ClaimCompleteResult result = claimService.complete(7L, APPLICANT);

        assertEquals(1, result.getClosedOthers(), "跳过的不能算进已关闭条数");
        // 2 条日志：1 条完成归还 + 1 条真正关掉的（被跳过的没有日志）
        verify(claimLogMapper, times(2)).insert(any(ClaimLog.class));
    }

    @Test
    @DisplayName("确认收到：物品 CAS 失败（状态被人手工改过）→ 异常上抛，不做自动关闭")
    void complete_itemConflict_shouldPropagateAndSkipAutoClose() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_APPROVED));
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(1);
        doThrow(new BusinessException(ResultCode.CONFLICT, "物品当前状态为 UNCLAIMED，操作未生效"))
                .when(itemService).changeStatusByClaim(any(), any(), any());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> claimService.complete(7L, APPLICANT));

        assertEquals(ResultCode.CONFLICT, ex.getCode());
        verify(claimApplicationMapper, never()).selectList(any());
    }

    // ===================== 撤回 =====================

    @Test
    @DisplayName("撤回：PENDING → CANCELLED，写日志；没有其他活跃申请时物品退回待认领")
    void cancel_success_shouldCancelAndRevertItem() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(1);
        when(claimApplicationMapper.selectCount(any())).thenReturn(0L);

        claimService.cancel(7L, APPLICANT);

        ArgumentCaptor<ClaimApplication> captor = ArgumentCaptor.forClass(ClaimApplication.class);
        verify(claimApplicationMapper).updateById(captor.capture());
        assertEquals(ClaimApplication.STATUS_CANCELLED, captor.getValue().getStatus());

        ArgumentCaptor<ClaimLog> logCaptor = ArgumentCaptor.forClass(ClaimLog.class);
        verify(claimLogMapper).insert(logCaptor.capture());
        assertEquals(ClaimApplication.STATUS_PENDING, logCaptor.getValue().getFromStatus());
        assertEquals(ClaimApplication.STATUS_CANCELLED, logCaptor.getValue().getToStatus());
        assertEquals(ClaimLog.ROLE_APPLICANT, logCaptor.getValue().getOperatorRole());

        verify(itemService).revertToUnclaimedIfClaiming(ITEM_ID);
    }

    @Test
    @DisplayName("撤回：还有别的活跃申请 → 物品保持「认领中」")
    void cancel_shouldKeepItemWhenOtherClaimsRemain() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        when(claimApplicationMapper.updateById(any(ClaimApplication.class))).thenReturn(1);
        when(claimApplicationMapper.selectCount(any())).thenReturn(1L);

        claimService.cancel(7L, APPLICANT);

        verify(itemService, never()).revertToUnclaimedIfClaiming(any());
    }

    @Test
    @DisplayName("撤回：不是申请人本人 → 403")
    void cancel_notApplicant_shouldThrow403() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));

        assertEquals(ResultCode.FORBIDDEN,
                assertThrows(BusinessException.class, () -> claimService.cancel(7L, OWNER)).getCode());
        verify(claimApplicationMapper, never()).updateById(any(ClaimApplication.class));
    }

    @Test
    @DisplayName("撤回：已通过的申请撤不了（东西都快到手了，不能靠按钮让对方物品无声回退）")
    void cancel_approvedClaim_shouldThrow400() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_APPROVED));

        assertEquals(ResultCode.BAD_REQUEST,
                assertThrows(BusinessException.class, () -> claimService.cancel(7L, APPLICANT)).getCode());
    }

    @Test
    @DisplayName("撤回：申请不存在 → 404")
    void cancel_notFound_shouldThrow404() {
        when(claimApplicationMapper.selectById(7L)).thenReturn(null);

        assertEquals(ResultCode.NOT_FOUND,
                assertThrows(BusinessException.class, () -> claimService.cancel(7L, APPLICANT)).getCode());
    }

    // ===================== 详情与时间线 =====================

    private ClaimLog buildLog(Long claimId, String from, String to, Long operatorId, String role, String remark) {
        ClaimLog logRecord = new ClaimLog();
        logRecord.setClaimId(claimId);
        logRecord.setFromStatus(from);
        logRecord.setToStatus(to);
        logRecord.setOperatorId(operatorId);
        logRecord.setOperatorRole(role);
        logRecord.setRemark(remark);
        return logRecord;
    }

    private void givenDetailDependencies() {
        when(userService.getNicknameById(any())).thenReturn("小白");
        when(itemService.getById(ITEM_ID)).thenReturn(buildItem(Item.TYPE_FOUND, Item.STATUS_CLAIMING, OWNER));
    }

    @Test
    @DisplayName("详情：申请人可看，带出物品摘要、申请人昵称与完整时间线")
    void getDetail_applicantCanSee() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_APPROVED));
        givenDetailDependencies();
        when(claimLogMapper.selectList(any())).thenReturn(List.of(
                buildLog(7L, null, ClaimApplication.STATUS_PENDING, APPLICANT, ClaimLog.ROLE_APPLICANT, null),
                buildLog(7L, ClaimApplication.STATUS_PENDING, ClaimApplication.STATUS_APPROVED,
                        OWNER, ClaimLog.ROLE_OWNER, null)));

        ClaimDetailView view = claimService.getDetail(7L, APPLICANT);

        assertEquals(7L, view.getClaimId().longValue());
        assertEquals(ClaimApplication.STATUS_APPROVED, view.getStatus());
        assertEquals("小白", view.getApplicantNickname());
        assertEquals("银色圆头车钥匙", view.getItemTitle());
        assertEquals(Item.STATUS_CLAIMING, view.getItemStatus());

        assertEquals(2, view.getTimeline().size(), "时间线要完整带出全部流转");
        assertNull(view.getTimeline().get(0).getFromStatus(), "首次提交没有变更前状态");
        assertEquals(ClaimLog.ROLE_OWNER, view.getTimeline().get(1).getOperatorRole());
    }

    @Test
    @DisplayName("详情：物品发布者也能看（他要据此审核）")
    void getDetail_ownerCanSee() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        givenDetailDependencies();
        when(claimLogMapper.selectList(any())).thenReturn(List.of());

        ClaimDetailView view = claimService.getDetail(7L, OWNER);

        assertEquals(7L, view.getClaimId().longValue());
        assertEquals(0, view.getTimeline().size());
    }

    @Test
    @DisplayName("详情：第三方（含未登录）一律 403 —— 特征证明不能给别人看")
    void getDetail_thirdParty_shouldThrow403() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));

        assertEquals(ResultCode.FORBIDDEN,
                assertThrows(BusinessException.class, () -> claimService.getDetail(7L, 999L)).getCode());
        assertEquals(ResultCode.FORBIDDEN,
                assertThrows(BusinessException.class, () -> claimService.getDetail(7L, null)).getCode());
    }

    @Test
    @DisplayName("详情：申请不存在 → 404")
    void getDetail_notFound_shouldThrow404() {
        when(claimApplicationMapper.selectById(7L)).thenReturn(null);

        assertEquals(ResultCode.NOT_FOUND,
                assertThrows(BusinessException.class, () -> claimService.getDetail(7L, APPLICANT)).getCode());
    }

    @Test
    @DisplayName("详情：物品查不到也不炸（只填申请本身的信息）")
    void getDetail_toleratesMissingItem() {
        when(claimApplicationMapper.selectById(7L))
                .thenReturn(buildClaim(7L, OWNER, ClaimApplication.STATUS_PENDING));
        when(userService.getNicknameById(any())).thenReturn("小白");
        when(itemService.getById(ITEM_ID)).thenReturn(null);
        when(claimLogMapper.selectList(any())).thenReturn(List.of());

        ClaimDetailView view = claimService.getDetail(7L, APPLICANT);

        assertEquals(ITEM_ID, view.getItemId());
        assertNull(view.getItemTitle());
    }

    // ===================== 详情页要用的两个判断 =====================

    @Test
    @DisplayName("联系方式可见性：未登录直接 false，连库都不查")
    void canSeeContact_guestIsAlwaysFalse() {
        assertFalse(claimService.canSeeContact(ITEM_ID, null));

        verify(claimApplicationMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("联系方式可见性：有已通过/已完成的申请 → true")
    void canSeeContact_trueWhenApprovedOrCompleted() {
        when(claimApplicationMapper.selectCount(any())).thenReturn(1L);

        assertTrue(claimService.canSeeContact(ITEM_ID, APPLICANT));
    }

    @Test
    @DisplayName("联系方式可见性：没有通过的申请 → false")
    void canSeeContact_falseWithoutApprovedClaim() {
        when(claimApplicationMapper.selectCount(any())).thenReturn(0L);

        assertFalse(claimService.canSeeContact(ITEM_ID, APPLICANT));
    }

    @Test
    @DisplayName("申请状态：未登录返回 null，不查库")
    void activeClaimStatus_nullForGuest() {
        assertNull(claimService.activeClaimStatus(ITEM_ID, null));

        verify(claimApplicationMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("申请状态：返回活跃申请的状态")
    void activeClaimStatus_returnsActiveStatus() {
        ClaimApplication active = new ClaimApplication();
        active.setStatus(ClaimApplication.STATUS_PENDING);
        when(claimApplicationMapper.selectOne(any())).thenReturn(active);

        assertEquals(ClaimApplication.STATUS_PENDING, claimService.activeClaimStatus(ITEM_ID, APPLICANT));
    }

    @Test
    @DisplayName("申请状态：没有活跃申请（比如被驳回了）返回 null")
    void activeClaimStatus_nullWhenNone() {
        when(claimApplicationMapper.selectOne(any())).thenReturn(null);

        assertNull(claimService.activeClaimStatus(ITEM_ID, APPLICANT));
    }

    // ===================== 视图装配 =====================

    @Test
    @DisplayName("申请人视角：摊平物品信息，且模型里没有他人字段")
    void toMyClaimView_flattensItemFields() {
        ClaimApplication claim = new ClaimApplication();
        claim.setId(7L);
        claim.setItemId(ITEM_ID);
        claim.setStatus(ClaimApplication.STATUS_REJECTED);
        claim.setRejectReason("描述与实物不符");
        claim.setProofText("灰色小恐龙挂件");

        MyClaimView view = claimService.toMyClaimView(
                claim, buildItem(Item.TYPE_FOUND, Item.STATUS_CLAIMING, OWNER));

        assertEquals(7L, view.getClaimId().longValue());
        assertEquals(ITEM_ID, view.getItemId());
        assertEquals("银色圆头车钥匙", view.getItemTitle());
        assertEquals(Item.STATUS_CLAIMING, view.getItemStatus());
        assertEquals("描述与实物不符", view.getRejectReason());
    }

    @Test
    @DisplayName("发布者视角：带申请人昵称与特征证明")
    void toReceivedClaimView_carriesProofAndApplicant() {
        ClaimApplication claim = new ClaimApplication();
        claim.setId(7L);
        claim.setItemId(ITEM_ID);
        claim.setApplicantId(APPLICANT);
        claim.setStatus(ClaimApplication.STATUS_PENDING);
        claim.setProofText("灰色小恐龙挂件");
        claim.setProofImage("/uploads/proof.jpg");
        claim.setContact("13800000000");

        ReceivedClaimView view = claimService.toReceivedClaimView(
                claim, buildItem(Item.TYPE_FOUND, Item.STATUS_CLAIMING, OWNER), "小白");

        assertEquals("小白", view.getApplicantNickname());
        assertEquals("灰色小恐龙挂件", view.getProofText());
        assertEquals("/uploads/proof.jpg", view.getProofImage());
        assertEquals("13800000000", view.getContact());
        assertEquals(Item.STATUS_CLAIMING, view.getItemStatus());
    }

    @Test
    @DisplayName("视图装配：物品查不到也不炸（只填申请本身的信息）")
    void toMyClaimView_toleratesMissingItem() {
        ClaimApplication claim = new ClaimApplication();
        claim.setId(7L);
        claim.setItemId(ITEM_ID);
        claim.setStatus(ClaimApplication.STATUS_PENDING);

        MyClaimView view = claimService.toMyClaimView(claim, null);

        assertEquals(ITEM_ID, view.getItemId());
        assertNull(view.getItemTitle());
    }
}
