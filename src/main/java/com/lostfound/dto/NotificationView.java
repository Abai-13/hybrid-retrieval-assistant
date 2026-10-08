package com.lostfound.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知列表项 —— 汇总文案 + 它包含的全部匹配明细。
 *
 * <p>明细直接内联返回（一条通知最多 5 条匹配，不需要再分页）：
 * 通知列表天生就是"扫一眼"的场景，让前端再为每条通知发一次请求毫无必要。
 */
@Data
public class NotificationView {

    private Long id;

    /** 汇总文案快照 */
    private String content;

    /** 触发这次匹配的物品 id（本次发布的那条） */
    private Long sourceItemId;

    /** true = 已读 */
    private Boolean isRead;

    private LocalDateTime readAt;

    private LocalDateTime createdAt;

    /** 这条通知里的具体匹配结果，按分数降序 */
    private List<NotificationMatchView> matches;
}
