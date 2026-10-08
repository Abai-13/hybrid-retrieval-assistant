package com.lostfound.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.config.JwtUtil;
import com.lostfound.entity.Item;
import com.lostfound.service.AutoMatchService;
import com.lostfound.service.ClaimService;
import com.lostfound.service.FileService;
import com.lostfound.service.ItemService;
import com.lostfound.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ItemController 接口测试（MockMvc）。
 * <p>
 * 和 UserControllerTest 的区别，重点验证两件事：
 * ① JWT 拦截器真的在拦截 —— 写操作无 token 返回 401，GET 无 token 放行；
 * ② multipart/form-data —— 发布接口用 @RequestPart，测试要用 multipart() 而不是 post()。
 */
@WebMvcTest(ItemController.class)
class ItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemService itemService;

    @MockBean
    private UserService userService;

    @MockBean
    private FileService fileService;

    /** 详情接口要靠它判断「联系方式能不能给这个人看」 */
    @MockBean
    private ClaimService claimService;

    /** 发布成功后 Controller 会触发它做后台自动匹配（异步，mock 掉不产生副作用） */
    @MockBean
    private AutoMatchService autoMatchService;

    /** JwtInterceptor 依赖 JwtUtil，@WebMvcTest 不会加载它，必须 @MockBean 补上 */
    @MockBean
    private JwtUtil jwtUtil;

    private static final String TOKEN = "fake-token";

    private Item buildItem(Long id, Long userId, String status) {
        Item item = new Item();
        item.setId(id);
        item.setUserId(userId);
        item.setTitle("校园卡");
        item.setType("LOST");
        item.setCategory("证件");
        item.setStatus(status);
        return item;
    }

    private Item buildFoundItemWithContact(Long id, Long userId) {
        Item item = buildItem(id, userId, Item.STATUS_UNCLAIMED);
        item.setType(Item.TYPE_FOUND);
        item.setContact("13800000000");
        return item;
    }

    // ==================== 拦截器：写操作必须带 token ====================

    @Test
    @DisplayName("发布：不带 token → 401（JWT 拦截器拦截写操作）")
    void publish_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(multipart("/api/item")
                        .file(new MockMultipartFile("data", "", "application/json",
                                "{\"title\":\"校园卡\",\"type\":\"LOST\",\"category\":\"证件\"}".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized());   // HTTP 401
    }

    @Test
    @DisplayName("发布：带合法 token + multipart → 200，发布成功")
    void publish_withToken_shouldReturn200() throws Exception {
        // 拦截器会先 validate 再 getUserId，把这两个方法 stub 成"合法"
        when(jwtUtil.validate(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(TOKEN)).thenReturn(1L);
        // Service 层换成假的：上传返回一个 URL，发布返回一个物品
        when(fileService.upload(any())).thenReturn("/uploads/card.jpg");
        when(itemService.publish(any(), any(), any())).thenReturn(buildItem(10L, 1L, "UNCLAIMED"));

        // multipart 请求：data 是 JSON 字符串部分，image 是文件部分
        MockMultipartFile dataPart = new MockMultipartFile("data", "", "application/json",
                "{\"title\":\"校园卡\",\"type\":\"LOST\",\"category\":\"证件\",\"location\":\"图书馆\"}".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile imagePart = new MockMultipartFile("image", "card.jpg", "image/jpeg",
                "fake-image-bytes".getBytes());

        mockMvc.perform(multipart("/api/item")
                        .file(dataPart)
                        .file(imagePart)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("发布成功"))
                .andExpect(jsonPath("$.data.title").value("校园卡"));
    }

    @Test
    @DisplayName("改状态：不带 token → 401（PUT 也属于写操作）")
    void updateStatus_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(put("/api/item/1/status").param("status", "CLAIMED"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("改状态：带合法 token → 200，更新成功")
    void updateStatus_withToken_shouldReturn200() throws Exception {
        when(jwtUtil.validate(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(TOKEN)).thenReturn(1L);
        // updateStatus 是 void 方法，Mockito 默认什么都不做，无需 stub

        mockMvc.perform(put("/api/item/1/status")
                        .param("status", "CLAIMED")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("更新成功"));
    }

    // ==================== 拦截器：GET 放行（无需 token） ====================

    @Test
    @DisplayName("列表：GET 不带 token → 200（查询类接口公开访问）")
    void list_getWithoutToken_shouldReturn200() throws Exception {
        Page<Item> page = new Page<>(1, 10);
        page.setRecords(List.of(buildItem(1L, 1L, "UNCLAIMED")));
        page.setTotal(1);
        when(itemService.page(any())).thenReturn(page);

        mockMvc.perform(get("/api/item").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("详情：GET 不带 token → 200，返回物品 + 发布者昵称")
    void detail_getWithoutToken_shouldReturn200() throws Exception {
        when(itemService.getById(1L)).thenReturn(buildItem(1L, 100L, "UNCLAIMED"));
        when(userService.getNicknameById(any())).thenReturn("小白");

        mockMvc.perform(get("/api/item/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.item.title").value("校园卡"))
                .andExpect(jsonPath("$.data.publisherName").value("小白"));
    }

    // ==================== 详情：联系方式按身份收敛 ====================

    @Test
    @DisplayName("详情：游客看不到联系方式（contact 置空 + contactVisible=false）")
    void detail_guestCannotSeeContact() throws Exception {
        when(itemService.getById(1L)).thenReturn(buildFoundItemWithContact(1L, 100L));
        when(userService.getNicknameById(any())).thenReturn("小白");

        mockMvc.perform(get("/api/item/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.title").value("校园卡"))
                .andExpect(jsonPath("$.data.item.contactVisible").value(false))
                .andExpect(jsonPath("$.data.item.contact").isEmpty());
    }

    @Test
    @DisplayName("详情：发布者本人看得到联系方式")
    void detail_ownerCanSeeContact() throws Exception {
        when(jwtUtil.validate(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(TOKEN)).thenReturn(100L);
        when(itemService.getById(1L)).thenReturn(buildFoundItemWithContact(1L, 100L));
        when(userService.getNicknameById(any())).thenReturn("小白");

        mockMvc.perform(get("/api/item/1").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.contactVisible").value(true))
                .andExpect(jsonPath("$.data.item.contact").value("13800000000"))
                .andExpect(jsonPath("$.data.item.myClaimStatus").isEmpty());
    }

    @Test
    @DisplayName("详情：审核已通过的申请人看得到联系方式，并带出自己的申请状态")
    void detail_approvedApplicantCanSeeContact() throws Exception {
        when(jwtUtil.validate(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(TOKEN)).thenReturn(200L);
        when(itemService.getById(1L)).thenReturn(buildFoundItemWithContact(1L, 100L));
        when(userService.getNicknameById(any())).thenReturn("小白");
        when(claimService.canSeeContact(anyLong(), any())).thenReturn(true);
        when(claimService.activeClaimStatus(anyLong(), any())).thenReturn("APPROVED");

        mockMvc.perform(get("/api/item/1").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.contactVisible").value(true))
                .andExpect(jsonPath("$.data.item.contact").value("13800000000"))
                .andExpect(jsonPath("$.data.item.myClaimStatus").value("APPROVED"));
    }

    @Test
    @DisplayName("详情：审核中的申请人看不到联系方式，但能看到自己的申请状态")
    void detail_pendingApplicantCannotSeeContactYet() throws Exception {
        when(jwtUtil.validate(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(TOKEN)).thenReturn(300L);
        when(itemService.getById(1L)).thenReturn(buildFoundItemWithContact(1L, 100L));
        when(userService.getNicknameById(any())).thenReturn("小白");
        when(claimService.canSeeContact(anyLong(), any())).thenReturn(false);
        when(claimService.activeClaimStatus(anyLong(), any())).thenReturn("PENDING");

        mockMvc.perform(get("/api/item/1").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.contactVisible").value(false))
                .andExpect(jsonPath("$.data.item.contact").isEmpty())
                .andExpect(jsonPath("$.data.item.myClaimStatus").value("PENDING"));
    }
}
