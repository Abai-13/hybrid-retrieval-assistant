package com.lostfound.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.common.BusinessException;
import com.lostfound.common.ResultCode;
import com.lostfound.dto.NotificationView;
import com.lostfound.entity.Item;
import com.lostfound.entity.Notification;
import com.lostfound.entity.NotificationMatch;
import com.lostfound.mapper.NotificationMapper;
import com.lostfound.mapper.NotificationMatchMapper;
import com.lostfound.service.ItemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * NotificationServiceImpl 单元测试（纯 Mockito）。
 * 覆盖：汇总通知+明细的原子写入、唯一键去重、越权、已读幂等、视图装配。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private NotificationMatchMapper notificationMatchMapper;

    @Mock
    private ItemService itemService;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private static final Long RECEIVER = 100L;
    private static final Long SOURCE = 7L;

    private Notification notification() {
        Notification n = new Notification();
        n.setReceiverId(RECEIVER);
        n.setSourceItemId(SOURCE);
        n.setIsRead(Notification.UNREAD);
        n.setContent("系统为你的失物《黑色华为手机》找到了 2 条可能匹配的招领信息，点击查看。");
        return n;
    }

    private NotificationMatch detail(long foundId, int score) {
        NotificationMatch d = new NotificationMatch();
        d.setLostItemId(SOURCE);
        d.setFoundItemId(foundId);
        d.setScore(score);
        d.setReason("很像");
        return d;
    }

    // ===================== 生成 =====================

    @Test
    @DisplayName("生成：主表 + 明细一次写入，明细带上通知ID")
    void createsNotificationWithDetails() {
        when(notificationMapper.selectCount(any())).thenReturn(0L);
        when(notificationMapper.insert(any(Notification.class))).thenAnswer(inv -> {
            inv.<Notification>getArgument(0).setId(555L);
            return 1;
        });

        boolean created = notificationService.createWithMatches(
                notification(), List.of(detail(11L, 95), detail(12L, 88)));

        assertTrue(created);

        ArgumentCaptor<Notification> nCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(nCaptor.capture());
        assertEquals(RECEIVER, nCaptor.getValue().getReceiverId());
        assertEquals(SOURCE, nCaptor.getValue().getSourceItemId());

        ArgumentCaptor<NotificationMatch> mCaptor = ArgumentCaptor.forClass(NotificationMatch.class);
        verify(notificationMatchMapper, times(2)).insert(mCaptor.capture());
        // 明细必须挂到刚生成的通知上，否则用户点开会看到空列表
        mCaptor.getAllValues().forEach(d -> assertEquals(555L, d.getNotificationId()));
        assertEquals(List.of(11L, 12L), mCaptor.getAllValues().stream().map(NotificationMatch::getFoundItemId).toList());
    }

    @Test
    @DisplayName("去重：同一（发布事件 + 收件人）已有通知 → 直接跳过，什么都不写")
    void skipsWhenAlreadyNotified() {
        when(notificationMapper.selectCount(any())).thenReturn(1L);

        boolean created = notificationService.createWithMatches(notification(), List.of(detail(11L, 95)));

        assertFalse(created);
        verify(notificationMapper, never()).insert(any(Notification.class));
        verify(notificationMatchMapper, never()).insert(any(NotificationMatch.class));
    }

    @Test
    @DisplayName("并发去重：两个任务同时处理同一发布事件，撞唯一键的那个被转成「跳过」而不是 500")
    void duplicateKeyIsTurnedIntoSkip() {
        when(notificationMapper.selectCount(any())).thenReturn(0L);
        when(notificationMapper.insert(any(Notification.class)))
                .thenThrow(new DuplicateKeyException("uk_source_receiver"));

        boolean created = notificationService.createWithMatches(notification(), List.of(detail(11L, 95)));

        assertFalse(created, "重复通知是预期内的，不该抛异常");
        verify(notificationMatchMapper, never()).insert(any(NotificationMatch.class));
    }

    // ===================== 已读 =====================

    @Test
    @DisplayName("标记已读：写 isRead 与 readAt")
    void markReadWritesFlags() {
        when(notificationMapper.selectById(1L)).thenReturn(notificationWithId(1L, Notification.UNREAD));

        notificationService.markRead(1L, RECEIVER);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).updateById(captor.capture());
        assertEquals(Notification.READ, captor.getValue().getIsRead());
        assertNotNull(captor.getValue().getReadAt());
    }

    @Test
    @DisplayName("标记已读：已经是已读 → 幂等返回，不产生多余的写")
    void markReadIsIdempotent() {
        when(notificationMapper.selectById(1L)).thenReturn(notificationWithId(1L, Notification.READ));

        notificationService.markRead(1L, RECEIVER);

        verify(notificationMapper, never()).updateById(any(Notification.class));
    }

    @Test
    @DisplayName("标记已读：别人的通知 → 403")
    void markReadRejectsOtherUsersNotification() {
        when(notificationMapper.selectById(1L)).thenReturn(notificationWithId(1L, Notification.UNREAD));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> notificationService.markRead(1L, 999L));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
        verify(notificationMapper, never()).updateById(any(Notification.class));
    }

    @Test
    @DisplayName("标记已读：通知不存在 → 404")
    void markReadMissingNotification() {
        when(notificationMapper.selectById(1L)).thenReturn(null);

        assertEquals(ResultCode.NOT_FOUND,
                assertThrows(BusinessException.class, () -> notificationService.markRead(1L, RECEIVER)).getCode());
    }

    @Test
    @DisplayName("未读数：只数自己的未读")
    void unreadCount() {
        when(notificationMapper.selectCount(any())).thenReturn(3L);

        assertEquals(3L, notificationService.unreadCount(RECEIVER));
    }

    @Test
    @DisplayName("全部已读：返回实际影响的条数")
    void markAllRead() {
        when(notificationMapper.update(any(), any())).thenReturn(4);

        assertEquals(4, notificationService.markAllRead(RECEIVER));
    }

    // ===================== 视图装配 =====================

    @Test
    @DisplayName("装配：内联明细、按分数降序、物品字段来自本地库")
    void assemblesViewsWithDetails() {
        Notification first = notificationWithId(1L, Notification.UNREAD);
        Notification second = notificationWithId(2L, Notification.UNREAD);
        when(notificationMatchMapper.selectList(any())).thenReturn(List.of(
                matchOf(1L, 11L, 70), matchOf(1L, 12L, 95), matchOf(2L, 13L, 88)));
        when(itemService.mapByIds(any())).thenReturn(Map.of(
                11L, item(11L, "银色车钥匙"), 12L, item(12L, "黑色雨伞"),
                13L, item(13L, "蓝色水杯"), SOURCE, item(SOURCE, "我的失物")));

        List<NotificationView> views = notificationService.toViews(List.of(first, second));

        assertEquals(2, views.size());
        assertEquals(2, views.get(0).getMatches().size());
        assertEquals(1, views.get(1).getMatches().size());
        // 分数降序：95 在前
        assertEquals(95, views.get(0).getMatches().get(0).getScore());
        assertEquals(12L, views.get(0).getMatches().get(0).getItemId());
        assertEquals("黑色雨伞", views.get(0).getMatches().get(0).getTitle());
        assertEquals("我的失物", views.get(0).getMatches().get(0).getMyItemTitle());
        assertFalse(views.get(0).getIsRead());
    }

    @Test
    @DisplayName("装配：某条通知的匹配物品查不到也不炸（只填 id 与分数）")
    void assemblesToleratesMissingItem() {
        when(notificationMatchMapper.selectList(any())).thenReturn(List.of(matchOf(1L, 99L, 90)));
        when(itemService.mapByIds(any())).thenReturn(Map.of());

        List<NotificationView> views = notificationService.toViews(List.of(notificationWithId(1L, Notification.UNREAD)));

        assertEquals(1, views.get(0).getMatches().size());
        assertEquals(99L, views.get(0).getMatches().get(0).getItemId());
        assertNull(views.get(0).getMatches().get(0).getTitle());
    }

    @Test
    @DisplayName("装配：空列表直接返回空，不查库")
    void assemblesEmptyWithoutQuery() {
        assertTrue(notificationService.toViews(List.of()).isEmpty());

        verify(notificationMatchMapper, never()).selectList(any());
    }

    // ===================== 辅助 =====================

    private Notification notificationWithId(Long id, int isRead) {
        Notification n = notification();
        n.setId(id);
        n.setIsRead(isRead);
        return n;
    }

    private NotificationMatch matchOf(Long notificationId, Long foundId, int score) {
        NotificationMatch d = detail(foundId, score);
        d.setNotificationId(notificationId);
        return d;
    }

    private Item item(Long id, String title) {
        Item item = new Item();
        item.setId(id);
        item.setTitle(title);
        item.setStatus(Item.STATUS_UNCLAIMED);
        return item;
    }

    @Test
    @DisplayName("分页：返回结构与物品/认领模块一致")
    void pageShape() {
        Page<Notification> source = new Page<>(1, 10);
        source.setRecords(List.of());
        source.setTotal(0);
        when(notificationMapper.selectPage(any(), any())).thenReturn(source);

        Page<NotificationView> result = notificationService.pageByReceiver(RECEIVER, 1, 10, false);

        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verify(notificationMatchMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("分页：空页时不去查明细和物品（省掉两次无意义的查询）")
    void pageWithEmptyRecordsSkipsJoins() {
        Page<Notification> source = new Page<>(1, 10);
        source.setRecords(List.of());
        source.setTotal(0);
        when(notificationMapper.selectPage(any(), any())).thenReturn(source);

        notificationService.pageByReceiver(RECEIVER, 1, 10, true);

        verify(notificationMapper).selectPage(any(), any());
        verify(notificationMatchMapper, never()).selectList(any());
        verify(itemService, never()).mapByIds(any());
    }
}
