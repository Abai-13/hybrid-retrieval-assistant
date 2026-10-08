package com.lostfound.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lostfound.config.JwtUtil;
import com.lostfound.dto.ClaimCompleteResult;
import com.lostfound.dto.ClaimDetailView;
import com.lostfound.dto.ClaimTimelineItem;
import com.lostfound.dto.MyClaimView;
import com.lostfound.entity.ClaimApplication;
import com.lostfound.service.ClaimService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ClaimController 接口测试（MockMvc）。
 * <p>
 * 重点验证认领接口的访问控制，这是本模块最容易出错的地方：
 * ① 提交申请是 POST → 无 token 直接 401（拦截器挡住）；
 * ② 两个查询是 GET → 拦截器会放行，所以<b>必须由 Controller 自己返回未授权</b>，
 *    否则就会把「我收到的申请」当成公开列表，把别人提交的特征证明漏出去。
 */
@WebMvcTest(ClaimController.class)
class ClaimControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClaimService claimService;

    /** JwtInterceptor 依赖 JwtUtil，@WebMvcTest 不会加载它，必须 @MockBean 补上 */
    @MockBean
    private JwtUtil jwtUtil;

    private static final String TOKEN = "fake-token";

    private static final String VALID_BODY = """
            {"itemId":1,"proofText":"钥匙扣上挂着一只灰色小恐龙挂件，背面有齿痕"}
            """;

    private ClaimApplication buildClaim() {
        ClaimApplication claim = new ClaimApplication();
        claim.setId(9L);
        claim.setItemId(1L);
        claim.setApplicantId(100L);
        claim.setOwnerId(200L);
        claim.setStatus(ClaimApplication.STATUS_PENDING);
        return claim;
    }

    private void givenLoggedIn(Long userId) {
        when(jwtUtil.validate(TOKEN)).thenReturn(true);
        when(jwtUtil.getUserId(TOKEN)).thenReturn(userId);
    }

    // ==================== 提交申请（POST 必须登录） ====================

    @Test
    @DisplayName("提交申请：不带 token → 401（JWT 拦截器拦住写操作）")
    void apply_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("提交申请：带 token → 200，返回申请ID与状态")
    void apply_withToken_shouldReturn200() throws Exception {
        givenLoggedIn(100L);
        when(claimService.apply(anyLong(), any())).thenReturn(buildClaim());

        mockMvc.perform(post("/api/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("提交申请：特征证明太短 → 400（@Size 校验拦住，不落到 Service）")
    void apply_tooShortProof_shouldReturn400() throws Exception {
        givenLoggedIn(100L);

        mockMvc.perform(post("/api/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":1,\"proofText\":\"是我的\"}".getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ==================== 查询（GET 放行，但必须自己判身份） ====================

    @Test
    @DisplayName("我的申请：不带 token → 未授权（GET 放行不等于公开）")
    void myClaims_withoutToken_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/claim/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("我的申请：带 token → 200，返回分页结构")
    void myClaims_withToken_shouldReturnPage() throws Exception {
        givenLoggedIn(100L);
        Page<MyClaimView> page = new Page<>(1, 10);
        page.setRecords(List.of(new MyClaimView()));
        page.setTotal(1);
        when(claimService.pageByApplicant(anyLong(), any())).thenReturn(page);

        mockMvc.perform(get("/api/claim/my")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("收到的申请：不带 token → 未授权（这里带着别人的特征证明，绝不能公开）")
    void receivedClaims_withoutToken_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/claim/received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("收到的申请：带 token → 200")
    void receivedClaims_withToken_shouldReturnPage() throws Exception {
        givenLoggedIn(200L);
        Page<com.lostfound.dto.ReceivedClaimView> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(claimService.pageByOwner(anyLong(), any())).thenReturn(page);

        mockMvc.perform(get("/api/claim/received")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ==================== 审核（POST，必须登录） ====================

    @Test
    @DisplayName("审核通过：不带 token → 401")
    void approve_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/claim/9/approve"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("审核通过：带 token → 200，返回新状态 APPROVED")
    void approve_withToken_shouldReturn200() throws Exception {
        givenLoggedIn(200L);

        mockMvc.perform(post("/api/claim/9/approve")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        verify(claimService).approve(eq(9L), eq(200L));
    }

    @Test
    @DisplayName("驳回：不带 token → 401")
    void reject_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/claim/9/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"特征不符\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("驳回：带 token + 理由 → 200，返回新状态 REJECTED")
    void reject_withToken_shouldReturn200() throws Exception {
        givenLoggedIn(200L);

        mockMvc.perform(post("/api/claim/9/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"描述的特征和实物对不上\"}".getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        verify(claimService).reject(eq(9L), eq(200L), eq("描述的特征和实物对不上"));
    }

    @Test
    @DisplayName("驳回：不填理由 → 400（理由必填，申请人要知道原因）")
    void reject_blankReason_shouldReturn400() throws Exception {
        givenLoggedIn(200L);

        mockMvc.perform(post("/api/claim/9/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}".getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ==================== 确认收到 ====================

    @Test
    @DisplayName("确认收到：不带 token → 401")
    void complete_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/claim/9/complete"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("确认收到：带 token → 200，返回物品已认领与自动关闭条数")
    void complete_withToken_shouldReturn200() throws Exception {
        givenLoggedIn(100L);
        when(claimService.complete(eq(9L), eq(100L)))
                .thenReturn(new ClaimCompleteResult(9L, "COMPLETED", "CLAIMED", 2));

        mockMvc.perform(post("/api/claim/9/complete")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.itemStatus").value("CLAIMED"))
                .andExpect(jsonPath("$.data.closedOthers").value(2))
                .andExpect(jsonPath("$.message").value("已确认收到，另有 2 条申请已自动关闭"));
    }

    @Test
    @DisplayName("确认收到：没有其他申请时给一句朴素提示")
    void complete_withoutAutoClosed_shouldSaySimply() throws Exception {
        givenLoggedIn(100L);
        when(claimService.complete(eq(9L), eq(100L)))
                .thenReturn(new ClaimCompleteResult(9L, "COMPLETED", "CLAIMED", 0));

        mockMvc.perform(post("/api/claim/9/complete")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.message").value("已确认收到，物品标记为已认领"));
    }

    // ==================== 撤回 ====================

    @Test
    @DisplayName("撤回：不带 token → 401")
    void cancel_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/claim/9/cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("撤回：带 token → 200，返回 CANCELLED")
    void cancel_withToken_shouldReturn200() throws Exception {
        givenLoggedIn(100L);

        mockMvc.perform(post("/api/claim/9/cancel")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verify(claimService).cancel(eq(9L), eq(100L));
    }

    // ==================== 详情（注意不能抢走 /my 与 /received） ====================

    @Test
    @DisplayName("详情：带 token → 200，返回时间线数组")
    void detail_withToken_shouldReturnTimeline() throws Exception {
        givenLoggedIn(100L);
        ClaimDetailView view = new ClaimDetailView();
        view.setClaimId(9L);
        view.setStatus("APPROVED");
        view.setTimeline(List.of(new ClaimTimelineItem(null, "PENDING", 100L, "APPLICANT", null, null)));
        when(claimService.getDetail(eq(9L), eq(100L))).thenReturn(view);

        mockMvc.perform(get("/api/claim/9").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.timeline").isArray())
                .andExpect(jsonPath("$.data.timeline[0].toStatus").value("PENDING"));
    }

    @Test
    @DisplayName("详情：不带 token 也要走到 Service，由它判 403（不能因为 GET 放行就公开）")
    void detail_withoutToken_stillGuardedByService() throws Exception {
        when(claimService.getDetail(eq(9L), any()))
                .thenThrow(new com.lostfound.common.BusinessException(
                        com.lostfound.common.ResultCode.FORBIDDEN, "只有申请人和物品发布者可以查看这条申请"));

        mockMvc.perform(get("/api/claim/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("路由：/my 与 /received 不会被 /{id} 抢走")
    void literalPaths_shouldNotBeSwallowedByPathVariable() throws Exception {
        givenLoggedIn(100L);
        Page<MyClaimView> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(claimService.pageByApplicant(anyLong(), any())).thenReturn(page);

        mockMvc.perform(get("/api/claim/my").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());

        verify(claimService, never()).getDetail(anyLong(), any());
    }
}
