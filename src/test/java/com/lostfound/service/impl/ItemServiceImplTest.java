package com.lostfound.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lostfound.common.BusinessException;
import com.lostfound.common.ResultCode;
import com.lostfound.dto.ItemCreateRequest;
import com.lostfound.entity.Item;
import com.lostfound.mapper.ItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ItemServiceImpl 单元测试（纯 Mockito）。
 * 覆盖：发布（type 校验）、发布者改状态（越权防护 + 乐观锁重试）、
 * 认领流程驱动改状态（状态机 + CAS 冲突）、AI 候选池的状态范围。
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceImplTest {

    @Mock
    private ItemMapper itemMapper;

    @InjectMocks
    private ItemServiceImpl itemService;

    /**
     * LambdaQueryWrapper 要把 {@code Item::getStatus} 解析成列名，靠的是 MyBatis-Plus 的
     * TableInfo 缓存；而这个缓存在纯单元测试里没人初始化，不灌一次连 {@code getSqlSegment()}
     * 都调不动。只为让下面「候选池条件」那条断言能跑起来。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Item.class);
    }

    private Item buildItem(Long id, Long userId, String status) {
        Item item = new Item();
        item.setId(id);
        item.setUserId(userId);
        item.setTitle("测试物品");
        item.setType("LOST");
        item.setStatus(status);
        item.setVersion(1);
        return item;
    }

    private ItemCreateRequest buildRequest(String type) {
        ItemCreateRequest req = new ItemCreateRequest();
        req.setTitle("校园卡");
        req.setType(type);
        req.setCategory("证件");
        req.setLocation("图书馆");
        req.setDescription("丢失校园卡一张");
        req.setContact("13800000000");
        return req;
    }

    @Test
    @DisplayName("发布：type 非法 → 抛业务异常 400，且不落库")
    void publish_invalidType_shouldThrow() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.publish(1L, buildRequest("WRONG"), "img.jpg"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(itemMapper, never()).insert(any(Item.class));
    }

    @Test
    @DisplayName("改状态：非发布者 → 抛 403 越权")
    void updateStatus_notOwner_shouldThrowForbidden() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "UNCLAIMED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.updateStatus(1L, 200L, "CLAIMED"));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
        assertEquals("只能修改自己发布的物品", ex.getMessage());
        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    @DisplayName("改状态：物品不存在 → 抛 404")
    void updateStatus_itemNotFound_shouldThrow() {
        when(itemMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.updateStatus(99L, 100L, "CLAIMED"));

        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("改状态：状态已是目标值 → 直接返回，不触发更新")
    void updateStatus_alreadyTargetStatus_shouldSkip() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "CLAIMED"));

        itemService.updateStatus(1L, 100L, "CLAIMED");

        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    @DisplayName("改状态：撤销已认领（CLAIMED → UNCLAIMED）成功")
    void updateStatus_success_shouldUpdate() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "CLAIMED"));
        when(itemMapper.updateById(any(Item.class))).thenReturn(1);

        itemService.updateStatus(1L, 100L, "UNCLAIMED");

        verify(itemMapper).updateById(any(Item.class));
    }

    @Test
    @DisplayName("改状态：不允许直接标记「已认领」（那会绕过整条认领流程）")
    void updateStatus_cannotMarkClaimedDirectly_shouldThrow400() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "UNCLAIMED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.updateStatus(1L, 100L, "CLAIMED"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    @DisplayName("改状态：不允许把「认领中」手工改回未认领（那是驳回/撤回该干的事）")
    void updateStatus_cannotResetClaiming_shouldThrow400() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "CLAIMING"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.updateStatus(1L, 100L, "UNCLAIMED"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    @DisplayName("改状态：乐观锁冲突一次后重试成功")
    void updateStatus_optimisticLockRetry_shouldSucceed() {
        // 每次 selectById 返回全新实例，避免上一轮 setStatus 污染下一轮
        when(itemMapper.selectById(1L)).thenAnswer(inv -> buildItem(1L, 100L, "CLAIMED"));
        // 第一次 updateById 返回 0（version 冲突），第二次返回 1（成功）
        when(itemMapper.updateById(any(Item.class))).thenReturn(0, 1);

        itemService.updateStatus(1L, 100L, "UNCLAIMED");

        verify(itemMapper, times(2)).updateById(any(Item.class));
    }

    @Test
    @DisplayName("改状态：乐观锁连续 3 次冲突 → 抛 500")
    void updateStatus_optimisticLockExhausted_shouldThrow() {
        when(itemMapper.selectById(1L)).thenAnswer(inv -> buildItem(1L, 100L, "CLAIMED"));
        when(itemMapper.updateById(any(Item.class))).thenReturn(0, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.updateStatus(1L, 100L, "UNCLAIMED"));

        assertEquals(ResultCode.INTERNAL_ERROR, ex.getCode());
        verify(itemMapper, times(3)).updateById(any(Item.class));
    }

    // ===================== P0 新增 =====================

    @Test
    @DisplayName("改状态：非法状态值 → 抛 400，且不查库不改库")
    void updateStatus_invalidStatusValue_shouldThrow() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.updateStatus(1L, 100L, "HACK"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(itemMapper, never()).selectById(any());
        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    @DisplayName("认领驱动改状态：UNCLAIMED → CLAIMING（有人提交申请）")
    void changeStatusByClaim_unclaimedToClaiming_shouldUpdate() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "UNCLAIMED"));
        when(itemMapper.updateById(any(Item.class))).thenReturn(1);

        itemService.changeStatusByClaim(1L, "UNCLAIMED", "CLAIMING");

        verify(itemMapper).updateById(any(Item.class));
    }

    @Test
    @DisplayName("认领驱动改状态：CLAIMING → CLAIMED（归还完成）")
    void changeStatusByClaim_claimingToClaimed_shouldUpdate() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "CLAIMING"));
        when(itemMapper.updateById(any(Item.class))).thenReturn(1);

        itemService.changeStatusByClaim(1L, "CLAIMING", "CLAIMED");

        verify(itemMapper).updateById(any(Item.class));
    }

    @Test
    @DisplayName("认领驱动改状态：CLAIMING → UNCLAIMED（申请全部关闭后回退）")
    void changeStatusByClaim_claimingToUnclaimed_shouldUpdate() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "CLAIMING"));
        when(itemMapper.updateById(any(Item.class))).thenReturn(1);

        itemService.changeStatusByClaim(1L, "CLAIMING", "UNCLAIMED");

        verify(itemMapper).updateById(any(Item.class));
    }

    @Test
    @DisplayName("认领驱动改状态：UNCLAIMED 不能直接跳到 CLAIMED（不能绕过流程）")
    void changeStatusByClaim_cannotSkipToClaimed() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.changeStatusByClaim(1L, "UNCLAIMED", "CLAIMED"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(itemMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("认领驱动改状态：CLAIMED → UNCLAIMED 不走流程（那是发布者主动撤销）")
    void changeStatusByClaim_cannotUndoClaimed() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.changeStatusByClaim(1L, "CLAIMED", "UNCLAIMED"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(itemMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("认领驱动改状态：非法状态值 → 抛 400")
    void changeStatusByClaim_invalidStatusValue_shouldThrow() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.changeStatusByClaim(1L, "UNCLAIMED", "HACK"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(itemMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("认领驱动改状态：库里状态与期望不符 → 409 冲突，绝不硬改（防并发覆盖）")
    void changeStatusByClaim_statusMismatch_shouldThrowConflict() {
        // 库里已经是 CLAIMED，但调用方以为还在 CLAIMING
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "CLAIMED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.changeStatusByClaim(1L, "CLAIMING", "UNCLAIMED"));

        assertEquals(ResultCode.CONFLICT, ex.getCode());
        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    @DisplayName("认领驱动改状态：已经是目标状态 → 幂等返回，不产生写")
    void changeStatusByClaim_alreadyTarget_shouldSkip() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, "CLAIMING"));

        itemService.changeStatusByClaim(1L, "UNCLAIMED", "CLAIMING");

        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    @DisplayName("认领驱动改状态：物品不存在 → 抛 404")
    void changeStatusByClaim_itemNotFound_shouldThrow() {
        when(itemMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.changeStatusByClaim(99L, "UNCLAIMED", "CLAIMING"));

        assertEquals(ResultCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("认领驱动改状态：乐观锁冲突一次后重试成功")
    void changeStatusByClaim_optimisticLockRetry_shouldSucceed() {
        when(itemMapper.selectById(1L)).thenAnswer(inv -> buildItem(1L, 100L, "UNCLAIMED"));
        when(itemMapper.updateById(any(Item.class))).thenReturn(0, 1);

        itemService.changeStatusByClaim(1L, "UNCLAIMED", "CLAIMING");

        verify(itemMapper, times(2)).updateById(any(Item.class));
    }

    @Test
    @DisplayName("认领驱动改状态：乐观锁连续 3 次冲突 → 抛 500")
    void changeStatusByClaim_optimisticLockExhausted_shouldThrow() {
        when(itemMapper.selectById(1L)).thenAnswer(inv -> buildItem(1L, 100L, "UNCLAIMED"));
        when(itemMapper.updateById(any(Item.class))).thenReturn(0, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.changeStatusByClaim(1L, "UNCLAIMED", "CLAIMING"));

        assertEquals(ResultCode.INTERNAL_ERROR, ex.getCode());
        verify(itemMapper, times(3)).updateById(any(Item.class));
    }

    // ===================== 无活跃申请时退回待认领 =====================

    @Test
    @DisplayName("退回待认领：物品是 CLAIMING → 改回 UNCLAIMED")
    void revertToUnclaimed_whenClaiming_shouldUpdate() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, Item.STATUS_CLAIMING));
        when(itemMapper.updateById(any(Item.class))).thenReturn(1);

        itemService.revertToUnclaimedIfClaiming(1L);

        ArgumentCaptor<Item> captor = ArgumentCaptor.forClass(Item.class);
        verify(itemMapper).updateById(captor.capture());
        assertEquals(Item.STATUS_UNCLAIMED, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("退回待认领：物品已是 UNCLAIMED → 幂等返回，不产生写")
    void revertToUnclaimed_whenAlreadyUnclaimed_shouldSkip() {
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, Item.STATUS_UNCLAIMED));

        itemService.revertToUnclaimedIfClaiming(1L);

        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    @DisplayName("退回待认领：物品已 CLAIMED → 什么都不做，也不抛异常")
    void revertToUnclaimed_whenClaimed_shouldSkipWithoutError() {
        // 这正是它不能用 changeStatusByClaim 的原因：那种情况下 CAS 会抛 409，
        // 把"驳回一条历史遗留申请"这个完全正常的操作给拦下来
        when(itemMapper.selectById(1L)).thenReturn(buildItem(1L, 100L, Item.STATUS_CLAIMED));

        assertDoesNotThrow(() -> itemService.revertToUnclaimedIfClaiming(1L));

        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    @DisplayName("退回待认领：物品不存在 → 不抛异常")
    void revertToUnclaimed_itemMissing_shouldNotThrow() {
        when(itemMapper.selectById(99L)).thenReturn(null);

        assertDoesNotThrow(() -> itemService.revertToUnclaimedIfClaiming(99L));
    }

    @Test
    @DisplayName("退回待认领：乐观锁冲突一次后重试成功")
    void revertToUnclaimed_optimisticLockRetry_shouldSucceed() {
        when(itemMapper.selectById(1L)).thenAnswer(inv -> buildItem(1L, 100L, Item.STATUS_CLAIMING));
        when(itemMapper.updateById(any(Item.class))).thenReturn(0, 1);

        itemService.revertToUnclaimedIfClaiming(1L);

        verify(itemMapper, times(2)).updateById(any(Item.class));
    }

    @Test
    @DisplayName("退回待认领：连续 3 次冲突 → 抛 500")
    void revertToUnclaimed_optimisticLockExhausted_shouldThrow() {
        when(itemMapper.selectById(1L)).thenAnswer(inv -> buildItem(1L, 100L, Item.STATUS_CLAIMING));
        when(itemMapper.updateById(any(Item.class))).thenReturn(0, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> itemService.revertToUnclaimedIfClaiming(1L));

        assertEquals(ResultCode.INTERNAL_ERROR, ex.getCode());
    }

    @Test
    @DisplayName("AI 候选池：含 CLAIMING（有申请不等于已归还），只排除 CLAIMED")
    void listUnclaimedFound_shouldIncludeClaiming() {
        List<Wrapper<Item>> captured = new ArrayList<>();
        when(itemMapper.selectList(any())).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return List.of();
        });

        itemService.listUnclaimedFound();

        assertEquals(1, captured.size(), "应当查询了一次候选池");
        String sql = captured.get(0).getSqlSegment();
        assertTrue(sql.contains("type"), "候选池仍应限定 type=FOUND，实际 SQL: " + sql);
        assertTrue(sql.contains("status"), "候选池应带 status 条件，实际 SQL: " + sql);
        assertTrue(sql.contains("IN"), "候选池应改为多值筛选（含 CLAIMING），实际 SQL: " + sql);
    }
}
