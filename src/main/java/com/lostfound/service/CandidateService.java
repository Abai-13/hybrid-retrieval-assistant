package com.lostfound.service;

import com.lostfound.entity.Item;

import java.util.List;

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
}
