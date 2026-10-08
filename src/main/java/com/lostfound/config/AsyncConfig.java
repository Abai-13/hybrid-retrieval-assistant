package com.lostfound.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步执行配置 —— 目前只服务于「发布后自动匹配」。
 *
 * <p>为什么需要一个专用线程池而不是默认的：{@code @Async} 不配执行器时会落到
 * {@code SimpleAsyncTaskExecutor}，它<b>每次调用都新建一个线程且不复用</b> ——
 * 发布高峰会把线程数打上天。所以给一个**有界**的池子。
 *
 * <p>池子刻意开得很小（核心 1 / 最大 2）：自动匹配是"后台锦上添花"，
 * 它不该跟用户的正常请求抢资源；真排队了，丢掉一次提醒也比拖慢全站好。
 *
 * <p>拒绝策略：<b>丢弃 + 记 ERROR</b>。任务被丢 = 这次发布不会产生通知，
 * 但物品本身已经发布成功了，用户随时可以自己去 AI 页搜 —— 能力和自动匹配一模一样。
 * 关键是**不能因此抛异常到发布链路上**。
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("autoMatchExecutor")
    public ThreadPoolTaskExecutor autoMatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("auto-match-");
        // 语义上等同 DiscardPolicy（丢弃不抛异常），但**留下一条 ERROR 日志** ——
        // DiscardPolicy 是会静默吞掉的，而"悄悄没发生"正是这个项目反复警惕的那类问题
        executor.setRejectedExecutionHandler((task, pool) ->
                log.error("自动匹配任务被丢弃：队列已满（活跃线程 {}，排队 {}）—— 本次发布不会产生通知，"
                                + "物品本身已发布成功，用户仍可在 AI 页手动搜",
                        pool.getActiveCount(), pool.getQueue().size()));
        executor.initialize();
        return executor;
    }
}
