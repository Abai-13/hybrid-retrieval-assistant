package com.lostfound.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关 —— 目前只有「清理过期的找物会话」一个任务。
 *
 * <p>为什么需要一个全站定时清理，而不只是"用户访问时顺手清自己的"：
 * 惰性清理只有人回来才会发生，而**再也没回来的用户正是最该被清理的那批**。
 * 只做惰性清理的结果是：活跃用户的数据很干净，沉默用户的会话永远躺在库里。
 *
 * <p>Spring Boot 默认给一个单线程调度器。当前只有一个任务、每天跑一次，
 * 用默认的足够 —— 不必为了"看起来专业"去配线程池，
 * 那会多出一个需要调的参数，而它现在没有任何需要调的理由。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
