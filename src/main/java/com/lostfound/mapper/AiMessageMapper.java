package com.lostfound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lostfound.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    /**
     * 锁定读出这一轮的 USER 行 —— 抢占成立前的<b>存在性证明</b>。
     *
     * <p>必须是 {@code FOR UPDATE}（当前读），不能是普通 SELECT：
     * 普通 SELECT 读的是本事务快照，它有没有看见"孤儿收回刚把这行删掉"
     * 取决于快照建立的时机；当前读一律读最新已提交版本，
     * 无论哪一方先拿到会话行锁，读到的都是那一刻的真相。
     *
     * <p>没有这道检查，「收回孤儿」可以先删掉原话、「抢占」随后照样成立 ——
     * 于是产生一条<b>只有助手、没有用户原话</b>的回复，刷新后界面上对不上账。
     *
     * @return 该轮的 USER 行（带锁）；已被收回（或从未存在）时返回 {@code null}
     */
    @Select("""
            SELECT * FROM ai_message
             WHERE conversation_id = #{conversationId}
               AND request_id = #{requestId}
               AND role = 'USER'
             LIMIT 1
             FOR UPDATE
            """)
    AiMessage selectUserMessageForUpdate(@Param("conversationId") Long conversationId,
                                         @Param("requestId") String requestId);
}
