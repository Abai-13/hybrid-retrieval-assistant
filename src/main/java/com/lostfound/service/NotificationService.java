package com.lostfound.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.dto.NotificationView;
import com.lostfound.entity.Notification;
import com.lostfound.entity.NotificationMatch;

import java.util.List;

/**
 * 站内通知 —— 生成与读取。
 */
public interface NotificationService {

    /**
     * 物化一条汇总通知及其匹配明细。
     *
     * <p>整体在一个事务里：主表插进去了、明细插一半失败，用户点开通知会看到空列表 ——
     * 那比"没收到通知"更糟（他会以为系统坏了）。
     *
     * @return {@code false} 表示这个「发布事件 + 收件人」已经通知过了（唯一键挡住），本次什么都没写
     */
    boolean createWithMatches(Notification notification, List<NotificationMatch> matches);

    /** 我的通知（分页），每条内联它包含的匹配明细 */
    Page<NotificationView> pageByReceiver(Long receiverId, int page, int size, boolean unreadOnly);

    /** 未读条数 —— 给右上角角标用 */
    long unreadCount(Long receiverId);

    /** 标记单条已读；仅本人可操作，重复标记是幂等的 */
    void markRead(Long notificationId, Long receiverId);

    /**
     * 全部标记已读。
     * @return 本次实际影响的条数（已经是已读的不计入）
     */
    int markAllRead(Long receiverId);
}
