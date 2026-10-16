package com.lostfound.service;

import com.lostfound.entity.Item;

import java.util.List;

/**
 * 物品向量 —— 写入（发布后 / 补齐）与检索（查询侧）的唯一入口。
 *
 * <p><b>它管什么</b>：把「一条招领物品的文本」变成 Qdrant 里的一个点，以及
 * 「一段用户描述」变成一次向量检索。<b>它不管什么</b>：怎么和 2-gram 结果合并、
 * 怎么排序 —— 那是 {@link HybridRecallService} 的事。两侧各管一段，改动互不牵连。
 *
 * <p><b>失败契约</b>（与 {@link EmbeddingService} 一致）：向量链路任何一环失败都
 * 返回 {@code null} / {@code false} / 空结果，<b>不抛异常</b> ——
 * 它是召回的增强路径，坏了必须安静回退到原 2-gram，不能把一次找物变成 500。
 *
 * <p><b>范围</b>：本轮只索引招领（{@link Item#TYPE_FOUND}）物品 ——
 * 聊天找物是「用户拿丢失描述 → 找招领池」，向量库只需要覆盖这一侧；
 * 失物（LOST）侧由发布招领后的自动匹配处理，而那条链路明确保持 2-gram。
 */
public interface ItemVectorService {

    /**
     * 物品发布成功后触发异步写向量（只处理 FOUND；LOST 直接跳过）。
     *
     * <p><b>必须异步、异常不得外溢</b>：向量化要调云端 embedding（几百毫秒到秒级），
     * 不能让用户在「发布」按钮上等它；失败也只记日志 —— 遗漏可以靠
     * {@link #backfill} 修复，而一次失败的调用不该撤销已经成功的发布。
     * 队列满时任务被丢弃，同样只记 ERROR。
     */
    void triggerAfterPublish(Item item);

    /**
     * 同步索引一条物品（幂等：同一 itemId 重复调用是覆盖而不是新增）。
     *
     * @return 是否写入成功；关闭开关 / 不是 FOUND / 文本为空 / 向量化失败 → {@code false}
     */
    boolean index(Item item);

    /**
     * 查询文本向量化后检索向量库。
     *
     * @param queryText 会话<b>合并后</b>的完整描述（不是用户最后一句话）
     * @param limit     最多返回多少条（调用方通常会多取一些再过滤）
     * @return 按相似度降序的物品 id；<b>{@code null} 表示向量路径不可用</b>
     *         （关闭开关 / 向量化失败 / Qdrant 不可达 / 索引未建），
     *         上层据此回退原 2-gram —— 注意 {@code null} 和「空列表」不是一回事：
     *         前者是故障，后者是「语义上确实一条都不像」
     */
    List<Long> searchIds(String queryText, int limit);

    /**
     * 分批补齐 / 重建索引（手动触发的命令，<b>可重复执行</b>）。
     *
     * <p>幂等的两层保障：写入用物品 id 当点 id（重复 upsert = 覆盖）；
     * 跳过靠 payload 里的 {@code model + 文本指纹} —— 模型没变、文本没变的点
     * 直接跳过，不重复烧 embedding 额度（{@code force=true} 则全量重算）。
     *
     * <p><b>孤儿清理</b>：扫完全量后，集合里存在但 MySQL 里已不存在（或已不是 FOUND）的点会被删除。
     * 只有<b>完整扫完</b>才清理 —— 中途失败时「没见过」不等于「不存在」，
     * 拿半份清单去删点，会把还没轮到的正常物品删掉。
     *
     * @param batchSize 每批多少条（一次批量 embedding + 一次 upsert）
     * @param force     true = 忽略指纹全量重算（换模型 / 怀疑索引脏了时用）
     */
    BackfillReport backfill(int batchSize, boolean force);

    /** 补齐结果 —— 全部数字都要如实反映，失败的算失败，不并进 skipped */
    record BackfillReport(int scanned, int indexed, int skipped, int failed, int orphansDeleted,
                          boolean completed, long elapsedMs) {

        public String summary() {
            return "扫描 %d 条 → 写入 %d / 跳过 %d / 失败 %d / 清理孤儿 %d 个点%s，耗时 %d ms"
                    .formatted(scanned, indexed, skipped, failed, orphansDeleted,
                            completed ? "" : "（未扫完，孤儿清理已跳过）", elapsedMs);
        }
    }
}
