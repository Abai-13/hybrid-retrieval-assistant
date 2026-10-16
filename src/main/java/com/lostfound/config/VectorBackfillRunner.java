package com.lostfound.config;

import com.lostfound.service.ItemVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时执行一次「物品向量补齐 / 重建」的维护命令。
 *
 * <p><b>怎么触发</b>（默认完全不生效，只有显式打开才会创建这个 Bean）：
 * <pre>
 * # 补齐：模型没变、文本没变的点会自动跳过
 * mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dai.vector.backfill.enabled=true"
 *
 * # 重建：忽略指纹全量重算（换了模型、或怀疑索引脏了）
 * mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dai.vector.backfill.enabled=true -Dai.vector.backfill.force=true"
 * </pre>
 *
 * <p>为什么做成启动开关而不是常驻定时任务：补齐是<b>按需</b>的一次性动作
 * （发布失败留下的缺口、改过库的历史数据、换模型重建），不是持续同步 ——
 * 本项目没有消息队列，异步写失败靠"事后补一次"恢复（计划 B / §5）。
 * 常驻任务在没有失败的情况下只是在反复扫描和烧 embedding 额度。
 *
 * <p>执行在应用启动之后（{@code ApplicationRunner}），Web 服务已经可用 ——
 * 补齐是分钟级的批量操作，不该卡住健康检查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.vector.backfill", name = "enabled", havingValue = "true")
public class VectorBackfillRunner implements ApplicationRunner {

    private final ItemVectorService itemVectorService;

    /** 每批多少条（DB 查询 + upsert 的粒度；embedding 内部还会按 batch-size 再切） */
    @Value("${ai.vector.backfill.batch-size:100}")
    private int batchSize;

    /** true = 忽略指纹全量重算（换模型 / 重建用） */
    @Value("${ai.vector.backfill.force:false}")
    private boolean force;

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("向量补齐开始: batchSize={}, force={}", batchSize, force);
            ItemVectorService.BackfillReport report = itemVectorService.backfill(batchSize, force);
            // 完成与未完成都用同一条 INFO 打出来 —— 数字本身（尤其 failed）就是结果
            log.info("向量补齐结束: {}", report.summary());
        } catch (Exception e) {
            // 补齐是维护动作：它失败说明向量层出问题了，但**不该让应用起不来** ——
            // 业务（发布/浏览/认领）不依赖向量库，日志里留 ERROR 够运维查
            log.error("向量补齐异常（应用继续启动，可用补齐命令重试）", e);
        }
    }
}
