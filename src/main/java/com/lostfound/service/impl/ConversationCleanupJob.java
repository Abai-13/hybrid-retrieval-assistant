package com.lostfound.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理过期的找物会话。
 *
 * <p>保留策略（默认 7 天）不是"建议"，它得有人真的执行 ——
 * 只写一条 {@code expires_at} 而不删除，表就会一直长，
 * 而"过期"也就只剩一个没人看的字段。
 *
 * <p><b>异常必须自己吞掉</b>：Spring 的调度器遇到未捕获异常只会打日志、
 * 然后按原节奏继续跑下一次，看似没问题；但把异常放到日志栈里滚过去之后，
 * 没人会注意到"清理已经连续失败一周了"。所以这里显式接住并记 ERROR，
 * 让"清理没成功"成为一条明确的、可被检索的记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationCleanupJob {

    private final ConversationStore store;

    /**
     * 每天凌晨 3:17 跑一次。
     * <p>刻意避开整点：整点是各种定时任务扎堆的时刻，
     * 错开几分钟的成本是零，收益是不和别的任务抢数据库。
     */
    @Scheduled(cron = "${ai.conversation.cleanup-cron:0 17 3 * * *}")
    public void purgeExpiredConversations() {
        try {
            int removed = store.purgeAllExpired();
            if (removed > 0) {
                log.info("已清理过期找物会话 {} 段（含其消息）", removed);
            }
        } catch (Exception e) {
            log.error("清理过期找物会话失败，本次未完成（下次调度会再试一次）", e);
        }
    }
}
