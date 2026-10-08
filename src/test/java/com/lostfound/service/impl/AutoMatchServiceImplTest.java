package com.lostfound.service.impl;

import com.lostfound.entity.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 自动匹配开关（门面）的单元测试。
 *
 * <p>钉住的契约很简单但很重要：<b>关掉开关时，连任务都不提交</b> ——
 * 不是"提交了但什么都不做"，而是根本不会走到执行器那一步。
 */
@ExtendWith(MockitoExtension.class)
class AutoMatchServiceImplTest {

    @Mock
    private AutoMatchRunner runner;

    @InjectMocks
    private AutoMatchServiceImpl autoMatchService;

    private static Item item() {
        Item item = new Item();
        item.setId(7L);
        item.setUserId(100L);
        item.setType(Item.TYPE_LOST);
        return item;
    }

    @Test
    @DisplayName("开关开启（默认）：把任务交给异步执行器")
    void enabledDelegatesToRunner() {
        ReflectionTestUtils.setField(autoMatchService, "autoMatchEnabled", true);

        Item lost = item();
        autoMatchService.triggerAfterPublish(lost);

        verify(runner).match(lost);
    }

    @Test
    @DisplayName("★ 开关关闭：不提交任何任务、不调用大模型、不产生通知")
    void disabledSubmitsNothing() {
        ReflectionTestUtils.setField(autoMatchService, "autoMatchEnabled", false);

        autoMatchService.triggerAfterPublish(item());

        // 连执行器都不该被碰到 —— 这是"不提交任务"的字面含义
        verify(runner, never()).match(any(Item.class));
    }

    @Test
    @DisplayName("开关关闭时，即使传 null 也不炸（发布链路不该因为开关而受影响）")
    void disabledToleratesNull() {
        ReflectionTestUtils.setField(autoMatchService, "autoMatchEnabled", false);

        autoMatchService.triggerAfterPublish(null);

        verify(runner, never()).match(any());
    }
}
