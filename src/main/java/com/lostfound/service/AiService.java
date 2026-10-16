package com.lostfound.service;

import com.lostfound.dto.AiQueryResponse;
import com.lostfound.entity.Item;

import java.util.List;

/**
 * AI 服务接口 — 第二阶段实现。
 */
public interface AiService {

    /**
     * AI 问答 — 用户用自然语言提问，返回 AI 生成的回答。
     * @param question 用户问题
     * @return AI 回答文本
     */
    String chat(String question);

    /**
     * 根据用户的自然语言描述匹配失物/招领物品。
     * <p>
     * 返回结构化结果（含匹配度和理由），而不是一段自然语言 ——
     * 这样前端能把结果渲染成可点击的卡片，评测脚本也能算出 Recall@K。
     *
     * @param question 用户对丢失物品的描述
     * @return 匹配结果 + 本次调用的 token / 耗时统计
     */
    AiQueryResponse query(String question);

    /**
     * 用一批**指定的候选**做精排 —— 「召回」与「精排」拆开之后的下半段。
     *
     * <p>为什么要有它：候选池不总是招领。发布招领后的反向匹配要拿**失物**当候选，
     * 但精排这一半（拼 prompt、调模型、解析、幻觉防护、按分数排序）完全一样。
     * 抽出来复用，两边就不会各自演化出不同的解析与防护逻辑。
     *
     * @param candidates 已经召回的候选；空列表会直接短路成"没找到"，不浪费一次模型调用
     */
    AiQueryResponse matchAgainst(String question, List<Item> candidates);

    /**
     * 带整轮时间预算的精排 —— 多轮会话用这一版。
     *
     * <p>为什么需要预算：多轮找物助手一轮里可能发两次模型调用（先理解、再匹配），
     * 每次调用自己还有重试。各自独立地"最多重试到 30 秒超时"，
     * 一轮就能跑到三分钟，用户早就走了。所以两次调用必须共享同一个截止时刻。
     *
     * <p><b>预算只影响"发不发、等多久"，不影响结果怎么算</b> ——
     * 筛选规则、幻觉防护、字段回填与无预算版本完全一致。
     *
     * @param deadlineEpochMs 截止时刻（毫秒时间戳）；{@code LlmTransport.NO_DEADLINE} 表示不限
     */
    AiQueryResponse matchAgainst(String question, List<Item> candidates, long deadlineEpochMs);
}
