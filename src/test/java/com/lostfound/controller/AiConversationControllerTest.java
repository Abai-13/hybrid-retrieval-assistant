package com.lostfound.controller;

import com.lostfound.common.BusinessException;
import com.lostfound.common.ResultCode;
import com.lostfound.config.JwtUtil;
import com.lostfound.dto.AiQueryResponse;
import com.lostfound.dto.ConversationView;
import com.lostfound.dto.SessionResponse;
import com.lostfound.service.AiService;
import com.lostfound.service.ConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话接口的 HTTP 行为 —— 重点是<b>限流的边界</b>。
 *
 * <p>会话的读/建不花模型额度，所以从 AI 限流里排除了；而真正调模型的
 * {@code .../messages} 必须继续受限。这条边界很容易在改路径时被顺手破坏，
 * 一旦破坏，用户几分钟就能烧掉一堆模型调用 —— 所以两边都要有测试盯着。
 *
 * <p>限流阈值在这里压到 2，限流器是单例、计数按 userId 分桶，
 * 所以每个用例用<b>不同的用户</b>，彼此不串味。
 */
@WebMvcTest({AiConversationController.class, AiController.class})
@TestPropertySource(properties = "rate-limit.ai.max-requests=2")
class AiConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConversationService conversationService;

    /** 一起加载单轮接口，才能验证"原接口的限流规则没有被顺手改掉" */
    @MockBean
    private AiService aiService;

    /** JwtInterceptor 依赖它，@WebMvcTest 不会加载真实实现 */
    @MockBean
    private JwtUtil jwtUtil;

    private static final String TOKEN = "fake-token";

    private void loginAs(long userId) {
        when(jwtUtil.validate(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(TOKEN)).thenReturn(userId);
    }

    // ===================== 限流边界 =====================

    @Test
    @DisplayName("★ 会话读取不消耗 AI 限流额度：连续 10 次都不该被 429 挡住")
    void sessionReadIsNotRateLimited() throws Exception {
        loginAs(11L);
        when(conversationService.currentSession(11L))
                .thenReturn(new SessionResponse(new ConversationView(), false));

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/ai/session").header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Test
    @DisplayName("★ 发消息仍然限流：超过阈值后返回 429（它才是真正花模型额度的接口）")
    void sendingMessagesIsStillRateLimited() throws Exception {
        loginAs(12L);
        when(conversationService.sendMessage(eq(12L), eq(1L), any())).thenReturn(null);

        String body = "{\"requestId\":\"r1\",\"content\":\"我丢了一部手机\"}";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/ai/session/1/messages")
                            .header("Authorization", "Bearer " + TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.getBytes(StandardCharsets.UTF_8)))
                    .andExpect(status().isOk());
        }

        // 第 3 次越过阈值
        mockMvc.perform(post("/api/ai/session/1/messages")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    @DisplayName("★ 原单轮接口仍然受限：/api/ai/query 的限流规则一个字节都没变")
    void legacyQueryIsStillRateLimited() throws Exception {
        loginAs(13L);
        when(aiService.query(any())).thenReturn(new AiQueryResponse());
        String body = "{\"question\":\"黑色手机\"}";

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/ai/query")
                            .header("Authorization", "Bearer " + TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.getBytes(StandardCharsets.UTF_8)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        mockMvc.perform(post("/api/ai/query")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isTooManyRequests());
    }

    // ===================== 鉴权与错误码 =====================

    @Test
    @DisplayName("★ 放行限流 ≠ 放行鉴权：开会话仍然要登录")
    void creatingSessionRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/ai/session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★ 不带 token 读会话：由 Service 判 401（GET 被拦截器放行，但私人对话不能公开）")
    void readingSessionWithoutLoginIs401() throws Exception {
        when(conversationService.currentSession(null))
                .thenThrow(new BusinessException(ResultCode.UNAUTHORIZED, "请先登录后再使用找物助手"));

        mockMvc.perform(get("/api/ai/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("别人的会话：转成 403 —— 抹成 404 会把「越权尝试」和「已过期」混成同一件事")
    void otherUsersConversationIs403() throws Exception {
        loginAs(14L);
        when(conversationService.getSession(14L, 99L))
                .thenThrow(new BusinessException(ResultCode.FORBIDDEN, "无权访问该会话"));

        mockMvc.perform(get("/api/ai/session/99").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("请求体不合法：400，而不是 500 —— 客户端的问题不能报成服务端的锅")
    void blankContentIsBadRequest() throws Exception {
        loginAs(15L);

        mockMvc.perform(post("/api/ai/session/1/messages")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"content\":\"   \"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("新建会话：返回会话视图")
    void startNewSessionReturnsView() throws Exception {
        loginAs(16L);
        ConversationView view = new ConversationView();
        view.setId(5L);
        when(conversationService.startNewSession(16L)).thenReturn(view);

        mockMvc.perform(post("/api/ai/session").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(5));
    }

    @Test
    @DisplayName("没有可用会话时返回 conversation=null，而不是编一段空会话出来")
    void currentSessionMayBeNull() throws Exception {
        loginAs(17L);
        when(conversationService.currentSession(17L)).thenReturn(new SessionResponse(null, true));

        mockMvc.perform(get("/api/ai/session").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.conversation").doesNotExist())
                .andExpect(jsonPath("$.data.previousExpired").value(true));
    }
}
