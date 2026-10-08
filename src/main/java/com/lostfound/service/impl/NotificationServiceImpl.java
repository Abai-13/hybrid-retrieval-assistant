package com.lostfound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.common.BusinessException;
import com.lostfound.common.PageLimits;
import com.lostfound.common.ResultCode;
import com.lostfound.dto.NotificationMatchView;
import com.lostfound.dto.NotificationView;
import com.lostfound.entity.Item;
import com.lostfound.entity.Notification;
import com.lostfound.entity.NotificationMatch;
import com.lostfound.mapper.NotificationMapper;
import com.lostfound.mapper.NotificationMatchMapper;
import com.lostfound.service.ItemService;
import com.lostfound.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationMatchMapper notificationMatchMapper;
    private final ItemService itemService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createWithMatches(Notification notification, List<NotificationMatch> matches) {
        // 先查一次，走上"已经通知过"这条正常路径时不用靠抛异常；
        // 并发下真正兜底的是唯一键 uk_source_receiver。
        Long exists = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getSourceItemId, notification.getSourceItemId())
                        .eq(Notification::getReceiverId, notification.getReceiverId()));
        if (exists > 0) {
            log.info("该发布事件已通知过，跳过: sourceItemId={}, receiverId={}",
                    notification.getSourceItemId(), notification.getReceiverId());
            return false;
        }

        try {
            notificationMapper.insert(notification);
        } catch (DuplicateKeyException e) {
            // 两个异步任务同时处理同一个发布事件时，第二个会撞在这里。
            // 转成"跳过"而不是 500 —— 这本来就是预期内的重复。
            log.info("并发重复通知被唯一键挡下，跳过: sourceItemId={}, receiverId={}",
                    notification.getSourceItemId(), notification.getReceiverId());
            return false;
        }

        for (NotificationMatch match : matches) {
            match.setNotificationId(notification.getId());
            notificationMatchMapper.insert(match);
        }
        log.info("生成站内通知: id={}, receiverId={}, 匹配 {} 条",
                notification.getId(), notification.getReceiverId(), matches.size());
        return true;
    }

    @Override
    public Page<NotificationView> pageByReceiver(Long receiverId, int page, int size, boolean unreadOnly) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getReceiverId, receiverId);
        if (unreadOnly) {
            wrapper.eq(Notification::getIsRead, Notification.UNREAD);
        }
        // ⚠️ 条件必须全部加在 orderBy 之前 —— 排在 order by 后面的条件会被拼成一条废 SQL
        wrapper.orderByDesc(Notification::getCreatedAt).orderByDesc(Notification::getId);

        Page<Notification> source = notificationMapper.selectPage(
                new Page<>(PageLimits.page(page), PageLimits.size(size)), wrapper);

        Page<NotificationView> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        result.setRecords(toViews(source.getRecords()));
        return result;
    }

    @Override
    public long unreadCount(Long receiverId) {
        return notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getIsRead, Notification.UNREAD));
    }

    @Override
    public void markRead(Long notificationId, Long receiverId) {
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "通知不存在");
        }
        // 越权检查在 Service 层 —— 拦截器只回答"你是谁"
        if (receiverId == null || !notification.getReceiverId().equals(receiverId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能操作自己的通知");
        }
        if (Notification.READ == notification.getIsRead()) {
            return; // 幂等：重复点"已读"不该报错
        }
        notification.setIsRead(Notification.READ);
        notification.setReadAt(LocalDateTime.now());
        notificationMapper.updateById(notification);
    }

    @Override
    public int markAllRead(Long receiverId) {
        // 批量置位用 update(entity, wrapper)：只需要改两列，没必要逐条 select + update。
        // 这张表没有乐观锁字段（"标记已读"本身就是幂等的），所以不违反"别绕开 @Version"那条约定。
        Notification patch = new Notification();
        patch.setIsRead(Notification.READ);
        patch.setReadAt(LocalDateTime.now());
        int rows = notificationMapper.update(patch,
                new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getReceiverId, receiverId)
                        .eq(Notification::getIsRead, Notification.UNREAD));
        log.info("标记全部已读: receiverId={}, 影响 {} 条", receiverId, rows);
        return rows;
    }

    // ===================== 视图装配 =====================

    /** 包级可见，便于单测直接验证装配逻辑 */
    List<NotificationView> toViews(List<Notification> notifications) {
        if (notifications.isEmpty()) {
            return List.of();
        }
        List<Long> notificationIds = notifications.stream().map(Notification::getId).toList();
        List<NotificationMatch> allMatches = notificationMatchMapper.selectList(
                new LambdaQueryWrapper<NotificationMatch>()
                        .in(NotificationMatch::getNotificationId, notificationIds)
                        .orderByDesc(NotificationMatch::getScore));

        // 明细上带的是两个 id：招领那条（点进去看的）和失物那条（自己那条）。
        // 一次性把两边都捞回来，避免每条明细各查一次。
        Set<Long> itemIds = new HashSet<>();
        for (NotificationMatch m : allMatches) {
            itemIds.add(m.getFoundItemId());
            itemIds.add(m.getLostItemId());
        }
        Map<Long, Item> itemMap = itemService.mapByIds(itemIds);

        Map<Long, List<NotificationMatch>> grouped = allMatches.stream()
                .collect(Collectors.groupingBy(NotificationMatch::getNotificationId));

        List<NotificationView> views = new ArrayList<>(notifications.size());
        for (Notification n : notifications) {
            NotificationView v = new NotificationView();
            v.setId(n.getId());
            v.setContent(n.getContent());
            v.setSourceItemId(n.getSourceItemId());
            v.setIsRead(Notification.READ == n.getIsRead());
            v.setReadAt(n.getReadAt());
            v.setCreatedAt(n.getCreatedAt());

            List<NotificationMatch> rows = grouped.getOrDefault(n.getId(), List.of());
            List<NotificationMatchView> matchViews = rows.stream()
                    .map(m -> toMatchView(m, itemMap))
                    .sorted(Comparator.comparingInt(NotificationMatchView::getScore).reversed())
                    .toList();
            v.setMatches(matchViews);
            views.add(v);
        }
        return views;
    }

    private static NotificationMatchView toMatchView(NotificationMatch match, Map<Long, Item> itemMap) {
        NotificationMatchView mv = new NotificationMatchView();
        mv.setItemId(match.getFoundItemId());
        mv.setMyItemId(match.getLostItemId());
        mv.setScore(match.getScore());
        mv.setReason(match.getReason());

        Item mine = itemMap.get(match.getLostItemId());
        if (mine != null) {
            mv.setMyItemTitle(mine.getTitle());
        }
        // 招领那条用库里最新的字段回填 —— 通知文案是快照，但点进去之前
        // 让用户看到它当前的状态（比如已经被别人认领了）比一个过期标题更有用
        Item target = itemMap.get(match.getFoundItemId());
        if (target != null) {
            mv.setTitle(target.getTitle());
            mv.setCategory(target.getCategory());
            mv.setLocation(target.getLocation());
            mv.setImageUrl(target.getImageUrl());
            mv.setItemStatus(target.getStatus());
        }
        return mv;
    }
}
