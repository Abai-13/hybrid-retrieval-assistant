package com.lostfound.dto;

import lombok.Data;

import java.util.List;

/**
 * AI 物品匹配的返回体。
 * <p>
 * 改造前这个接口返回的是一段自然语言文本（{@code {"answer": "我帮你找到了..."}}），
 * 前端只能整段渲染。三个后果：
 * <ol>
 *   <li>没法做成物品卡片 —— 结果里没有 id，点不进详情页</li>
 *   <li>大模型每次输出的措辞和格式都不一样，前端没法稳定解析</li>
 *   <li><b>测不了</b> —— 结果是段文字，算不出 Recall@K，优化前后没得比</li>
 * </ol>
 * 结构化之后，matches 给前端渲染，token / 耗时字段给评测脚本统计成本。
 */
@Data
public class AiQueryResponse {

    /** 给用户看的一句话总结 */
    private String answer;

    /** 匹配到的物品，按匹配度从高到低 */
    private List<MatchResult> matches;

    /** 这次喂给大模型多少条候选 —— 用来诊断召回是不是被候选池卡住了 */
    private int candidateCount;

    /**
     * 这次实际召回的候选 id 列表（按召回得分从高到低）。
     * <p>
     * 用途是**把失败归因到具体的层**。只看最终命中率的话，
     * 「没召回到」和「召回到了但大模型没挑中」长得一模一样，
     * 而这两种情况要修的地方完全不同（前者改召回，后者改精排）。
     * 有了这个字段，评测脚本就能分开算两层的成功率。
     */
    private List<Long> candidateIds;

    /** 本次消耗的 prompt token。评测要拿它对比优化前后的成本 */
    private int promptTokens;

    /** 本次消耗的 completion token */
    private int completionTokens;

    /** 端到端耗时（毫秒），含查库 + 调大模型 */
    private long elapsedMs;

    /**
     * 降级原因。正常返回时为 {@code null}。
     * <p>
     * <b>为什么必须有这个字段</b>：大模型调用失败时，接口会返回 HTTP 200
     * 和一句"AI 服务繁忙，请稍后重试"——从外面看和"正常但没匹配到"完全一样。
     * 评测脚本因此把调用失败当成"未命中"算进了指标，
     * 导致「优化后的版本看起来更差」这种完全颠倒的结论。
     * <p>
     * 这和 P0 那个 Redis bug 是同一类问题：降级保证可用性是对的，
     * 但把失败伪装成正常结果是错的 —— 失败必须可观测。
     */
    private String degradeReason;

    /**
     * 本次实际生效的最低分数门槛。
     * <p><b>主动匹配恒为 0</b>（不过滤，见 AiServiceImpl#NO_SCORE_FILTER）；
     * 自动匹配才是 80 那一档。回显它有两个用途：前端排查"为什么只有一条"时不用去翻服务器配置；
     * 评测脚本可以把<b>当时真正生效的配置</b>记进报告 ——
     * 而不是靠人记得在命令行里传对环境变量（历史报告里连用了哪个模型都没记，就是这么欠下的债）。
     */
    private int minScore;

    /** 本次实际生效的最大展示条数 */
    private int maxResults;

    /**
     * 大模型返回了、但因为分数低于 {@link #minScore} 而没有被采用的条数。
     * <p>主动匹配恒为 0（不过滤）；自动匹配用它来说明"有几条被门槛挡在通知之外"。
     * <p><b>没有这个字段就没法把话说准</b>：「一条都没匹配上」和「匹配上了但都没过线」
     * 是完全不同的两件事，但都表现为 matches 为空。少了它，界面只能笼统说"没找到"，
     * 用户会以为平台上一定没有自己的东西 —— 而事实可能只是分数差了一点。
     */
    private int belowThresholdCount;

    /** 本次调用使用的大模型名，供评测记录（历史报告缺失模型信息，导致事后无法区分对照组） */
    private String model;

    /** 大模型调用失败（超时、网络、5xx） */
    public static final String DEGRADE_LLM_ERROR = "LLM_ERROR";
    /** 召回为空，压根没调大模型（这是正常路径，不是故障） */
    public static final String DEGRADE_NO_CANDIDATES = "NO_CANDIDATES";
    /**
     * 大模型**调通了**，但返回的内容结构上不可用（不是 JSON，或缺 matches / answer）。
     *
     * <p><b>为什么必须和"没匹配到"分开</b>：以前这段内容会被原样塞进 answer、
     * matches 留空、degradeReason 留 null —— 从外面看，它和"查了，但没有"长得一模一样。
     * 后果是双重的：用户以为平台上真没有自己的东西而放弃；
     * 基于这些记录算出来的命中率也会被这批"其实是坏的"样本拉低，
     * 得出"优化之后反而更差"这种颠倒的结论。
     *
     * <p>注：单轮接口仍会把原文放进 answer（页面上至少还能看到点东西），
     * 但**必须带上这个标记**；多轮会话则直接判为可重试的失败。
     */
    public static final String DEGRADE_BAD_RESPONSE = "BAD_RESPONSE";
}
