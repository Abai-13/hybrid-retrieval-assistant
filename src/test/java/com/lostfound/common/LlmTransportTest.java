package com.lostfound.common;

import cn.hutool.json.JSONObject;
import com.lostfound.config.LlmConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mockingDetails;

/**
 * {@link LlmTransport} 的整轮预算分配测试。
 *
 * <p>守着的是这条契约：<b>一次调用的最坏耗时（连接 + 读取）必须落在剩余预算之内</b>。
 * 只测读超时是不够的 —— 连接超时是固定值，它正是最容易被漏掉的那一半：
 * 剩余 4 秒时还留 3 秒去连接，再花几秒读，整轮就超了，
 * 而"超了"的表现只是用户多等一会儿，不会报任何错。
 */
@ExtendWith(MockitoExtension.class)
class LlmTransportTest {

    @Mock
    private RestTemplate restTemplate;

    private LlmConfig config;

    @BeforeEach
    void setUp() {
        config = new LlmConfig();
        config.setApiUrl("https://example.invalid/v1/chat/completions");
        config.setApiKey("test-key");
        config.setModel("test-model");
        config.setConnectTimeout(3);
        config.setReadTimeout(30);
        config.setMaxTokens(500);
    }

    // ===================== 超时分配 =====================

    @Test
    @DisplayName("预算充裕时：连接与读取都用配置值，不被无故缩短")
    void plentyOfBudget_usesConfiguredTimeouts() {
        LlmTransport.Timeouts timeouts = LlmTransport.timeoutsFor(now() + 90_000, config);

        assertEquals(3, timeouts.connectSeconds());
        assertEquals(30, timeouts.readSeconds());
    }

    @Test
    @DisplayName("★ 预算紧张时：【连接和读取都要缩】，两者之和不超过剩余时间")
    void tightBudget_shrinksBothTimeouts() {
        long remaining = 8_000;
        LlmTransport.Timeouts timeouts = LlmTransport.timeoutsFor(now() + remaining, config);

        assertTrue(timeouts.connectSeconds() < 3, "连接超时也要缩，不能固定 3 秒");
        // 最坏耗时 = 连接 + 读取，必须装得进"剩余 - 预留量"
        long worstCaseMs = (timeouts.connectSeconds() + timeouts.readSeconds()) * 1000L;
        assertTrue(worstCaseMs <= remaining,
                "最坏耗时 " + worstCaseMs + "ms 超过了剩余 " + remaining + "ms");
    }

    @Test
    @DisplayName("剩余时间极短时：两个超时仍然 ≥1 秒，绝不落到 0")
    void tinyBudget_neverGoesBelowOneSecond() {
        LlmTransport.Timeouts timeouts = LlmTransport.timeoutsFor(now() + 1_000, config);

        // 0 会被 HttpURLConnection 当成"无限等待" —— 比超时更糟，它会把预算彻底吃掉
        assertTrue(timeouts.connectSeconds() >= 1);
        assertTrue(timeouts.readSeconds() >= 1);
    }

    @Test
    @DisplayName("截止时刻已经过去：两个超时仍然合法（由调用方的 MIN_ATTEMPT_MS 拦住这次请求）")
    void expiredDeadline_stillProducesLegalTimeouts() {
        LlmTransport.Timeouts timeouts = LlmTransport.timeoutsFor(now() - 5_000, config);

        assertTrue(timeouts.connectSeconds() >= 1);
        assertTrue(timeouts.readSeconds() >= 1);
    }

    @Test
    @DisplayName("★ 算出来的两个超时真的被用来建 RestTemplate（不是只算不用）")
    void computedTimeoutsAreActuallyApplied() {
        LlmConfig spyConfig = spy(config);
        RestTemplate bounded = mock(RestTemplate.class);
        when(spyConfig.restTemplateWithTimeouts(anyInt(), anyInt())).thenReturn(bounded);
        when(bounded.postForEntity(any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                        "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}", HttpStatus.OK));

        // 预算充裕 → 用配置值（3 秒连接 / 30 秒读取）。选充裕的预算是因为
        // 这两个数不随毫秒级的时间流逝变化，断言不会闪断
        LlmTransport.call(restTemplate, spyConfig, new JSONObject(), 0, now() + 90_000);

        verify(spyConfig).restTemplateWithTimeouts(3, 30);
    }

