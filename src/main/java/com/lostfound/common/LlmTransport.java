package com.lostfound.common;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lostfound.config.LlmConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 大模型 HTTP 调用的**唯一出处**：拼请求头、发请求、解析 OpenAI 兼容返回体、
 * 按 {@link RetrySupport} 的规则重试。
 *
 * <p><b>为什么要把它从 {@code AiServiceImpl} 里抠出来</b>：多轮会话里
 * 一轮最多要发两次模型调用，而整轮有 90 秒预算 —— 两次调用和它们各自的重试
 * <b>必须共享同一份剩余时间</b>。如果新链路自己复制一份 HTTP + 重试代码，
 * 就一定会演变成"老的那份不管预算、新的那份管"，同一个项目里两套超时语义。
 *
 * <p><b>它的行为对老链路是逐字兼容的</b>：不传预算时（{@link #NO_DEADLINE}）
 * 用的就是注入进来的那个 RestTemplate、就是原来的重试规则。
 * 只有传了预算，才会为这次请求现做一个读超时更短的 RestTemplate。
 */
@Slf4j
public final class LlmTransport {

    /** 没有整轮预算限制（老的单轮链路走这条） */
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    /**
     * 一次尝试至少要有这么多剩余时间才值得发出去。
     * 小于它，发出去也几乎必然超时，只是白白吃掉用户剩下的等待时间。
     */
    public static final long MIN_ATTEMPT_MS = 2_000L;

    /** 从剩余时间里预留出来的余量（退避、解析、以及别的开销） */
    static final long DEADLINE_SLACK_MS = 1_500L;

    /**
     * 连接超时最多占可用时间的这个比例。
     * <p>不把可用时间全给连接：连接很快失败时要留出读的时间，否则
     * "连上了但还没读完"会立刻变成超时，等于白连一次。
     */
    static final int CONNECT_SHARE_DIVISOR = 3;

    /**
     * 一次调用的两个超时 —— 它们必须一起从剩余预算里分配。
     *
     * @param connectSeconds 连接超时（秒）
     * @param readSeconds    读取超时（秒）
     */
    record Timeouts(int connectSeconds, int readSeconds) {
    }

    private LlmTransport() {
    }

    /** 一次模型调用的结果：正文 + token 用量 */
    public record Result(String content, int promptTokens, int completionTokens) {
    }

    /**
     * 调一次大模型。
     *
     * @param template       不带预算时使用的 RestTemplate（沿用注入的那个）
     * @param config         模型配置
     * @param body           OpenAI 兼容的请求体
     * @param maxRetries     重试次数（不含首次）
     * @param deadlineEpochMs 整轮截止时刻；{@link #NO_DEADLINE} 表示不限
     * @return 调用结果；失败/超时返回 {@code null}，由调用方决定怎么降级
     */
    public static Result call(RestTemplate template, LlmConfig config, JSONObject body,
                              int maxRetries, long deadlineEpochMs) {
        return call(template, config, body, maxRetries, deadlineEpochMs, 0);
    }

    private static Result call(RestTemplate template, LlmConfig config, JSONObject body,
                               int maxRetries, long deadlineEpochMs, int attempt) {
        if (deadlineEpochMs != NO_DEADLINE && remainingMs(deadlineEpochMs) < MIN_ATTEMPT_MS) {
            log.warn("整轮预算只剩 {} ms，不再发起第 {} 次模型调用", remainingMs(deadlineEpochMs), attempt + 1);
            return null;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());
        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);

        // 有预算时现做一个超时被裁短的 RestTemplate：默认 30 秒读 + 3 秒连接
        // 放进只剩 8 秒的预算里，等于"预算"只是个装饰。
        // ⚠️ 连接和读取**都要缩** —— 一次调用最坏 = 连接超时 + 读超时，
        //    只压住后者，连接阶段照样能把预算顶穿。
        RestTemplate effective = template;
        if (deadlineEpochMs != NO_DEADLINE) {
            Timeouts timeouts = timeoutsFor(deadlineEpochMs, config);
            effective = config.restTemplateWithTimeouts(timeouts.connectSeconds(), timeouts.readSeconds());
        }

        try {
            ResponseEntity<String> response = effective.postForEntity(
                    config.getApiUrl(), entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.error("大模型 API 返回异常状态码: {}", response.getStatusCode());
                return null;
            }

            JSONObject json = JSONUtil.parseObj(response.getBody());
            String content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");

            JSONObject usage = json.getJSONObject("usage");
            int promptTokens = usage == null ? 0 : usage.getInt("prompt_tokens", 0);
            int completionTokens = usage == null ? 0 : usage.getInt("completion_tokens", 0);

            return new Result(content, promptTokens, completionTokens);

        } catch (RestClientException e) {
            long waitMs = RetrySupport.backoffMs(attempt);
            // 退避 + 一次最短尝试都塞不进剩余预算，就别重试了 ——
            // 它会把这轮拖到预算耗尽才告诉用户"失败了"，而那正是我们想避免的
            if (attempt < maxRetries && RetrySupport.isRetryable(e) && retryFitsBudget(deadlineEpochMs, waitMs)) {
                log.warn("大模型调用失败（第 {} 次尝试），{}ms 后重试: {}",
                        attempt + 1, waitMs, e.getMessage());
                RetrySupport.sleepQuietly(waitMs);
                return call(template, config, body, maxRetries, deadlineEpochMs, attempt + 1);
            }
            log.error("调用大模型 API 失败（共尝试 {} 次）", attempt + 1, e);
            return null;
        } catch (Exception e) {
            // 解析失败属于"调用成功了但返回体不对"，重试大概率还是同样的结果
            log.error("解析大模型返回内容失败", e);
            return null;
        }
    }

    /** 这次的退避等待 + 下一次尝试的最短耗时，装不装得进剩余预算 */
    static boolean retryFitsBudget(long deadlineEpochMs, long waitMs) {
        return deadlineEpochMs == NO_DEADLINE
                || remainingMs(deadlineEpochMs) >= waitMs + MIN_ATTEMPT_MS;
    }

    public static long remainingMs(long deadlineEpochMs) {
        return deadlineEpochMs - System.currentTimeMillis();
    }

    /**
     * 把剩余时间分给「连接」和「读取」两个超时。
     *
     * <p>分配方式：先扣掉预留量，再从可用时间里切最多
     * {@value #CONNECT_SHARE_DIVISOR} 分之一给连接（且不超过配置值），
     * 剩下的给读取（同样不超过配置值）。
     *
     * <p><b>为什么不是"读超时 = 剩余时间，连接照旧 3 秒"</b>：
     * 一次调用最坏耗时是<b>两者之和</b>。只压读超时的话，
     * 剩余 4 秒时仍可能花掉 3 秒去连接、再花 4 秒读 —— 整整超出一倍，
     * 预算就成了摆设。而连接超时原本是固定值，正是最容易被漏掉的那一半。
     *
     * <p>两个值都保证 ≥1 秒：配置成 0 会被 HttpURLConnection 当成"无限等待"，
     * 那比超时更糟 —— 它会把整轮的预算彻底吃掉。
     */
    static Timeouts timeoutsFor(long deadlineEpochMs, LlmConfig config) {
        long usableMs = Math.max(0, remainingMs(deadlineEpochMs) - DEADLINE_SLACK_MS);

        int connectSeconds = (int) Math.min(config.getConnectTimeout(),
                Math.max(1, usableMs / 1000 / CONNECT_SHARE_DIVISOR));
        long leftMs = usableMs - connectSeconds * 1000L;
        int readSeconds = (int) Math.min(config.getReadTimeout(),
                Math.max(1, leftMs / 1000));
        return new Timeouts(connectSeconds, readSeconds);
    }
}
