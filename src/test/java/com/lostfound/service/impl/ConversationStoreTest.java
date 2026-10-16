package com.lostfound.service.impl;

import com.lostfound.common.BusinessException;
import com.lostfound.common.ResultCode;
import com.lostfound.entity.AiConversation;
import com.lostfound.entity.AiMessage;
import com.lostfound.mapper.AiConversationMapper;
import com.lostfound.mapper.AiMessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 会话所有权与并发写入 —— 这是第一阶段里最不能出错的一段。
 *
 * <p>两条底线在这里被钉住：
 * <ol>
 *   <li><b>会话 id 不可信</b>。它来自客户端，所以每次读写都要拿它和登录用户比对。
 *       漏掉一处，任何人改一下 URL 里的数字就能读到别人的对话记录；</li>
 *   <li><b>版本冲突不能覆盖新状态</b>。同一段对话并发写两条消息时，
 *       后写的那条必须失败，而不是带着旧摘要覆盖掉别人刚写好的状态。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ConversationStoreTest {

    @Mock
    private AiConversationMapper conversationMapper;

    @Mock
    private AiMessageMapper messageMapper;

    @InjectMocks
    private ConversationStore store;

    private static AiConversation conversation(Long id, Long userId, int version) {
        AiConversation conversation = new AiConversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        conversation.setVersion(version);
        conversation.setSummaryJson("{}");
        conversation.setExpiresAt(LocalDateTime.now().plusDays(7));
        return conversation;
    }

    // ===================== 所有权 =====================

    @Test
    @DisplayName("★ 别人的会话：403（不是 404 —— 抹成 404 会把「越权尝试」和「已过期」混成同一件事）")
    void requireOwned_rejectsOtherUsersConversation() {
        when(conversationMapper.selectById(5L)).thenReturn(conversation(5L, 2L, 0));

        BusinessException e = assertThrows(BusinessException.class,
                () -> store.requireOwned(5L, 1L));

        assertEquals(ResultCode.FORBIDDEN, e.getCode());
    }

    @Test
    @DisplayName("会话不存在：404，并且提示可以重新开始")
    void requireOwned_missingConversationIs404() {
        when(conversationMapper.selectById(5L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> store.requireOwned(5L, 1L));

        assertEquals(ResultCode.NOT_FOUND, e.getCode());
    }

    @Test
    @DisplayName("★ 没登录（userId 为 null）：401 —— 直接抛错，绝不让它掉进空指针后被当成「没有会话」")
    void requireOwned_nullUserIs401() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> store.requireOwned(5L, null));

        assertEquals(ResultCode.UNAUTHORIZED, e.getCode());
        verify(conversationMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("本人的会话：正常返回")
    void requireOwned_acceptsOwner() {
        AiConversation owned = conversation(5L, 1L, 0);
        when(conversationMapper.selectById(5L)).thenReturn(owned);

        assertSame(owned, store.requireOwned(5L, 1L));
    }

    // ===================== 过期 =====================

    @Test
    @DisplayName("过期判定：到期时刻之前算有效，之后算过期，没设过期时间也算过期（宁可要求重来）")
    void isExpired() {
        AiConversation expired = conversation(1L, 1L, 0);
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertTrue(store.isExpired(expired));

        assertFalse(store.isExpired(conversation(1L, 1L, 0)));

        AiConversation noExpiry = conversation(1L, 1L, 0);
        noExpiry.setExpiresAt(null);
        assertTrue(store.isExpired(noExpiry));
    }

    // ===================== 开始一轮：会话级占用 =====================

    private ConversationStore.ClaimOutcome beginTurn(AiConversation conversation, String requestId) {
        return store.beginTurn(conversation, requestId,
                LocalDateTime.now().minusMinutes(3), LocalDateTime.now());
    }

    @Test
    @DisplayName("★ 抢到会话：插入「处理中」占位行（它是这一轮的落点，不是锁）")
    void beginTurn_insertsPlaceholder() {
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.selectUserMessageForUpdate(5L, "r1"))
                .thenReturn(userMessage("r1", "我丢了一部手机"));
        when(conversationMapper.claimTurn(anyLong(), any(), anyString(), any(), any())).thenReturn(1);

        ConversationStore.ClaimOutcome outcome = beginTurn(conversation(5L, 1L, 0), "r1");

        assertEquals(ConversationStore.Claim.CLAIMED, outcome.claim());
        ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        assertEquals(AiMessage.ROLE_ASSISTANT, captor.getValue().getRole());
        assertEquals(AiMessage.STATUS_IN_PROGRESS, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("★ 会话已被别人占用：占用语句影响 0 行 → BUSY（同一段对话同时只跑一轮）")
    void beginTurn_reportsBusyWhenConversationIsTaken() {
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(conversationMapper.claimTurn(anyLong(), any(), anyString(), any(), any())).thenReturn(0);
        when(conversationMapper.selectById(5L)).thenReturn(conversation(5L, 1L, 0));

        ConversationStore.ClaimOutcome outcome = beginTurn(conversation(5L, 1L, 0), "r1");

        assertEquals(ConversationStore.Claim.BUSY, outcome.claim());
        // 抢不到就什么都不写 —— 那一轮正由别人负责，我们再插一条占位只会让两轮打架
        verify(messageMapper, never()).insert(any(AiMessage.class));
    }

    @Test
    @DisplayName("★ 占用抢不到且版本已经变了 → 明确 409 提示刷新（不是含糊的 BUSY）")
    void beginTurn_reportsVersionConflict() {
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(conversationMapper.claimTurn(anyLong(), any(), anyString(), any(), any())).thenReturn(0);
        when(conversationMapper.selectById(5L)).thenReturn(conversation(5L, 1L, 7));

        BusinessException e = assertThrows(BusinessException.class,
                () -> beginTurn(conversation(5L, 1L, 3), "r1"));

        assertEquals(ResultCode.CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("刷新"));
    }

    @Test
    @DisplayName("★ 这一轮已经完成：占用抢不到但结果是 OK → 告诉调用方去重放，别再来一次")
    void beginTurn_reportsAlreadyDone() {
        AiMessage finished = assistant("r1");
        finished.setStatus(AiMessage.STATUS_OK);
        when(messageMapper.selectOne(any())).thenReturn(finished);
        when(conversationMapper.claimTurn(anyLong(), any(), anyString(), any(), any())).thenReturn(0);

        ConversationStore.ClaimOutcome outcome = beginTurn(conversation(5L, 1L, 0), "r1");

        assertEquals(ConversationStore.Claim.ALREADY_DONE, outcome.claim());
        assertSame(finished, outcome.message());
    }

    @Test
    @DisplayName("上一轮失败了：把那一行拉回「处理中」重跑，而不是再插一条")
    void beginTurn_reusesFailedRow() {
        AiMessage failed = assistant("r1");
        failed.setId(77L);
        failed.setStatus(AiMessage.STATUS_FAILED);
        failed.setVersion(2);
        when(messageMapper.selectOne(any())).thenReturn(failed);
        when(messageMapper.selectUserMessageForUpdate(5L, "r1"))
                .thenReturn(userMessage("r1", "我丢了一部手机"));
        when(conversationMapper.claimTurn(anyLong(), any(), anyString(), any(), any())).thenReturn(1);
        when(messageMapper.updateById(any(AiMessage.class))).thenReturn(1);

        beginTurn(conversation(5L, 1L, 0), "r1");

        ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
        verify(messageMapper).updateById(captor.capture());
        assertEquals(77L, captor.getValue().getId(), "同一轮只有一条助手消息");
        assertEquals(2, captor.getValue().getVersion(), "接管要带版本号做 CAS");
    }

    // ===================== 结束这一轮 =====================

    @Test
    @DisplayName("★ 正常收尾：先 CAS 写助手消息，再写摘要并释放占用（顺序不能反）")
    void finishWithSummary_writesMessageThenSummary() {
        when(messageMapper.updateById(any(AiMessage.class))).thenReturn(1);
        when(conversationMapper.finishWithSummary(anyLong(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(1);

        store.finishWithSummary(conversation(5L, 1L, 3), claimed(77L, 4), assistant("r1"),
                "{\"conditions\":[]}", LocalDateTime.now().plusDays(7));

        org.mockito.InOrder order = inOrder(messageMapper, conversationMapper);
        order.verify(messageMapper).updateById(any(AiMessage.class));
        order.verify(conversationMapper).finishWithSummary(anyLong(), any(), anyString(),
                anyString(), any(), any());

        org.mockito.ArgumentCaptor<AiMessage> captor =
                org.mockito.ArgumentCaptor.forClass(AiMessage.class);
        verify(messageMapper).updateById(captor.capture());
        assertEquals(4, captor.getValue().getVersion(), "终态写入要带版本号做 CAS");
        assertEquals(AiMessage.STATUS_OK, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("★ 占用已被接管（消息 CAS 失败）→ 抛异常回滚，绝不提交任何状态更新")
    void finishWithSummary_rollsBackWhenSuperseded() {
        // 另一个请求已经接管并写完这一轮：我们手上的版本过期，影响 0 行
        when(messageMapper.updateById(any(AiMessage.class))).thenReturn(0);

        assertThrows(ConversationStore.TurnSupersededException.class,
                () -> store.finishWithSummary(conversation(5L, 1L, 3), claimed(77L, 4),
                        assistant("r1"), "{}", LocalDateTime.now().plusDays(7)));

        // 关键：**摘要一步都不能走**。否则会出现"记忆按旧结果推进了、回答却没落库"
        verify(conversationMapper, never()).finishWithSummary(anyLong(), any(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("★ 摘要版本对不上（另一轮先写了）→ 同样回滚，连助手消息一起作废")
    void finishWithSummary_rollsBackWhenVersionChanged() {
        when(messageMapper.updateById(any(AiMessage.class))).thenReturn(1);
        when(conversationMapper.finishWithSummary(anyLong(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(0);

        assertThrows(ConversationStore.TurnSupersededException.class,
                () -> store.finishWithSummary(conversation(5L, 1L, 3), claimed(77L, 4),
                        assistant("r1"), "{}", LocalDateTime.now().plusDays(7)));
    }

    @Test
    @DisplayName("失败的一轮：只释放占用并续期，摘要内容一个字都不动")
    void finishWithoutSummary_doesNotTouchSummary() {
        when(messageMapper.updateById(any(AiMessage.class))).thenReturn(1);
        when(conversationMapper.finishWithoutSummary(anyLong(), any(), anyString(), any(), any()))
                .thenReturn(1);
        AiConversation current = conversation(5L, 1L, 3);
        current.setSummaryJson("{\"conditions\":[{\"field\":\"颜色\"}]}");

        store.finishWithoutSummary(current, claimed(77L, 4), failedAssistant("r1"),
                LocalDateTime.now().plusDays(7));

        // 失败路径走的是**不接受摘要参数**的那条语句 —— 结构上就不可能改到摘要，
        // 比"传一份原封不动的摘要进去"更可靠
        verify(conversationMapper).finishWithoutSummary(eq(5L), eq(3), eq("r1"), any(), any());
        verify(conversationMapper, never()).finishWithSummary(anyLong(), any(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("失败轮次收尾时发现占用已被接管 → 抛异常，本次全部作废")
    void finishWithoutSummary_rollsBackWhenSuperseded() {
        when(messageMapper.updateById(any(AiMessage.class))).thenReturn(1);
        when(conversationMapper.finishWithoutSummary(anyLong(), any(), anyString(), any(), any()))
                .thenReturn(0);

        assertThrows(ConversationStore.TurnSupersededException.class,
                () -> store.finishWithoutSummary(conversation(5L, 1L, 3), claimed(77L, 4),
                        failedAssistant("r1"), LocalDateTime.now().plusDays(7)));
    }

    // ===================== 幂等 =====================

    @Test
    @DisplayName("★ 同一个 requestId 的用户消息已存在：不再插一条（否则界面上同一句话出现两遍）；"
            + "如实报告『不是这次插的』")
    void saveUserMessageIfAbsent_skipsWhenAlreadyPresent() {
        when(messageMapper.selectOne(any())).thenReturn(userMessage("r1", "我丢了一部手机"));

        assertFalse(store.saveUserMessageIfAbsent(5L, "r1", "我丢了一部手机", null),
                "行是先前那一次的 —— 调用方据此判断被拒时不该收回它");

        verify(messageMapper, never()).insert(any(AiMessage.class));
    }

    @Test
    @DisplayName("★ 同一个 requestId 配不同内容：拒绝 —— 否则重放会答非所问，记录也对不上账")
    void saveUserMessageIfAbsent_rejectsDifferentContent() {
        when(messageMapper.selectOne(any())).thenReturn(userMessage("r1", "我丢了一部手机"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> store.saveUserMessageIfAbsent(5L, "r1", "其实我要找的是钥匙", null));

        assertEquals(ResultCode.CONFLICT, e.getCode());
        verify(messageMapper, never()).insert(any(AiMessage.class));
    }

    @Test
    @DisplayName("并发撞上唯一键：按幂等处理（记日志放行），而不是升级成 500")
    void saveUserMessageIfAbsent_swallowsDuplicateKey() {
        when(messageMapper.selectOne(any())).thenReturn(null, userMessage("r1", "手机"));
        when(messageMapper.insert(any(AiMessage.class))).thenThrow(
                new org.springframework.dao.DuplicateKeyException("uk_conv_request_role"));

        assertFalse(store.saveUserMessageIfAbsent(5L, "r1", "手机", null));
    }

    // ===================== 未被接纳的用户消息（缺陷 2：孤儿行） =====================

    @Test
    @DisplayName("★ 没有助手行、占用也不属于这个 requestId → 这一句没有下文，收回（补偿 409 拒绝留下的孤儿行）")
    void discardUserMessageIfOrphan_deletesTrulyOrphanedRow() {
        when(messageMapper.selectOne(any())).thenReturn(null);                    // 没有助手行
        when(conversationMapper.lockById(5L)).thenReturn(conversation(5L, 1L, 0)); // 占用空闲（锁定读）
        when(messageMapper.delete(any())).thenReturn(1);

        store.discardUserMessageIfOrphan(5L, "r2");

        verify(messageMapper).delete(any());
    }

    @Test
    @DisplayName("已有助手行（这一轮正跑着或已跑完）：不动它的用户消息")
    void discardUserMessageIfOrphan_keepsWhenAssistantExists() {
        when(conversationMapper.lockById(5L)).thenReturn(conversation(5L, 1L, 0));
        when(messageMapper.selectOne(any())).thenReturn(assistant("r2"));

        store.discardUserMessageIfOrphan(5L, "r2");

        verify(messageMapper, never()).delete(any());
        verify(conversationMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("★ 占用正属于这个 requestId（同 requestId 的并发重试在跑）：不收回 —— "
            + "删了会让进行中的那一轮失去原话")
    void discardUserMessageIfOrphan_keepsWhenOccupiedBySameRequestId() {
        when(messageMapper.selectOne(any())).thenReturn(null);
        AiConversation occupied = conversation(5L, 1L, 0);
        occupied.setActiveRequestId("r2");
        when(conversationMapper.lockById(5L)).thenReturn(occupied);

        store.discardUserMessageIfOrphan(5L, "r2");

        verify(messageMapper, never()).delete(any());
    }

    @Test
    @DisplayName("★ 竞态不变量：同一 requestId 的「收回孤儿」与「抢占」互斥 —— "
            + "清理持锁后再判再删；清理先删则抢占作废（不写占位行）；原话还在则照常抢到")
    void discardAndClaim_sameRequestIdRaceInvariant() {
        // ---- 交错 ①：清理先拿到会话行锁 → 判断与删除必须整段落在这把锁之内 ----
        // 锁的是 claimTurn 要 UPDATE 的同一条行锁，所以抢占插不进「读占用 → 删除」之间
        when(conversationMapper.lockById(5L)).thenReturn(conversation(5L, 1L, 0));
        when(messageMapper.selectOne(any())).thenReturn(null);   // 没有助手行
        when(messageMapper.delete(any())).thenReturn(1);

        store.discardUserMessageIfOrphan(5L, "r2");

        org.mockito.InOrder order = inOrder(conversationMapper, messageMapper);
        order.verify(conversationMapper).lockById(5L);      // ① 先拿行锁
        order.verify(messageMapper).selectOne(any());       // ② 守卫在锁之后读
        order.verify(messageMapper).delete(any());          // ③ 删除最后才发生

        // ---- 交错 ②：清理已经先删掉原话 → 抢占成立必须整体作废 ----
        when(conversationMapper.claimTurn(anyLong(), any(), anyString(), any(), any())).thenReturn(1);
        when(messageMapper.selectUserMessageForUpdate(5L, "r1")).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> beginTurn(conversation(5L, 1L, 0), "r1"));

        assertEquals(ResultCode.CONFLICT, e.getCode());
        // claimTurn 的结果随异常回滚，占位行一条都不写 —— 不会留下「只有助手、没有原话」的一轮
        verify(messageMapper, never()).insert(any(AiMessage.class));

        // ---- 相邻行为不被改坏：原话还在（正常请求 / 同 requestId 幂等重试）→ 照常抢到 ----
        when(messageMapper.selectUserMessageForUpdate(5L, "r1"))
                .thenReturn(userMessage("r1", "我丢了一部手机"));

        ConversationStore.ClaimOutcome outcome = beginTurn(conversation(5L, 1L, 0), "r1");

        assertEquals(ConversationStore.Claim.CLAIMED, outcome.claim());
        verify(messageMapper, times(1)).insert(any(AiMessage.class));
        // 旧实现里那句非锁定的 selectById 不复存在：它读到的状态在删除前随时会被抢占改掉
        verify(conversationMapper, never()).selectById(any());
    }

    // ===================== 清理 =====================

    @Test
    @DisplayName("清理过期会话：先删消息再删会话（反过来会留下永远关联不到会话的孤儿消息）")
    void purgeAllExpired_deletesMessagesFirst() {
        AiConversation expired = conversation(5L, 1L, 0);
        when(conversationMapper.selectList(any())).thenReturn(java.util.List.of(expired));
        when(conversationMapper.delete(any())).thenReturn(1);

        int removed = store.purgeAllExpired();

        assertEquals(1, removed);
        org.mockito.InOrder order = inOrder(messageMapper, conversationMapper);
        order.verify(messageMapper).delete(any());
        order.verify(conversationMapper).delete(any());
    }

    @Test
    @DisplayName("没有过期会话时不发删除语句（每天空跑一次不该产生无谓的写）")
    void purgeAllExpired_noopWhenNothingExpired() {
        when(conversationMapper.selectList(any())).thenReturn(java.util.List.of());

        assertEquals(0, store.purgeAllExpired());
        verify(messageMapper, never()).delete(any());
        verify(conversationMapper, never()).delete(any());
    }

    private static AiMessage assistant(String requestId) {
        AiMessage message = new AiMessage();
        message.setConversationId(5L);
        message.setRequestId(requestId);
        message.setRole(AiMessage.ROLE_ASSISTANT);
        message.setContent("回复");
        message.setStatus(AiMessage.STATUS_OK);
        message.setPayloadJson("{}");
        return message;
    }

    private static AiMessage failedAssistant(String requestId) {
        AiMessage message = assistant(requestId);
        message.setStatus(AiMessage.STATUS_FAILED);
        return message;
    }

    /** 已经抢到的占位（带版本号，终态写入要拿它做 CAS） */
    private static AiMessage claimed(Long id, int version) {
        AiMessage message = new AiMessage();
        message.setId(id);
        message.setConversationId(5L);
        message.setRequestId("r1");
        message.setRole(AiMessage.ROLE_ASSISTANT);
        message.setStatus(AiMessage.STATUS_IN_PROGRESS);
        message.setVersion(version);
        return message;
    }

    private static AiMessage userMessage(String requestId, String content) {
        AiMessage message = new AiMessage();
        message.setConversationId(5L);
        message.setRequestId(requestId);
        message.setRole(AiMessage.ROLE_USER);
        message.setContent(content);
        message.setStatus(AiMessage.STATUS_OK);
        return message;
    }
}
