package com.lostfound.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步执行配置 —— 两个彼此隔离的专用线程池：「发布后自动匹配」与「发布后物品向量化」。
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

    /**
     * 物品向量化任务的专用线程池 —— 发布成功后补写向量。
     *
     * <p><b>为什么不复用 {@code autoMatchExecutor}</b>：两个任务的「丢得起」程度不同，
     * 挤在一个池子里会互相拖累 —— 一次批量补齐（几百个向量任务排队）
     * 足以把自动匹配的唯一核心线程占满，让发布通知迟到甚至被丢。
     * 分开之后各自的队列深度就是自己的事，故障域也不重叠。
     *
     * <p>参数刻意比自动匹配池更小（核心 1 / 最大 1 / 队列 200）：向量化是
     * 「网络 IO 等待型」任务，一个线程顺序跑就够；队列放得下几十次发布的积压，
     * 因为<b>写向量不写也不影响正确性</b> —— 遗漏靠补齐命令修复。
     *
     * <p>拒绝时记 ERROR 不抛（同上：向量丢一次不能影响已经成功的发布）。
     * 之所以不用 {@code @Async} 而是提交到手动注入的执行器：{@code @Async} 自调用不生效，
     * 而这个触发方法和索引方法在同一个类里（触发 → 跑索引），
     * 走代理就得把它们拆成两个 Bean，不如直接用线程池来得直白。
     */
    @Bean("itemIndexExecutor")
    public ThreadPoolTaskExecutor itemIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("item-index-");
        executor.setRejectedExecutionHandler((task, pool) ->
                log.error("物品向量化任务被丢弃：队列已满（活跃 {}，排队 {}）—— 本次发布的向量缺失，"
                                + "可运行补齐命令修复；发布与自动匹配均不受影响",
                        pool.getActiveCount(), pool.getQueue().size()));
        executor.initialize();
        return executor;
    }
}
