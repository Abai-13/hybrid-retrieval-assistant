package com.lostfound.service;

import com.lostfound.entity.Item;

import java.util.List;
import java.util.Set;

/**
 * 候选召回 —— 决定「哪些物品有机会被大模型看到」。
 * <p>
 * 这一步是整条链路的天花板：候选池里没有的东西，后面再好的排序也找不出来。
 * 评测基线证实了这一点 —— 原实现只把「最近发布的 30 条」当候选，
 * 池内命中率 100%，池外 0%，总命中率精确等于候选池覆盖率。
 */
public interface CandidateService {

    /**
     * 根据用户描述召回最相关的 Top-K 候选物品。
     *
     * @param query 用户对丢失物品的自然语言描述
     * @param topK  最多返回多少条
     * @return 按相关度从高到低排序的候选；没有任何字符重合时返回空列表
     */
    List<Item> recall(String query, int topK);

    /**
     * 指定候选池的召回。
     *
     * @param poolType {@link com.lostfound.entity.Item#TYPE_FOUND}（失物描述找招领）
     *                 或 {@link com.lostfound.entity.Item#TYPE_LOST}（招领描述找失物）
     */
    List<Item> recall(String query, String poolType, int topK);

    /**
     * 带排除项的召回 —— 「不是第二个，再找找」走这条。
     *
     * <p><b>为什么排除必须在截断之前做</b>：Top-K 是"最好的 K 条"。
     * 如果先取 Top-K 再把被排除的那条从结果里删掉，用户拿到的就是 K-1 条，
     * 而第 K+1 名（本来该补上来的）根本没机会进来。
     * 用户说的是"再找找"，不是"少给我一条"。
     *
     * @param excludeIds 需要躲开的物品 id（用户已明确否掉的）；可为空
     */
    List<Item> recall(String query, String poolType, int topK, Set<Long> excludeIds);

    /**
     * 召回结果 + 过程数字。
     *
     * @param candidates    最终送进精排的候选
     * @param poolSize      打分用的候选池大小 —— 它决定了召回的天花板
     * @param excludedCount 因为用户明确否掉而被剔出候选池的条数。
     *                      「不是第二个，再找找」到底有没有生效，看的就是它：
     *                      为 0 时说明那条物品本来就不在这次召回里，
     *                      用户会觉得"我说了不要它，它还在"
     */
    record RecallResult(List<Item> candidates, int poolSize, int excludedCount) {
    }

    /**
     * 带回召回过账信息的召回。
     * <p>为什么要有它：{@link #recall} 只给结果，而多轮对话需要把「这次躲开了几条」
     * 也记进诊断 —— 排除项生效与否是用户直接能感知的事，不能只写在日志里。
     */
    RecallResult recallDetailed(String query, String poolType, int topK, Set<Long> excludeIds);
}
