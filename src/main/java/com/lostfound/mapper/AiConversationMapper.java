package com.lostfound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lostfound.entity.AiConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 会话表的数据访问。
 *
 * <p><b>为什么这三个方法写成原样 SQL，而不是用 {@code LambdaUpdateWrapper}</b>：
 * 它们都是"条件更新"—— 必须把「谁在占用」和「版本号」写进 WHERE 才成立，
 * 而这正是悲观锁要解决的问题：<b>判断和写入必须是同一条语句</b>。
 * 先查后改会留下两条缝，两个并发请求可以都查到"空闲"，然后同时开始跑。
 *
 * <p>另外，这几个语句也刻意避开了 MyBatis-Plus 的 LambdaWrapper ——
 * 那套要在运行时读实体元数据（TableInfo），在纯 Mockito 单测里根本没有那张缓存，
 * 于是"能不能并发正确"这段最关键的逻辑反而没法单元测试。
 * 写成显式 SQL 之后，它既原子、又能被普通 mock 覆盖，还能被审查者逐字读。
 */
@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    /**
     * 占用这段会话来跑某一轮。
     *
     * <p>三个条件缺一不可：
     * <ul>
     *   <li>{@code version} 对得上 —— 我们读到的是最新摘要，没有被别人改过；</li>
     *   <li>没人占用（{@code active_request_id IS NULL}）—— 同一会话同时只跑一轮；</li>
     *   <li>或者占用已经陈旧（{@code active_since < staleBefore}）—— 进程挂过也能恢复，
     *       否则一轮死在半路会把这段会话永久锁住。</li>
     * </ul>
     *
     * <p>⚠️ <b>这里刻意不动 {@code version}</b>：version 表达的是"摘要改到第几版了"，
     * 而占用本身没有改摘要。曾经在这里顺手 {@code version = version + 1}，
     * 结果占用之后收尾时拿的还是占用前的版本号，条件更新一行都匹配不到 ——
     * 每一轮都变成 409（R7 复验时当场复现）。
     *
     * @return 1 表示抢到了；0 表示别人在跑、或版本已经变了
     */
    @Update("""
            UPDATE ai_conversation
               SET active_request_id = #{requestId},
                   active_since = #{now},
                   updated_at = #{now}
             WHERE id = #{id}
               AND version = #{version}
               AND (active_request_id IS NULL OR active_since IS NULL OR active_since < #{staleBefore})
            """)
    int claimTurn(@Param("id") Long id,
                  @Param("version") Integer version,
                  @Param("requestId") String requestId,
                  @Param("staleBefore") LocalDateTime staleBefore,
                  @Param("now") LocalDateTime now);

    /**
     * 锁定并读出会话行 —— 抢的正是 {@link #claimTurn} 要改的<b>同一条行锁</b>。
     *
     * <p>为什么收回孤儿用户消息必须先拿这把锁：那一步是「读 active_request_id → 判断 → 删除」，
     * 而 {@code claimTurn} 是一条 UPDATE。只读不锁的话，读到「不归我」和删除之间
     * 抢占随时可以插进来成立，这一删就会删掉<b>正在被使用的那一轮</b>的原话 ——
     * 判断和写入之间不能留缝，这和上面那三条条件更新是同一个道理。
     *
     * <p>{@code FOR UPDATE} 读的是<b>最新已提交</b>版本，不受本事务快照建立时机影响；
     * 事务回滚/提交时锁一起释放，临界区就是这个短事务本身。
     *
     * @return 会话行（带锁）；会话已不存在时返回 {@code null}（此时它名下的消息也已被同事务清走）
     */
    @Select("""
            SELECT * FROM ai_conversation
             WHERE id = #{id}
             FOR UPDATE
            """)
    AiConversation lockById(@Param("id") Long id);

    /**
     * 正常结束一轮：写摘要 + 释放占用。
     *
     * <p>{@code active_request_id = #{requestId}} 是**所有权证明**：
     * 只有还持有占用的那一轮才写得进去。它正是"被接管后，旧执行者迟到的结果不能提交"的实现 ——
     * 旧执行者的占用早就被新的一轮换掉了，这条 UPDATE 一行都匹配不到。
     */
    @Update("""
            UPDATE ai_conversation
               SET summary_json = #{summaryJson},
                   expires_at = #{expiresAt},
                   active_request_id = NULL,
                   active_since = NULL,
                   version = version + 1,
                   updated_at = #{now}
             WHERE id = #{id}
               AND version = #{version}
               AND active_request_id = #{requestId}
            """)
    int finishWithSummary(@Param("id") Long id,
                          @Param("version") Integer version,
                          @Param("requestId") String requestId,
                          @Param("summaryJson") String summaryJson,
                          @Param("expiresAt") LocalDateTime expiresAt,
                          @Param("now") LocalDateTime now);

    /**
     * 失败结束一轮：**不碰摘要**，只释放占用并续期。
     *
     * <p>续期是因为用户确实还在用这段对话（"保留最近 7 天"说的就是最后一次活动）；
     * 不写摘要是因为这一轮没跑完，它的条件不该进记忆。
     *
     * <p>和占用一样**不动 {@code version}**：摘要没变，版本就不该变。
     * 否则"用户看到的版本"会因为一轮失败而变化，前端明明什么都没改就被判成过期。
     */
    @Update("""
            UPDATE ai_conversation
               SET expires_at = #{expiresAt},
                   active_request_id = NULL,
                   active_since = NULL,
                   updated_at = #{now}
             WHERE id = #{id}
               AND version = #{version}
               AND active_request_id = #{requestId}
            """)
    int finishWithoutSummary(@Param("id") Long id,
                             @Param("version") Integer version,
                             @Param("requestId") String requestId,
                             @Param("expiresAt") LocalDateTime expiresAt,
                             @Param("now") LocalDateTime now);
}
