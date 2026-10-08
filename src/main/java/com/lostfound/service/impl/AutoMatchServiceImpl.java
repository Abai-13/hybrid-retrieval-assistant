package com.lostfound.service.impl;

import com.lostfound.entity.Item;
import com.lostfound.service.AutoMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 自动匹配的门面 —— 只做一件事：<b>判开关</b>。
 *
 * <p>真正的活（异步、调模型、写通知）在 {@link AutoMatchRunner} 里。
 * 之所以拆成两个 Bean：{@code @Async} 只能加在**被代理的方法**上，
 * 如果把开关判断写进那个异步方法里，关闭开关时任务照样会被丢进线程池 ——
 * 只是进去之后立刻返回。拆开之后，「关掉」就是**真的一个任务都不提交**，
 * 语义和要求完全一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoMatchServiceImpl implements AutoMatchService {

    private final AutoMatchRunner runner;

    /**
     * 自动匹配总开关。
     * <p>关掉时：发布照常成功，但不提交匹配任务、不调用大模型、不产生任何通知。
     * <p>它只影响自动匹配，<b>不影响 AiChat 的主动匹配</b>。
     */
    @Value("${ai.match.auto.enabled:true}")
    private boolean autoMatchEnabled;

    @Override
    public void triggerAfterPublish(Item item) {
        if (!autoMatchEnabled) {
            log.info("自动匹配已关闭（ai.match.auto.enabled=false），本次发布不提交匹配任务: itemId={}",
                    item == null ? null : item.getId());
            return;
        }
        runner.match(item);
    }
}
