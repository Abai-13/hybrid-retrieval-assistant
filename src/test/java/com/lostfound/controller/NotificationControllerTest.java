package com.lostfound.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.config.JwtUtil;
import com.lostfound.dto.NotificationMatchView;
import com.lostfound.dto.NotificationView;
import com.lostfound.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NotificationController 接口测试（MockMvc）。
 *
 * <p>重点和认领模块同一个套路：
 * ① 两个 PUT 是写操作 → 无 token 被拦截器挡下（HTTP 401）；
 * ② 两个 GET 会被拦截器放行 → <b>必须由 Controller 自己返回未授权</b>，
 *    否则"我的通知"就变成公开列表，任何人都能看到别人收到了什么。
 */
@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService notificationService;

    /** JwtInterceptor 依赖 JwtUtil，@WebMvcTest 不会加载它 */
    @MockBean
    private JwtUtil jwtUtil;

    private static final String TOKEN = "fake-token";

    private void givenLoggedIn(Long userId) {
        when(jwtUtil.validate(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(TOKEN)).thenReturn(userId);
    }

    private NotificationView view() {
        NotificationMatchView match = new NotificationMatchView();
        match.setItemId(11L);
        match.setMyItemId(7L);
        match.setScore(95);
        match.setReason("颜色和型号都对得上");
        match.setTitle("招领物品11");

        NotificationView v = new NotificationView();
        v.setId(1L);
        v.setContent("系统为你的失物《黑色华为手机》找到了 1 条可能匹配的招领信息，点击查看。");
        v.setIsRead(false);
        v.setMatches(List.of(match));
        return v;
    }

    // ==================== 读（GET 放行，但必须自己判身份） ====================

    @Test
    @DisplayName("我的通知：不带 token → 未授权（GET 放行不等于公开）")
    void my_withoutToken_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/notification/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("我的通知：带 token → 200，内联匹配明细")
    void my_withToken_shouldReturnPage() throws Exception {
        givenLoggedIn(100L);
        Page<NotificationView> page = new Page<>(1, 10);
        page.setRecords(List.of(view()));
        page.setTotal(1);
        when(notificationService.pageByReceiver(eq(100L), anyInt(), anyInt(), eq(false))).thenReturn(page);

        mockMvc.perform(get("/api/notification/my").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].content").value(org.hamcrest.Matchers.containsString("黑色华为手机")))
                .andExpect(jsonPath("$.data.records[0].isRead").value(false))
                .andExpect(jsonPath("$.data.records[0].matches[0].itemId").value(11))
                .andExpect(jsonPath("$.data.records[0].matches[0].score").value(95))
                .andExpect(jsonPath("$.data.records[0].matches[0].reason").value("颜色和型号都对得上"));
    }

    @Test
    @DisplayName("未读数：不带 token → 未授权")
    void unreadCount_withoutToken_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/notification/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("未读数：带 token → 200，返回自己的未读条数")
    void unreadCount_withToken() throws Exception {
        givenLoggedIn(100L);
        when(notificationService.unreadCount(100L)).thenReturn(3L);

        mockMvc.perform(get("/api/notification/unread-count").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(3));
    }

    // ==================== 写（PUT 必须登录） ====================

    @Test
    @DisplayName("标记单条已读：不带 token → 401（拦截器拦住写操作）")
    void markRead_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(put("/api/notification/1/read"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("标记单条已读：带 token → 200，且把当前用户传给 Service 做越权判断")
    void markRead_withToken() throws Exception {
        givenLoggedIn(100L);

        mockMvc.perform(put("/api/notification/1/read").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationService).markRead(eq(1L), eq(100L));
    }

    @Test
    @DisplayName("全部已读：不带 token → 401")
    void markAllRead_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(put("/api/notification/read-all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("全部已读：带 token → 200，返回影响条数")
    void markAllRead_withToken() throws Exception {
        givenLoggedIn(100L);
        when(notificationService.markAllRead(100L)).thenReturn(2);

        mockMvc.perform(put("/api/notification/read-all").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(2));
    }

    @Test
    @DisplayName("路由：PUT /{id}/read 与 PUT /read-all 不会互相抢")
    void routesDoNotCollide() throws Exception {
        givenLoggedIn(100L);
        when(notificationService.markAllRead(100L)).thenReturn(0);

        mockMvc.perform(put("/api/notification/read-all").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());

        verify(notificationService, org.mockito.Mockito.never()).markRead(anyLong(), anyLong());
    }
}
