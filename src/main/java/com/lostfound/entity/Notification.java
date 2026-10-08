package com.lostfound.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知（汇总）—— <b>一次发布事件 + 一个收件人 = 至多一条</b>。
 *
 * <p>注意是"汇总"：一条通知可以包含多条匹配结果，具体匹配到哪几对物品存在
 * {@link NotificationMatch} 里。所以这里只放"这条通知是关于谁的、说了什么、读没读"，
 * **不放单个物品 id** —— 那是明细的事。
 *
 * <p>去重靠 {@code uk_source_receiver(source_item_id, receiver_id)}：
 * 同一个发布事件被处理两次时，第二次的插入会撞上唯一键，直接忽略即可。
 * 这条键同时覆盖了「重试」「重复触发」两种重复来源。
 */
@Data
@TableName("notification")
public class Notification {

    /** 未读 */
    public static final int UNREAD = 0;
    /** 已读 */
    public static final int READ = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 收件人 —— 恒等于「失物那条的主人」。
     * <p>两个发布场景在这一点上是对称的：发失物时收件人是发布者本人，
     * 发招领时收件人是被匹配到的那条失物的主人。所以收件人始终是"丢了东西的人"。
     */
    private Long receiverId;

    /** 触发这次匹配的物品 id（＝本次发布的那条）。去重键的一半 */
    private Long sourceItemId;

    /**
     * 汇总文案，<b>生成时落库的快照</b>。
     * <p>为什么不读取时现拼：通知是"当时发生了什么"的记录。物品后来改了标题、
     * 被别人认领了，都不该改写历史通知说了什么。（点进去看到的仍是最新状态，这是对的。）
     */
    private String content;

    /** 0=未读 1=已读 */
    private Integer isRead;

    /** 已读时间；未读时为 null */
    private LocalDateTime readAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