    @Test
    @DisplayName("★ 预算紧张时，真正传给 RestTemplate 的两个超时之和装得进剩余时间")
    void tightBudgetTimeoutsAreWithinRemaining() {
        LlmConfig spyConfig = spy(config);
        RestTemplate bounded = mock(RestTemplate.class);
        when(spyConfig.restTemplateWithTimeouts(anyInt(), anyInt())).thenReturn(bounded);
        when(bounded.postForEntity(any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                        "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}", HttpStatus.OK));

        long remaining = 8_000;
        LlmTransport.call(restTemplate, spyConfig, new JSONObject(), 0, now() + remaining);

        ArgumentCaptor<Integer> connect = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> read = ArgumentCaptor.forClass(Integer.class);
        verify(spyConfig).restTemplateWithTimeouts(connect.capture(), read.capture());

        long worstCaseMs = (connect.getValue() + read.getValue()) * 1000L;
        assertTrue(worstCaseMs <= remaining,
                "最坏耗时 " + worstCaseMs + "ms 超过了剩余 " + remaining + "ms");
        assertTrue(connect.getValue() < 3, "连接超时也被裁短了，不是雷打不动的 3 秒");
    }

    @Test
    @DisplayName("两个超时都真的设进了请求工厂（否则算得再对也不生效）")
    void timeoutsLandOnRequestFactory() {
        SimpleClientHttpRequestFactory factory = (SimpleClientHttpRequestFactory)
                config.restTemplateWithTimeouts(2, 7).getRequestFactory();

        // Spring 6 取消了这两个 getter，直接读字段 —— 断言的是"设进去了"，
        // 不是"Spring 会怎么处理它"
        assertEquals(2_000, ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(7_000, ReflectionTestUtils.getField(factory, "readTimeout"));
    }

    // ===================== 该不该发 / 该不该重试 =====================

    @Test
    @DisplayName("★ 预算已经用完：不再发起请求（返回 null，且一次 HTTP 调用都不发出）")
    void exhaustedBudget_doesNotCallHttpAtAll() {
        LlmTransport.Result result = LlmTransport.call(
                restTemplate, config, new JSONObject(), 2, now() - 1);

        assertNull(result);
        // 用 mockingDetails 数调用次数，而不是 verify(never())：
        // postForEntity 是可变参数方法，用 eq(String.class) 这种按位置匹配的写法
        // 在这种方法上会因为参数个数对不上而"永远匹配不到" —— 那样 never() 就会
        // 无条件通过，把这条断言变成一句空话（踩过）
        assertTrue(mockingDetails(restTemplate).getInvocations().isEmpty(),
                "预算已经用完，不该再向模型发出任何请求");
    }

    @Test
    @DisplayName("剩余时间不够重试（退避 + 一次最短尝试）时：不再重试，直接返回失败")
    void insufficientBudgetForRetry_doesNotRetry() {
        // 退避 500ms + 最短尝试 2000ms ≈ 2500ms，还剩 1 秒显然不够
        assertFalse(LlmTransport.retryFitsBudget(now() + 1_000, 500));
        assertTrue(LlmTransport.retryFitsBudget(now() + 30_000, 500));
    }

    @Test
    @DisplayName("老的单轮链路（不传预算）：重试不受任何限制，行为与改造前一致")
    void noDeadline_neverBlocksRetry() {
        assertTrue(LlmTransport.retryFitsBudget(LlmTransport.NO_DEADLINE, 500));
        assertTrue(LlmTransport.retryFitsBudget(LlmTransport.NO_DEADLINE, 100_000));
    }

    @Test
    @DisplayName("没有预算限制时用注入的那个 RestTemplate 真发一次请求（老链路逐字兼容）")
    void noDeadline_usesInjectedTemplate() {
        // 直接断言"结果是从这个模板回来的"，比断言"调用发生过"更强：
        // 现建一个别的 RestTemplate 就什么也拿不到，测试会失败
        when(restTemplate.postForEntity(ArgumentMatchers.<String>any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                        "{\"choices\":[{\"message\":{\"content\":\"你好\"}}],"
                                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}",
                        HttpStatus.OK));

        LlmTransport.Result result = LlmTransport.call(
                restTemplate, config, new JSONObject(), 0, LlmTransport.NO_DEADLINE);

        assertNotNull(result, "没有预算限制时必须真的用注入的模板发出去");
        assertEquals("你好", result.content());
        assertEquals(11, result.promptTokens());
        assertEquals(22, result.completionTokens());
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
