package com.lostfound.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 大模型 API 配置。
 * <p>
 * 原来的类名是 DeepSeekConfig，绑定的配置前缀是 {@code deepseek} ——
 * 供应商名字写死在类名和配置键里，想换个模型就得改代码。
 * 现在改成通用的 {@code llm}：换供应商只改 application.yml 三项
 * （api-url / api-key / model），代码一行不动。
 * <p>
 * 能这么改是因为主流国产大模型都提供了 OpenAI 兼容的接口：
 * <pre>
 * DeepSeek     https://api.deepseek.com/v1/chat/completions
 * 硅基流动      https://api.siliconflow.cn/v1/chat/completions
 * 智谱          https://open.bigmodel.cn/api/paas/v4/chat/completions
 * </pre>
 * <p>
 * 面试可讲：为什么自定义超时 —— 默认的 RestTemplate 没有超时，
 * 下游卡住会把 Tomcat 线程池拖干，进而整个服务不可用。
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
@Data
public class LlmConfig {

    /** OpenAI 兼容的 chat completions 地址 */
    private String apiUrl;

    /** API Key */
    private String apiKey;

    /** 模型 ID */
    private String model;

    /** 连接超时（秒） */
    private int connectTimeout;

    /** 读取超时（秒） */
    private int readTimeout;

    /** 最大生成 token 数 */
    private int maxTokens;

    /** 创建配置了超时的 RestTemplate */
    @Bean
    public RestTemplate llmRestTemplate() {
        return restTemplateWithTimeouts(connectTimeout, readTimeout);
    }

    /**
     * 按指定的连接/读取超时建一个 RestTemplate。
     *
     * <p>为什么需要它：多轮会话有整轮预算（默认 90 秒），一轮里可能有两次调用
     * 加各自的重试。如果每次都按固定的 30 秒读超时、3 秒连接超时去发，
     * 一轮最坏能跑到三分钟 —— 预算就成了一句空话。所以接近截止时刻的调用
     * 必须<b>两个超时一起缩</b>。
     *
     * <p>⚠️ 只缩读超时是不够的：连接本身要花时间。一次调用最坏 =
     * 连接超时 + 读超时，只压住后者，剩下的那几秒照样能把预算顶穿
     * （远程服务正在被墙/丢包时，连接阶段就能耗满）。
     *
     * <p>为什么不改这个类里那个共享 Bean：它是全局单例，改它等于把"这一轮快到期了"
     * 变成对所有并发请求生效 —— 一个用户的预算会影响另一个用户。所以是**每次现建**，
     * 用完即弃（底层是 HttpURLConnection，本来也没有连接池可复用）。
     */
    public RestTemplate restTemplateWithTimeouts(int connectTimeoutSeconds, int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(1, connectTimeoutSeconds) * 1000);
        factory.setReadTimeout(Math.max(1, readTimeoutSeconds) * 1000);
        return new RestTemplate(factory);
    }
}
