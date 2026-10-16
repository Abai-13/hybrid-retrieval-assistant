package com.lostfound.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Qdrant 连接配置 —— 绑定 {@code qdrant.*}。
 *
 * <p>为什么单独一段而不是塞进 {@code embedding.*}：向量化和向量库是两个独立下游 ——
 * 一个挂了另一个还能干活（向量写不进去时查询侧照样回退 2-gram），
 * 超时特征也完全不同（本地 Qdrant 百分之几毫秒返回，云端 embedding 200~300ms）。
 * 混在一起，调 embedding 超时会顺手把 Qdrant 的也改了。
 *
 * <p><b>超时为什么是毫秒级且短</b>：Qdrant 是本机/同网服务，
 * 正常检索在个位数毫秒内返回。给它 3 秒读超时意味着「Qdrant 卡死」这个故障
 * 会让每一次找物对话都白等 3 秒才回退 —— 回退必须是<b>立刻</b>发生的，
 * 否则「有向量加速」在故障时反而变成了「比纯 2-gram 更慢」。
 * 两个值都可配置：真放到远端机器时按网络往返时间调大即可。
 */
@Configuration
@ConfigurationProperties(prefix = "qdrant")
@Data
public class QdrantConfig {

    /**
     * 是否启用 Qdrant（连接与读写）。
     * <p>{@code false} 时所有向量读写直接失败返回，调用方按「向量不可用」回退 ——
     * 相当于一键关掉向量能力，不需要改代码。
     */
    private boolean enabled = true;

    /** 服务地址（不含路径）。容器内用 {@code http://qdrant:6333} */
    private String baseUrl = "http://127.0.0.1:6333";

    /**
     * 集合名。
     * <p><b>换 embedding 模型必须换集合名重建</b>：新旧模型的向量维度/语义空间不同，
     * 混在同一个集合里，Qdrant 要么维度对不上直接报错、要么把两种向量混着比 ——
     * 两种结果都比「重建一次」贵得多。所以集合名是模型的身份，不是随手起的别名。
     */
    private String collection = "lost_found_items";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 500;

    /** 读取超时（毫秒）—— 短超时是「回退要立刻发生」的前提，见类注释 */
    private int readTimeoutMs = 2000;

    /**
     * Qdrant 专用 RestTemplate。
     * <p>⚠️ 和 {@link EmbeddingConfig#embeddingRestTemplate()} 同样的规则：
     * 项目里有多个 {@code RestTemplate} Bean，注入方<b>字段名必须叫 {@code qdrantRestTemplate}</b>，
     * 否则按类型注入会因为候选不唯一而失败。
     */
    @Bean
    public RestTemplate qdrantRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
