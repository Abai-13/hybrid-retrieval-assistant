package com.lostfound.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知里的一条匹配明细 —— 「你的哪件东西 ↔ 对方的哪件东西，像到什么程度，为什么像」。
 *
 * <p><b>为什么必须单独一张表</b>：一条汇总通知要带 N 个匹配，每个都要有
 * 失物 id + 招领 id + 分数 + 匹配理由。塞进主表只能靠 JSON 列或逗号拼接 ——
 * 前者项目里没有先例，后者没法校验也没法查询。1:N 就是这段关系本身的形状。
 *
 * <p>两个 id 都存（而不是只存"对方那条"）：这样不管这条通知是
 * 「发失物时匹配到招领」还是「发招领时匹配到失物」产生的，
 * 「你的哪件 ↔ 对方的哪件」永远是明确的，前端不用靠猜。
 *
 * <p>⚠️ 每一行都指向一个**具体的 itemId**。这不是"同标题就算同一条"——
 * 那是评测口径的问题，和这里无关。
 */
@Data
@TableName("notification_match")
public class NotificationMatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long notificationId;

    /** 失物 id */
    private Long lostItemId;

    /** 招领物品 id */
    private Long foundItemId;

    /** AI 匹配分数（0~100，模型自报，未校准） */
    private Integer score;

    /** AI 给出的匹配理由 */
    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
