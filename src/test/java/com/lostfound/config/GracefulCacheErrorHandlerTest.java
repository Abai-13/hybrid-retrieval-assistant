package com.lostfound.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缓存降级处理器的单元测试。
 *
 * <p>钉住的是两条必须同时成立的契约（项目约定 6 + 7）：
 * <ol>
 *   <li><b>静默</b>：4 个钩子都不能把异常抛出去 —— 缓存是性能组件不是正确性组件，
 *       它挂了服务必须照常可用；</li>
 *   <li><b>可观测</b>：静默 ≠ 不管。被吞掉的异常必须留下 WARN 日志，
 *       否则你永远不知道缓存其实是坏的。</li>
 * </ol>
 *
 * <p>为什么用单测而不是"把 Redis 停掉试试"：停 Redis 会影响正在跑的实例，
 * 而且只覆盖"连接失败"一种异常；单测能确定性地覆盖全部 4 个钩子。
 */
class GracefulCacheErrorHandlerTest {

    private final GracefulCacheErrorHandler handler = new GracefulCacheErrorHandler();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(GracefulCacheErrorHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    /** 造一个名字固定的假 Cache，并给一个带 message 的异常 */
    private static Cache cacheNamed(String name) {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn(name);
        return cache;
    }

    private static RuntimeException boom() {
        return new RuntimeException("Unable to connect to Redis");
    }

    @Test
    @DisplayName("静默降级：读失败不抛异常（读失败＝未命中，主流程继续查库）")
    void getErrorIsSwallowed() {
        assertDoesNotThrow(() -> handler.handleCacheGetError(boom(), cacheNamed("foundPool"), "all"));
    }

    @Test
    @DisplayName("静默降级：写失败不抛异常（数据以数据库为准，缓存写不进去就算了）")
    void putErrorIsSwallowed() {
        assertDoesNotThrow(() -> handler.handleCachePutError(boom(), cacheNamed("itemPage"), "1:10", "value"));
    }

    @Test
    @DisplayName("静默降级：删除失败不抛异常（最坏是脏缓存，会自然过期）")
    void evictErrorIsSwallowed() {
        assertDoesNotThrow(() -> handler.handleCacheEvictError(boom(), cacheNamed("itemDetail"), 7L));
    }

    @Test
    @DisplayName("静默降级：清空失败不抛异常")
    void clearErrorIsSwallowed() {
        assertDoesNotThrow(() -> handler.handleCacheClearError(boom(), cacheNamed("itemMypage")));
    }

    @Test
    @DisplayName("★ 静默 ≠ 不管：四种失败都要留下带 cache 名和原始异常的 WARN")
    void everyFailureIsLoggedWithCacheNameAndCause() {
        handler.handleCacheGetError(boom(), cacheNamed("foundPool"), "all");
        handler.handleCachePutError(boom(), cacheNamed("itemPage"), "1:10", "v");
        handler.handleCacheEvictError(boom(), cacheNamed("itemDetail"), 7L);
        handler.handleCacheClearError(boom(), cacheNamed("itemMypage"));

        assertEquals(4, appender.list.size(), "四种失败各应记一条日志");
        assertTrue(appender.list.stream().allMatch(e -> e.getLevel() == Level.WARN), "降级必须是 WARN 级别");

        String all = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("foundPool"), all);
        assertTrue(all.contains("itemPage"), all);
        assertTrue(all.contains("itemDetail"), all);
        assertTrue(all.contains("itemMypage"), all);
        // 原始异常信息要带上，否则只看到"缓存失败了"却不知道是连不上还是序列化问题
        assertTrue(all.contains("Unable to connect to Redis"), all);
    }
}
