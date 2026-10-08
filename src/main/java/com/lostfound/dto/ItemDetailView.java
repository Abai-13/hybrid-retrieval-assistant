package com.lostfound.dto;

import com.lostfound.entity.Item;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物品详情返回体 —— 物品详情页看到的东西，按「谁在看」裁剪过。
 *
 * <p><b>为什么不能直接返回 {@link Item}</b>：物品详情是带缓存的
 * （{@code @Cacheable("itemDetail")}），缓存里存的是<b>实体对象本身</b>。
 * 如果为了隐藏联系方式去调 {@code item.setContact(null)}，改的是缓存里的那个对象 ——
 * 发布者本人之后也再也看不到联系方式了，而且直到 TTL 过期都恢复不了。
 * 所以这里是<b>复制一份再裁剪</b>，原始实体一个字都不动。
 *
 * <p>字段与 {@link Item} 一一对应，是为了不给前端制造第二个响应结构；
 * 多出来的是两个「本次访问者视角」的字段：
 * <ul>
 *   <li>{@code contactVisible} —— 联系方式是"被隐藏了"还是"发布者根本没填"，
 *       前端要能分清，否则只能都显示成"未填写"，把规则说反；</li>
 *   <li>{@code myClaimStatus} —— 当前用户在这条物品上的活跃申请状态，
 *       有了它详情页才能显示「审核中」，而不是继续给人一个「我要认领」按钮。</li>
 * </ul>
 */
@Data
public class ItemDetailView {

    private Long id;

    private Long userId;

    private String title;

    /** LOST / FOUND */
    private String type;

    private String category;

    private String location;

    private String description;

    private String imageUrl;

    /** 只有 {@link #contactVisible} 为 true 时才有值 */
    private String contact;

    /** UNCLAIMED / CLAIMING / CLAIMED */
    private String status;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 当前访问者是否有权看到联系方式 */
    private Boolean contactVisible;

    /** 当前用户对该物品的活跃申请状态（PENDING / APPROVED），没有则为 null */
    private String myClaimStatus;

    /**
     * 从实体复制出一个带视角裁剪的视图。
     *
     * @param contactVisible 是否对当前访问者展示联系方式
     * @param myClaimStatus  当前用户的活跃申请状态，可为 null
     */
    public static ItemDetailView from(Item item, boolean contactVisible, String myClaimStatus) {
        ItemDetailView view = new ItemDetailView();
        view.setId(item.getId());
        view.setUserId(item.getUserId());
        view.setTitle(item.getTitle());
        view.setType(item.getType());
        view.setCategory(item.getCategory());
        view.setLocation(item.getLocation());
        view.setDescription(item.getDescription());
        view.setImageUrl(item.getImageUrl());
        view.setStatus(item.getStatus());
        view.setVersion(item.getVersion());
        view.setCreatedAt(item.getCreatedAt());
        view.setUpdatedAt(item.getUpdatedAt());

        view.setContactVisible(contactVisible);
        // 复制后裁剪 —— 见类注释，绝不能回头改 item
        view.setContact(contactVisible ? item.getContact() : null);
        view.setMyClaimStatus(myClaimStatus);
        return view;
    }
}
