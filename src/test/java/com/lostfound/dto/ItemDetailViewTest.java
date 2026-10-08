package com.lostfound.dto;

import com.lostfound.entity.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ItemDetailView 的复制语义测试。
 *
 * <p>这里钉的是一条<b>很容易被写坏、而且写坏了很难发现</b>的规则：
 * 视图必须是从实体复制出来的新对象，绝不回头改实体本身 ——
 * 因为那个实体很可能就是 {@code itemDetail} 缓存里的那一个。
 * 一旦为了隐藏联系方式去 setContact(null)，缓存就被污染了，
 * 结果是发布者自己也看不到联系方式，而且直到 TTL 过期都恢复不了。
 */
class ItemDetailViewTest {

    private Item buildSource() {
        Item item = new Item();
        item.setId(1L);
        item.setUserId(100L);
        item.setTitle("拾到迷彩的索尼相机");
        item.setType(Item.TYPE_FOUND);
        item.setCategory("电子产品");
        item.setLocation("校医院门口");
        item.setDescription("镜头盖上贴着蓝色贴纸");
        item.setImageUrl("/uploads/a7m4.jpg");
        item.setContact("13800000000");
        item.setStatus(Item.STATUS_UNCLAIMED);
        item.setVersion(3);
        return item;
    }

    @Test
    @DisplayName("不可见时：contact 置空，但原始实体必须原封不动")
    void hidesContactWithoutTouchingSource() {
        Item source = buildSource();

        ItemDetailView view = ItemDetailView.from(source, false, null);

        assertNull(view.getContact());
        assertEquals(Boolean.FALSE, view.getContactVisible());
        assertEquals("13800000000", source.getContact(),
                "原实体的联系方式被改掉了 —— 那就是把缓存对象改坏了");
        assertNotSame(source, view, "视图必须是新对象，不能是原实体的别名");
    }

    @Test
    @DisplayName("可见时：contact 原样带出")
    void exposesContactWhenVisible() {
        ItemDetailView view = ItemDetailView.from(buildSource(), true, null);

        assertEquals("13800000000", view.getContact());
        assertEquals(Boolean.TRUE, view.getContactVisible());
    }

    @Test
    @DisplayName("物品字段整体复制，一个不缺")
    void copiesAllItemFields() {
        ItemDetailView view = ItemDetailView.from(buildSource(), true, null);

        assertEquals(1L, view.getId().longValue());
        assertEquals(100L, view.getUserId().longValue());
        assertEquals("拾到迷彩的索尼相机", view.getTitle());
        assertEquals(Item.TYPE_FOUND, view.getType());
        assertEquals("电子产品", view.getCategory());
        assertEquals("校医院门口", view.getLocation());
        assertEquals("镜头盖上贴着蓝色贴纸", view.getDescription());
        assertEquals("/uploads/a7m4.jpg", view.getImageUrl());
        assertEquals(Item.STATUS_UNCLAIMED, view.getStatus());
        assertEquals(3, view.getVersion().intValue());
    }

    @Test
    @DisplayName("带出当前用户的申请状态")
    void carriesMyClaimStatus() {
        ItemDetailView view = ItemDetailView.from(buildSource(), true, "PENDING");

        assertEquals("PENDING", view.getMyClaimStatus());
    }
}
