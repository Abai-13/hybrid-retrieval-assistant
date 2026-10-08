package com.lostfound.service.impl;

import cn.hutool.json.JSONUtil;
import com.lostfound.config.LlmConfig;
import com.lostfound.dto.AiQueryResponse;
import com.lostfound.entity.Item;
import com.lostfound.service.CandidateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentMatchers;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AiServiceImpl.query() 的流程测试 —— 大模型被 mock 掉，<b>不产生任何真实 API 调用</b>。
 *
 * <p>覆盖三件必须钉住的事：
 * <ol>
 *   <li><b>两条降级路径没被新逻辑破坏</b>：召回为空时不调模型（省一次 token）；
 *       模型调用失败时返回降级文案并带上 degradeReason（不能退化成"正常但没匹配到"）。</li>
 *   <li><b>过滤真的生效了</b>，且被过滤的条数如实报出。</li>
 *   <li><b>答案文案与展示条数不矛盾</b> —— 裁剪过就由后端重写。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AiServiceImplQueryTest {

    @Mock
    private LlmConfig llmConfig;

    @Mock
    private RestTemplate llmRestTemplate;

    @Mock
    private CandidateService candidateService;

    @InjectMocks
    private AiServiceImpl aiService;

    @BeforeEach
    void setUp() {
        // 这些 @Value 字段在纯单测里没人注入，只能手动塞
        ReflectionTestUtils.setField(aiService, "interactiveMaxResults", 5);
        ReflectionTestUtils.setField(aiService, "autoMatchMinScore", 80);
        ReflectionTestUtils.setField(aiService, "autoMatchMaxResults", 5);
        ReflectionTestUtils.setField(aiService, "maxRetries", 0);
        ReflectionTestUtils.setField(aiService, "recallTopK", 50);
    }

    private static List<Item> candidates(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> {
            Item item = new Item();
            item.setId(id);
            item.setTitle("物品" + id);
            item.setCategory("其他");
            item.setType(Item.TYPE_FOUND);
            item.setStatus(Item.STATUS_UNCLAIMED);
            return item;
        }).toList();
    }

    /** 拼一个 OpenAI 兼容格式的返回体，content 是模型产出的那段 JSON 字符串 */
    private void givenLlmReturns(String modelContent) {
        String body = "{\"choices\":[{\"message\":{\"content\":" + JSONUtil.toJsonStr(modelContent)
                + "}}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":40}}";
        // ⚠️ 第一个参数必须用 any() 而不是 anyString()：mock 的 llmConfig.getApiUrl() 返回 null，
        //    而 anyString() 不匹配 null —— stub 会静默失效，测试却看起来"跑过了"
        when(llmRestTemplate.postForEntity(ArgumentMatchers.<String>any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    // ===================== 降级路径（不能被新逻辑破坏） =====================

    @Test
    @DisplayName("降级：召回为空 → 标 NO_CANDIDATES，且【压根不调大模型】")
    void recallEmpty_shouldDegradeWithoutCallingLlm() {
        when(llmConfig.getModel()).thenReturn("test-model");
        when(candidateService.recall(anyString(), anyInt())).thenReturn(List.of());

        AiQueryResponse result = aiService.query("一把银色钥匙");

        assertEquals(AiQueryResponse.DEGRADE_NO_CANDIDATES, result.getDegradeReason());
        assertTrue(result.getMatches().isEmpty());
        assertEquals(0, result.getBelowThresholdCount());
        assertNotNull(result.getAnswer());
        // 空候选还要花钱问一次模型是纯浪费，这条断言守着它。
        // 同样必须用 any()：用 anyString() 去 verify 会永远"通过"，哪怕真的调了模型
        verify(llmRestTemplate, never()).postForEntity(ArgumentMatchers.<String>any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("降级：大模型调用失败 → 标 LLM_ERROR，仍能返回（不能变成「正常但没匹配到」）")
    void llmFailure_shouldDegradeWithReason() {
        when(llmConfig.getModel()).thenReturn("test-model");
        when(candidateService.recall(anyString(), anyInt())).thenReturn(candidates(1L));
        when(llmRestTemplate.postForEntity(ArgumentMatchers.<String>any(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("read timed out"));

        AiQueryResponse result = aiService.query("一把银色钥匙");

        assertEquals(AiQueryResponse.DEGRADE_LLM_ERROR, result.getDegradeReason());
        assertTrue(result.getMatches().isEmpty());
        assertTrue(result.getAnswer().contains("繁忙"), "应给用户一句可读的降级说明");
    }

    @Test
    @DisplayName("降级路径也要回显模型名与生效配置（否则评测记不到这一轮的对照条件）")
    void degradeStillEchoesModelAndConfig() {
        when(llmConfig.getModel()).thenReturn("Qwen/Qwen2.5-7B-Instruct");
        when(candidateService.recall(anyString(), anyInt())).thenReturn(List.of());

        AiQueryResponse result = aiService.query("钥匙");

        assertEquals("Qwen/Qwen2.5-7B-Instruct", result.getModel());
        // 主动匹配不过滤，所以生效门槛恒为 0；条数上限来自 interactive 配置
        assertEquals(0, result.getMinScore());
        assertEquals(5, result.getMaxResults());
    }

    // ===================== 主动匹配的筛选策略：不过滤，只截断 =====================

    @Test
    @DisplayName("主动匹配不做分数过滤：各分数段都返回，也不重写模型的文案")
    void interactiveDoesNotFilterByScore() {
        when(llmConfig.getModel()).thenReturn("test-model");
        when(candidateService.recall(anyString(), anyInt())).thenReturn(candidates(1L, 2L, 3L));
        givenLlmReturns("""
                {"answer":"我帮你找到了 3 条","matches":[
                  {"itemId":1,"score":95,"reason":"很像"},
                  {"itemId":2,"score":70,"reason":"一般"},
                  {"itemId":3,"score":50,"reason":"不太像"}]}
                """);

        AiQueryResponse result = aiService.query("一把银色钥匙");

        assertEquals(3, result.getMatches().size(), "主动匹配不过滤：3 条全部返回");
        assertEquals(0, result.getBelowThresholdCount(), "没有任何一条被门槛挡下");
        assertEquals(0, result.getMinScore(), "回显应是 0（主动匹配不过滤）");
        // 没有裁剪过 → 用模型原文，不重写
        assertEquals("我帮你找到了 3 条", result.getAnswer());
    }

    @Test
    @DisplayName("★ 主动匹配的核心契约：低于 80 分的结果必须照常返回，由用户自己判断")
    void returnsResultsBelow80() {
        when(llmConfig.getModel()).thenReturn("test-model");
        when(candidateService.recall(anyString(), anyInt())).thenReturn(candidates(1L, 2L, 3L));
        givenLlmReturns("""
                {"answer":"我帮你找到了 3 条","matches":[
                  {"itemId":1,"score":95,"reason":"很像"},
                  {"itemId":2,"score":70,"reason":"一般"},
                  {"itemId":3,"score":40,"reason":"只有一点像"}]}
                """);

        AiQueryResponse result = aiService.query("一把银色钥匙");

        // 这条是这轮修正的核心：以前 70 和 40 会被 80 分门槛砍掉，用户根本看不到
        assertEquals(List.of(1L, 2L, 3L), result.getMatches().stream().map(m -> m.getItemId()).toList());
        assertEquals(40, result.getMatches().get(2).getScore(), "40 分的那条也要原样带回来");
        assertEquals("只有一点像", result.getMatches().get(2).getReason(), "理由要保留");
        assertEquals(0, result.getBelowThresholdCount());
        // 顺序仍然是分数降序 —— 用户先看到最像的
        assertTrue(result.getMatches().get(0).getScore() > result.getMatches().get(1).getScore());
    }

    @Test
    @DisplayName("★ 哪怕全部都很低分，也照样返回，不能变成空结果")
    void returnsEvenWhenAllScoresAreLow() {
        when(llmConfig.getModel()).thenReturn("test-model");
        when(candidateService.recall(anyString(), anyInt())).thenReturn(candidates(1L, 2L));
        givenLlmReturns("""
                {"answer":"找到了 2 条","matches":[
                  {"itemId":1,"score":70,"reason":"一般"},
                  {"itemId":2,"score":40,"reason":"不太像"}]}
                """);

        AiQueryResponse result = aiService.query("一把银色钥匙");

        assertEquals(2, result.getMatches().size(), "全低分也要返回，让用户自己看");
        assertEquals("找到了 2 条", result.getAnswer(), "没有裁剪过，就用模型原文");
    }

    @Test
    @DisplayName("只超出条数上限时：截断到上限，但不算「低于阈值」，文案如实说 N 条")
    void truncatesToMaxResults() {
        ReflectionTestUtils.setField(aiService, "interactiveMaxResults", 2);
        when(llmConfig.getModel()).thenReturn("test-model");
        when(candidateService.recall(anyString(), anyInt())).thenReturn(candidates(1L, 2L, 3L));
        givenLlmReturns("""
                {"answer":"找到三条","matches":[
                  {"itemId":1,"score":95,"reason":"a"},
                  {"itemId":2,"score":90,"reason":"b"},
                  {"itemId":3,"score":85,"reason":"c"}]}
                """);

        AiQueryResponse result = aiService.query("一把银色钥匙");

        assertEquals(2, result.getMatches().size());
        assertEquals(0, result.getBelowThresholdCount(), "截断掉的不算「低于阈值」");
        assertTrue(result.getAnswer().contains("2 条"), result.getAnswer());
    }

    @Test
    @DisplayName("模型返回不是合法 JSON 时仍走原有降级（把原文塞进 answer，不抛异常）")
    void malformedJson_shouldFallBackToRawText() {
        when(llmConfig.getModel()).thenReturn("test-model");
        when(candidateService.recall(anyString(), anyInt())).thenReturn(candidates(1L));
        givenLlmReturns("```json 我不是 JSON");

        AiQueryResponse result = aiService.query("一把银色钥匙");

        assertNotNull(result.getAnswer());
        assertTrue(result.getMatches().isEmpty());
        // 解析失败不属于「大模型调用失败」，不该被标成 LLM_ERROR
        assertNull(result.getDegradeReason());
    }
}
