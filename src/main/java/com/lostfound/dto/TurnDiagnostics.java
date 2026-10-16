package com.lostfound.dto;

import lombok.Data;

/**
 * 一轮对话的诊断信息 —— 用户平时看不见，但出了问题必须能回答"卡在哪一层"。
 *
 * <p>它存在的理由和 {@code AiQueryResponse.degradeReason} 是同一个：
 * <b>「调用失败」和「正常没找到」不能长得一样</b>。
 * 混在一起会让用户以为平台上一定没有自己的东西，也会让任何基于这批数据的统计失真。
 */
@Data
public class TurnDiagnostics {

    /** 本轮实际发生的业务模型调用次数（理解 1 次 + 匹配/解释最多 1 次；重试不计入这里，另看 tokens） */
    private int modelCalls;

    /** 本轮所有模型调用加起来的 prompt token */
    private int promptTokens;

    /** 本轮所有模型调用加起来的 completion token */
    private int completionTokens;

    /** 本轮端到端耗时（毫秒），含查库、召回、模型调用与重试 */
    private long elapsedMs;

    /** 本轮送进精排的候选条数 */
    private int candidateCount;

    /** 本次打分用的候选池大小 —— 召回的天花板就在这里 */
    private int poolSize;

    /** 因为用户此前明确否掉而被剔出候选池的条数 —— 「不是第二个，再找找」是否真的生效，看它 */
    private int excludedCount;

    /** 本轮使用的模型名 */
    private String model;

    /** 本轮实际生效的整轮时间预算（毫秒） */
    private long budgetMs;

    /** 降级原因；正常时为 null。见 {@link ConversationTurnView} 的常量 */
    private String degradeReason;

    /**
     * 降级的细节（哪一步、什么异常、模型返回了什么）。
     * <p>和 degradeReason 分开：前者是给前端选文案用的**有限枚举**，
     * 这个是给人排查用的自由文本。把它们混成一个字段的代价是
     * 前端只能靠字符串匹配来分支，而任何一次文案微调都会悄悄打断它。
     */
    private String degradeDetail;
}
