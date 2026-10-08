package com.lostfound.dto;

import lombok.Data;

/**
 * 通知里一条匹配结果的展示视图。
 *
 * <p>收件人永远是"丢了东西的人"，所以对他而言**需要点进去看的是对方那条招领**，
 * 因此 {@link #itemId} 指的是招领物品；{@link #myItemId} 是他自己那条失物，
 * 用来把话说完整（「你的《X》可能匹配到《Y》」）。
 */
@Data
public class NotificationMatchView {

    /** 招领物品 id —— 前端点这条时跳这里 */
    private Long itemId;

    /** 自己的失物 id —— 让这条明细能自我说明，不用再去猜"我的哪件" */
    private Long myItemId;

    /** 自己的失物标题 */
    private String myItemTitle;

    /** 匹配分数（模型自报 0~100） */
    private Integer score;

    /** 匹配理由 */
    private String reason;

    // ---- 招领物品的展示信息（本地回填，不用模型给的）----

    private String title;

    private String category;

    private String location;

    private String imageUrl;

    /** 物品当前状态 —— 通知是快照，但点进去前让用户知道它是不是已经被别人认领了 */
    private String itemStatus;
}
