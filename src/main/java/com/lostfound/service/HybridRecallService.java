package com.lostfound.service;

import java.util.Set;

/**
 * 混合召回 —— 「2-gram 字面召回 + 向量语义召回」两路合并，只服务<b>聊天找物入口</b>。
 *
 * <p><b>为什么单独一个服务，而不是直接改 {@link CandidateService}</b>：
 * {@code CandidateService} 是公共服务，发布后的自动匹配（{@code AutoMatchRunner}）
 * 也走它。往里面塞向量，等于悄悄把自动匹配也改成了混合召回 ——
 * 而自动匹配是<b>系统主动推给用户</b>的，多推一条就是一次打扰，
 * 它和用户主动来找东西的风险方向相反，必须保持原来的路（约定 25/26 的同一套逻辑）。
 * 所以两条路在这里分岔：自动匹配用 {@code CandidateService}，聊天找物用这个。
 *
 * <p><b>为什么是 RRF 而不是把分数相加</b>：2-gram 给的是「覆盖了用户多少比例的字」，
 * 向量给的是「余弦相似度」，两个数的量纲、分布、含义都不一样 ——
 * 直接相加等于默认"0.6 的覆盖率 = 0.6 的相似度"，这个前提没有任何依据。
 * RRF 只看<b>名次</b>：一条候选在两路里各排第几，名次靠前的得分高。
 * 不引入任何量纲假设，也就不会因为某一路分数整体偏大而被它一手遮天。
 *
 * <p><b>失败回退</b>：向量腿任何一环不可用（embedding 挂、Qdrant 挂、索引没建、
 * 剩余预算不够），都只是这条腿不出结果，返回的仍是原 2-gram 候选 ——
 * 找物助手的行为在向量层故障时与改造前<b>完全一致</b>。
 */
public interface HybridRecallService {

    /**
     * 招领池（FOUND）的混合召回，返回结构与
     * {@link CandidateService#recallDetailed} 一致 —— 精排、诊断、卡片整条下游都不用改。
     *
     * @param query            会话<b>合并后</b>的完整描述（不是用户最后那一句）
     * @param topK             最终候选上限（融合截断后仍然是它，限制 LLM 输入）
     * @param excludeIds       用户明确否掉的物品 id，两路都要躲开（在截断之前就剔除）
     * @param deadlineEpochMs  本轮对话的截止时刻 —— 向量腿是「够时间才跑」的增强，
     *                         不能把已经紧张的预算耗在它身上
     */
    CandidateService.RecallResult recall(String query, int topK, Set<Long> excludeIds,
                                         long deadlineEpochMs);
}
