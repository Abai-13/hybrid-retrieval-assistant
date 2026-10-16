package com.lostfound.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lostfound.common.BusinessException;
import com.lostfound.common.ResultCode;
import com.lostfound.dto.SearchSummary;
import com.lostfound.entity.AiConversation;
import com.lostfound.entity.AiMessage;
import com.lostfound.mapper.AiConversationMapper;
import com.lostfound.mapper.AiMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话与消息的持久化 —— <b>所有涉及所有权和并发的地方都收在这里</b>。
 *
 * <p><b>为什么单独一个类，而不是把这些方法写在 {@code ConversationServiceImpl} 里</b>：
 * Spring 的事务注解基于代理生效，<b>同一个类内部的自调用不会走代理</b>，
 * {@code @Transactional} 会静默失效。会话编排里有"调模型"这种耗时动作，
 * 绝不能包在事务里；把「短事务的落库」和「不持事务的编排」分成两个 Bean，
 * 是让这条边界在代码结构上就成立，而不是靠调用者记得别写错。
 *
 * <p>另外：读方法一律不标事务（单条查询本来就是原子的），写方法才标。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationStore {

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

    // ===================== 会话 =====================

    public AiConversation create(Long userId, LocalDateTime expiresAt) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setSummaryJson(SearchSummary.empty().toJson());
        conversation.setExpiresAt(expiresAt);
        // @Version 字段必须显式给初值：MyBatis-Plus 的乐观锁插件靠它决定要不要加 WHERE。
        // 库里有 DEFAULT 0，但 insert 时实体里是 null 就会写进 NULL，之后每次更新都对不上。
        conversation.setVersion(0);
        conversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 当前用户**未过期**的最近一个会话。
     * <p>「刷新恢复」靠的就是它：只按 userId 查，前端传来的任何 id 都不参与，
     * 所以刷新、换标签页、甚至换设备，只要还是同一个账号，拿到的就是同一段记忆。
     */
    public AiConversation findCurrent(Long userId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, userId)
                .gt(AiConversation::getExpiresAt, LocalDateTime.now())
                .orderByDesc(AiConversation::getId)
                .last("LIMIT 1"));
    }

    /**
     * 按 id 取会话并**校验所有权** —— 会话 id 来自客户端，属于不可信输入。
     *
     * <p>两种情况分开报是必要的：
     * <ul>
     *   <li>不存在 → 404：可能是被清理了；</li>
     *   <li>存在但不是你的 → <b>403，不是 404</b>。抹成 404 看起来很"安全"，
     *       但会把"会话过期被清理了"和"这是别人的会话"混成同一件事，
     *       前端没法给出正确引导，我们排查时也分不清是过期还是越权尝试。</li>
     * </ul>
     */
    public AiConversation requireOwned(Long conversationId, Long userId) {
        if (conversationId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话 id 不能为空");
        }
        if (userId == null) {
            // 不该发生（调用方已经拦过），但这里是所有会话读写的必经之路 ——
            // 让它在这一层彻底不可能通过，比依赖每个调用方都记得先校验更可靠
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录后再使用找物助手");
        }
        AiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在或已被清理，请开始新对话");
        }
        if (!conversation.getUserId().equals(userId)) {
            log.warn("越权访问会话：conversationId={}, 会话属主={}, 当前用户={}",
                    conversationId, conversation.getUserId(), userId);
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该会话");
        }
        return conversation;
    }

    /**
     * 会话是否已过期。
     * <p>过期不是"自动续命"：记忆是有保质期的，用户过两天回来看到
     * 一堆自己都不记得说过的条件，比干净地重新开始更糟。所以过期就明确提示重新开始。
     */
    public boolean isExpired(AiConversation conversation) {
        return conversation.getExpiresAt() == null
                || conversation.getExpiresAt().isBefore(LocalDateTime.now());
    }

    // ===================== 消息 =====================

    /**
     * 会话内最后 limit 条消息，按时间正序返回（给模型做上下文用）。
     *
     * <p>⚠️ <b>「处理中」的占位行一律不返回</b>：它还没有内容，
     * 混进历史里会让模型以为"助手已经答过一句空的"，也会白占掉一个最近轮次的名额。
     * 它只是并发控制用的占位，不是对话内容。
     */
    public List<AiMessage> recentMessages(Long conversationId, int limit) {
        List<AiMessage> latestFirst = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .ne(AiMessage::getStatus, AiMessage.STATUS_IN_PROGRESS)
                .orderByDesc(AiMessage::getId)
                .last("LIMIT " + Math.max(1, limit)));
        // 倒序取"最近 N 条"再翻回正序 —— 直接正序取 N 条拿到的是最早 N 条，
        // 那样上下文里永远是开头几句，用户说到第五轮模型还在看第一轮
        List<AiMessage> chronological = new ArrayList<>(latestFirst);
        java.util.Collections.reverse(chronological);
        return chronological;
    }

    /** 会话内全部消息（有上限），按时间正序 —— 给前端恢复页面用 */
    public List<AiMessage> messages(Long conversationId, int limit) {
        return recentMessages(conversationId, limit);
    }

    public AiMessage findAssistant(Long conversationId, String requestId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .eq(AiMessage::getRequestId, requestId)
                .eq(AiMessage::getRole, AiMessage.ROLE_ASSISTANT)
                .last("LIMIT 1"));
    }

    public AiMessage findUserMessage(Long conversationId, String requestId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .eq(AiMessage::getRequestId, requestId)
                .eq(AiMessage::getRole, AiMessage.ROLE_USER)
                .last("LIMIT 1"));
    }

    // ===================== 一轮的占用（并发控制） =====================

    /** 抢占这一轮的结果 */
    public enum Claim {
        /** 抢到了，可以开始跑 */
        CLAIMED,
        /** 这一轮早就完成了 —— 直接重放，不要再调模型 */
        ALREADY_DONE,
        /** 另一个请求正在跑这一轮 —— 明确拒绝，不要重复干活 */
        BUSY
    }

    /** 抢占结果 + 当前那条助手消息（若已存在） */
    public record ClaimOutcome(Claim claim, AiMessage message) {
    }

    /**
     * 开始一轮 —— 占用这段会话，并立起这一轮的助手占位行。
     *
     * <p><b>调用顺序有要求</b>：必须**先** {@link #saveUserMessageIfAbsent} 落用户原话，
     * 再调它。消息按自增 id 排序，顺序反了助手那行会拿到比用户更小的 id，
     * 刷新之后界面上就变成"助手先说话、用户后说话"（第一轮审查复现过）。
     *
     * <p>为什么两者不合成一个事务：落用户原话是"先查后插"，
     * 两条同 requestId 的请求并发时会撞死锁；把它放进这里的事务里，
     * 一旦撞上，整个事务已经被 InnoDB 回滚，后面无论怎么补救都是在一条死掉的事务上继续写。
     * 拆开之后，各是单条语句的自动提交，撞了就地恢复、互不影响。
     *
     * <p><b>占用不是锁死</b>：占用时间超过 {@code staleBefore} 视为陈旧，后来者可以接管。
     * 否则进程在模型调用中途挂掉，这段会话就永远卡在"处理中"——
     * 一个偶发故障会变成永久故障。
     *
     * <p><b>与 {@link #discardUserMessageIfOrphan} 的竞态</b>（两侧都要改，缺一边就不成立）：
     * <ul>
     *   <li>那边先动手 → 它删掉了本轮的原话 → 这里抢占后必须复查原话还在，
     *       不在就让抢占整体作废（抛 409 回滚），否则留下一条只有助手的回复；</li>
     *   <li>这边先动手 → 占用已属于本 requestId → 那边拿到会话行锁后读到的
     *       正是它，守卫跳过删除。</li>
     * </ul>
     * 两边争的是<b>同一条会话行锁</b>（{@code claimTurn} 的 UPDATE ↔ {@code lockById}），
     * 所以无论谁先谁后，都只会走到上面其中一种结果，不存在第三种交错。
     */
    @Transactional
    public ClaimOutcome beginTurn(AiConversation conversation, String requestId,
                                  LocalDateTime staleBefore, LocalDateTime now) {
        int claimed = conversationMapper.claimTurn(conversation.getId(), conversation.getVersion(),
                requestId, staleBefore, now);
        if (claimed == 0) {
            // 没抢到有两种可能：别人正在跑，或者版本已经变了。分开报，用户的下一步动作不一样
            AiMessage finished = findAssistant(conversation.getId(), requestId);
            if (finished != null && AiMessage.STATUS_OK.equals(finished.getStatus())) {
                return new ClaimOutcome(Claim.ALREADY_DONE, finished);
            }
            AiConversation latest = conversationMapper.selectById(conversation.getId());
            if (latest == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在或已被清理，请开始新对话");
            }
            if (latest.getVersion() != null && !latest.getVersion().equals(conversation.getVersion())) {
                throw new BusinessException(ResultCode.CONFLICT, "你看到的这段对话已经变了，请刷新后再发送");
            }
            return new ClaimOutcome(Claim.BUSY, null);
        }

        // ② 抢占成立（claimTurn 这条 UPDATE 的行锁此刻正握在本事务手里）→
        //    复查这一轮的原话还在。收回孤儿的那次清理要么已经先完成（这行没了，
        //    必须让抢占作废），要么被这把行锁挡在后面（它随后会读到占用属于本
        //    requestId 而跳过删除）。**锁定读，不是普通 SELECT**：普通 SELECT 走
        //    快照，看不到"清理在我们抢到锁之前刚提交的删除"，检查就成了摆设。
        //
        //    不做这道检查的后果：抢下一个没有原话的轮次，落一条只有助手、
        //    没有用户原话的回复 —— 正是这次要修的竞态的另一半。
        if (messageMapper.selectUserMessageForUpdate(conversation.getId(), requestId) == null) {
            log.warn("本轮的用户原话已被收回，抢占作废（不产生只有助手、没有原话的一轮）: conversationId={}, requestId={}",
                    conversation.getId(), requestId);
            // 抛异常 → 本事务回滚 → 上面的 claimTurn 一并作废，占用没有真的易主。
            // 调用方会把这条 409 透给客户端：客户端用同一个 requestId 重发即可
            // （原话会被重新落库，再走一次抢占）
            throw new BusinessException(ResultCode.CONFLICT,
                    "这条消息刚刚被并发处理收回了，请重新发送一次");
        }

        // ③ 助手占位行（此时它的 id 一定大于用户那行）
        return new ClaimOutcome(Claim.CLAIMED, preparePlaceholder(conversation.getId(), requestId));
    }

    /** 准备（或接管）这一轮的助手占位行，返回带当前版本号的它 */
    private AiMessage preparePlaceholder(Long conversationId, String requestId) {
        AiMessage existing = findAssistant(conversationId, requestId);
        if (existing == null) {
            AiMessage placeholder = new AiMessage();
            placeholder.setConversationId(conversationId);
            placeholder.setRequestId(requestId);
            placeholder.setRole(AiMessage.ROLE_ASSISTANT);
            placeholder.setStatus(AiMessage.STATUS_IN_PROGRESS);
            placeholder.setVersion(0);
            try {
                messageMapper.insert(placeholder);
            } catch (DuplicateKeyException e) {
                log.info("同一轮的占位已存在（并发或重试）: conversationId={}, requestId={}",
                        conversationId, requestId);
            }
            return findAssistant(conversationId, requestId);
        }
        if (AiMessage.STATUS_OK.equals(existing.getStatus())) {
            return existing;
        }
        // 上次失败了 → 把这一行重新拉回「处理中」，而不是再插一条
        // （同一轮只能有一条助手消息，否则界面上会出现两条互相矛盾的回复）
        AiMessage reset = new AiMessage();
        reset.setId(existing.getId());
        reset.setVersion(existing.getVersion());
        reset.setStatus(AiMessage.STATUS_IN_PROGRESS);
        messageMapper.updateById(reset);
        return findAssistant(conversationId, requestId);
    }

    /**
     * 把占位写成终态。**写不进去就抛异常，让整个事务回滚**。
     *
     * <p>为什么必须抛而不是只记日志：这一步写不进去，说明这一轮的占用早就被别人接管了。
     * 此时如果继续往下提交摘要，就会出现"摘要按旧执行者的结果推进了，
     * 但对应的助手消息根本没落库"—— 记忆和回答对不上账，而且没人能解释为什么。
     * 抛异常 → 事务回滚 → 这一轮的所有状态更新一起作废，干干净净。
     */
    private void writeFinished(AiMessage claimed, AiMessage finished) {
        finished.setId(claimed.getId());
        finished.setConversationId(claimed.getConversationId());
        finished.setRequestId(claimed.getRequestId());
        finished.setRole(AiMessage.ROLE_ASSISTANT);
        finished.setVersion(claimed.getVersion());
        if (messageMapper.updateById(finished) == 0) {
            log.warn("这一轮的占用已被接管，本次结果作废（不覆盖任何已完成的状态）: conversationId={}, requestId={}",
                    claimed.getConversationId(), claimed.getRequestId());
            throw new TurnSupersededException();
        }
    }

    /**
     * 这一轮的占用已经不属于我们了（被接管/已完成）—— 触发事务回滚。
     *
     * <p>继承 {@link BusinessException} 并带上 {@code 409}：它是一条<strong>业务结果</strong>
     * （"你这次没赶上"），不是程序故障。万一它从某个没料到的路径逃出去，
     * 全局异常处理器也能给出正确语义 —— 而不是掉进兜底变成 500，
     * 让用户看到一句"服务器内部错误"却其实什么都没坏。
     */
    public static class TurnSupersededException extends BusinessException {
        TurnSupersededException() {
            super(ResultCode.CONFLICT, "这一轮的处理权已经被接管，本次结果不再写入");
        }
    }

    /**
     * 落用户这一轮的原话 —— <b>已存在就什么都不做，但内容必须一致</b>。
     *
     * <p>重试会带着同一个 requestId 再来一次，这时用户消息已经在库里了，
     * 再插一条，界面上就会看到同一句话说了两遍。
     *
     * <p>唯一键 {@code uk_conv_request_role} 是最终保证：先查后插之间有竞态，
     * 撞上唯一键说明另一个并发的同 requestId 请求已经插过了 —— 那正是我们想要的结果，
     * 记一条日志就好，不升级成错误。（这和认领流程里"唯一键撞了要翻译成 409"
     * 是两回事：那里撞键代表业务冲突，这里撞键代表幂等生效。）
     *
     * <p>⚠️ <b>同一个 requestId 配不同内容必须拒绝</b>：requestId 是幂等键，
     * 后端会拿它去重放历史结果。要是允许"换个内容复用旧 requestId"，
     * 用户会收到一段和历史输入完全对不上的回答，而记录里还留着旧的那句话 ——
     * 之后无论怎么排查都对不上账。
     *
     * @param payloadJson 用户消息附带的结构化说明（目前只用于结构化撤回，
     *                    见 {@link #findUserMessage} 的调用方）——重试时要能原样重放
     * @return true 表示<b>这一调用插入了新行</b>；false 表示行已存在（幂等放行）——
     *         调用方据此决定"这一轮没被接纳时要不要收回这行"（见
     *         {@link #discardUserMessageIfOrphan}）
     */
    public boolean saveUserMessageIfAbsent(Long conversationId, String requestId, String content,
                                           String payloadJson) {
        AiMessage existing = findUserMessage(conversationId, requestId);
        if (existing != null) {
            assertSameContent(requestId, content, existing);
            return false;
        }
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setRequestId(requestId);
        message.setRole(AiMessage.ROLE_USER);
        message.setContent(content);
        message.setPayloadJson(payloadJson);
        message.setStatus(AiMessage.STATUS_OK);
        message.setVersion(0);
        try {
            messageMapper.insert(message);
            return true;
        } catch (DuplicateKeyException | DeadlockLoserDataAccessException e) {
            // 同一个 requestId 被并发发两次时，"先查后插"天然会撞车 ——
            // 表现有两种：撞唯一键，或者两条 insert 互相等对方的插入意向锁、被 InnoDB 判成死锁。
            // **它们是同一件事的两种表现**，所以处理方式也必须一样：
            // 确认那一行确实在（在就按幂等放行），不在就明确告诉客户端"重试"。
            //
            // ⚠️ 死锁曾经漏成 500：那是把"两条请求撞在一起"这种正常并发现象
            //    报成了服务器故障，用户看到的是一句"服务器内部错误"，
            //    而重发一次本来就好了。
            log.info("同一 requestId 的用户消息插入撞车（唯一键或死锁），按幂等处理: conversationId={}, requestId={}",
                    conversationId, requestId);
            AiMessage winner = findUserMessage(conversationId, requestId);
            if (winner == null) {
                // 死锁把这次插入整个回滚了，那一行并不存在 —— 不能假装成功，
                // 也不该报 500。让客户端重发一次即可
                throw new BusinessException(ResultCode.CONFLICT, "这条消息正在被处理，请稍后重试");
            }
            assertSameContent(requestId, content, winner);
            // 行是并发那一份的，不是这次调用插的 —— 收回的职责不归我们
            return false;
        }
    }

    /**
     * 收回一条**没有被任何一轮接纳**的用户消息（"孤儿消息"）。
     *
     * <p>缺陷场景：会话正被 A 轮占用时，B 轮带着<b>另一个 requestId</b> 到达 ——
     * B 在占用判定（409 BUSY）之前已经把自己的原话落了库。占用没抢到，
     * 这一轮永远不会有对应的助手行，那句话就永久留在历史里、没有任何回复，
     * 刷新后前端只能靠"孤儿轮"兜底给一个假的失败条目。
     *
     * <p>两个守卫缺一不可，都是为了不误删<b>正在被使用</b>的行：
     * <ul>
     *   <li>已有助手行（占位或终态）→ 这一轮其实跑着/跑过了，不能动；</li>
     *   <li>占用正属于这个 requestId → 同 requestId 的并发重试正在跑这一轮，
     *       它的原话正是这行；删掉会让它收尾时留下一条没有用户消息的回复。</li>
     * </ul>
     *
     * <p><b>只读一次占用再删是不够的，两个守卫都必须在行锁里读</b>：
     * {@link #beginTurn} 的 {@code claimTurn} 是一条 UPDATE，它不需要等这里。
     * 读到「占用不归我」和删除之间，抢占完全可以插进来成立 —— 于是这一删
     * 删掉的正是刚抢到这一轮的那行原话（交错 ①：先判后删，判的时候还没抢）。
     * 所以第一步是 {@code lockById}：抢的和 {@code claimTurn} 是同一条会话行锁，
     * 谁先拿到锁谁先走，另一边只能等它提交后再重读 —— 读到的必然是已成定局的状态。
     *
     * <p>反过来那一半（交错 ②：清理先删、抢占后到）由 {@link #beginTurn} 兜住：
     * 抢占成立后用锁定读复查原话还在，不在就整体作废。两侧合起来，
     * 「删」和「抢」对同一个 requestId 才真正互斥。
     *
     * <p>只删 USER 行、按 requestId 精确匹配：这是一次补偿动作，
     * 范围必须小到不可能碰到别的轮次 —— 别的 requestId 正在跑的那一行，
     * 在两个守卫下也碰不到（占用属于它，或它已有助手行）。
     */
    @Transactional
    public void discardUserMessageIfOrphan(Long conversationId, String requestId) {
        // ① 先锁会话行（与 claimTurn 同一条行锁），临界区 = 本短事务
        AiConversation conversation = conversationMapper.lockById(conversationId);

        // ② 守卫：必须在锁内读，锁前读到的不算数
        if (findAssistant(conversationId, requestId) != null) {
            return;
        }
        if (conversation != null && requestId.equals(conversation.getActiveRequestId())) {
            log.warn("这一轮的占用正属于该 requestId，不收回它的用户消息: conversationId={}, requestId={}",
                    conversationId, requestId);
            return;
        }

        // ③ 删除：此时若已有并发抢占成立，它要么在我们锁前就提交（守卫②已挡下），
        //    要么被这把锁挡在后面（等我们提交后它会看到原话已不在 → 抢占作废）
        int rows = messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .eq(AiMessage::getRequestId, requestId)
                .eq(AiMessage::getRole, AiMessage.ROLE_USER));
        if (rows > 0) {
            log.info("收回未被接纳的用户消息（本轮在写入后被拒绝）: conversationId={}, requestId={}, {} 行",
                    conversationId, requestId, rows);
        }
    }

    /** 同一 requestId 的历史内容与本次不一致 → 拒绝（见 {@link #saveUserMessageIfAbsent}） */
    public void assertSameContent(String requestId, String content, AiMessage existing) {
        if (existing.getContent() != null && !existing.getContent().equals(content)) {
            log.warn("同一个 requestId 被用于不同内容: requestId={}", requestId);
            throw new BusinessException(ResultCode.CONFLICT,
                    "这个请求标识已经用过一次了，请刷新页面后重新发送");
        }
    }

    /**
     * 一次短事务里写完「更新摘要（带版本校验）+ 把这一轮写成终态」。
     *
     * <p><b>为什么这两件事必须同一个事务</b>：摘要更新成功、消息写入失败，
     * 会留下"记忆里多了个条件，但用户看不到是哪句话带来的"这种半截状态；
     * 反过来则是消息在、记忆不在 —— 刷新后那句话就"失效"了。
     *
     * <p>⚠️ 事务里<b>只有数据库操作</b>：模型调用在外面完成，事务边界不含它。
     * 一个 90 秒预算的轮次如果包在事务里，会长时间占着数据库连接和行锁。
     *
     * @return true 表示写入成功；false 表示<b>摘要版本冲突</b>（会话已被另一轮改过），
     *         这时本轮结果基于旧摘要算出来，必须整个作废，由调用方回 409
     */
    @Transactional
    public void finishWithSummary(AiConversation conversation, AiMessage claimed,
                                  AiMessage finished, String summaryJson, LocalDateTime expiresAt) {
        // ① 先写助手消息（CAS）—— 写不进去说明占用已被接管，直接抛异常回滚，
        //    绝不能让"摘要推进了、回答却没落库"这种半截状态提交出去
        writeFinished(claimed, finished);

        // ② 再写摘要 + 释放占用。所有权和版本号都写在 WHERE 里，
        //    少一个条件都可能让一个已经不属于我们的结果盖掉别人的
        int rows = conversationMapper.finishWithSummary(conversation.getId(),
                conversation.getVersion(), claimed.getRequestId(), summaryJson, expiresAt,
                LocalDateTime.now());
        if (rows == 0) {
            // 版本对不上（另一轮先写了）。绝不能"再查一次最新版重试"：
            // 手上这份摘要是基于旧版算的，强行写进去就是把那一轮的结果丢了
            log.warn("会话版本冲突，放弃本次摘要写入: conversationId={}, 本地版本={}",
                    conversation.getId(), conversation.getVersion());
            throw new TurnSupersededException();
        }
    }

    /**
     * 结束这一轮但**不推进摘要** —— 失败的那一轮走这条。
     *
     * <p>为什么不复用 {@link #finishWithSummary}：那条路动摘要，而失败的一轮本来就没打算改它。
     * 保留期照常续：用户确实还在用这段对话，"保留最近 7 天"说的就是最后一次活动。
     *
     * <p>⚠️ 失败时**回传的条件必须是上一次有效的**，不能是本轮合并了一半的那份 ——
     * 那是 R8 里的问题：面板上显示着新条件，服务端却还是旧条件，两边对不上，
     * 用户既不知道新条件生效没有，也没法把它撤回去。
     */
    @Transactional
    public void finishWithoutSummary(AiConversation conversation, AiMessage claimed,
                                     AiMessage finished, LocalDateTime expiresAt) {
        writeFinished(claimed, finished);

        int rows = conversationMapper.finishWithoutSummary(conversation.getId(),
                conversation.getVersion(), claimed.getRequestId(), expiresAt, LocalDateTime.now());
        if (rows == 0) {
            // 占用/版本已经不是我们的了：本次一切作废（消息写入也会一起回滚）
            log.warn("失败轮次收尾时发现占用已被接管，本次全部作废: conversationId={}", conversation.getId());
            throw new TurnSupersededException();
        }
    }

    /** 写入完成后回读最新版本号（返回给前端，便于它判断自己是不是最新的） */
    public Integer currentVersion(Long conversationId) {
        AiConversation latest = conversationMapper.selectById(conversationId);
        return latest == null ? null : latest.getVersion();
    }

    // ===================== 清理 =====================

    /** 清理某个用户已过期的会话（返回删掉的会话数） */
    @Transactional
    public int purgeExpiredOfUser(Long userId) {
        List<AiConversation> expired = conversationMapper.selectList(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getUserId, userId)
                        .lt(AiConversation::getExpiresAt, LocalDateTime.now()));
        return deleteAll(expired);
    }

    /**
     * 清理全站已过期的会话 —— 由定时任务调用。
     *
     * <p>为什么要有全站清理，而不只是"用户进来时顺手清自己的"：
     * 一个用户开了会话就再也没回来，他的数据永远不会被任何人访问到，
     * 也就永远不会被清理。只做惰性清理 = 只清活跃用户的数据，
     * 而不活跃用户的会话会一直留在库里。
     */
    @Transactional
    public int purgeAllExpired() {
        List<AiConversation> expired = conversationMapper.selectList(
                new LambdaQueryWrapper<AiConversation>()
                        .lt(AiConversation::getExpiresAt, LocalDateTime.now()));
        return deleteAll(expired);
    }

    private int deleteAll(List<AiConversation> conversations) {
        if (conversations.isEmpty()) {
            return 0;
        }
        List<Long> ids = conversations.stream().map(AiConversation::getId).toList();
        // 先删消息再删会话：反过来的话，中途失败会留下一堆孤儿消息，
        // 而它们再也关联不到任何会话，只能靠人工清理
        messageMapper.delete(new LambdaQueryWrapper<AiMessage>().in(AiMessage::getConversationId, ids));
        return conversationMapper.delete(new LambdaQueryWrapper<AiConversation>().in(AiConversation::getId, ids));
    }
}
