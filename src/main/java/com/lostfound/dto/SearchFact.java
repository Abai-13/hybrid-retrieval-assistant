package com.lostfound.dto;

import lombok.Data;

/**
 * 模型从**这一轮用户消息**里提取出的一个事实 —— 还只是"建议"，不是已确认的条件。
 *
 * <p>它和 {@link SearchCondition} 的区别是<b>缺少 source</b>：来源由后端补。
 * 为什么不让模型一起返回来源：它会把 source 写成自己改写过的句子，
 * 那样"来源"就不再是用户原话，而成了模型的转述 —— 摘要里最有价值的那条线索就没了。
 *
 * <p>模型返回后，后端会校验并合并进 {@link SearchSummary}：
 * 字段名和值都有长度上限，超了截断；值空白的整条丢弃。
 */
@Data
public class SearchFact {

    /** 条件名，如「颜色」「地点」 */
    private String field;

    /** 条件值 */
    private String value;

    /** CERTAIN / UNCERTAIN；缺失按 CERTAIN 处理（用户正常陈述时不带限定词） */
    private String certainty;
}
