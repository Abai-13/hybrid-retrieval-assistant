package com.lostfound.service.impl;

import com.lostfound.common.BusinessException;
import com.lostfound.common.LlmTransport;
import com.lostfound.common.ResultCode;
import com.lostfound.dto.*;
import com.lostfound.entity.AiConversation;
import com.lostfound.entity.AiMessage;
import com.lostfound.entity.Item;
import com.lostfound.service.AiService;
import com.lostfound.service.CandidateService;
import com.lostfound.service.ConversationService;
import com.lostfound.service.HybridRecallService;
import com.lostfound.service.ItemService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 找物助手会话编排 —— 一次「轮次」是怎么跑完的。
 *
 * <p>流水线（每一步失败的方式都不一样，所以必须分开）：
 * <pre>
 *  ① 认领会话 / 判过期 / 重放已完成的轮次      ← 纯数据库，快
 *  ② 落用户原话（幂等）                        ← 短写；抢占被拒时收回这行
 *  ③ 理解这一轮            【模型调用 1，最多 1 次】
 *  ④ 校验并合并摘要（补充 / 纠正 / 撤回）      ← 纯代码，模型只提建议
 *  ⑤ 检索或解释            【模型调用 2，最多 1 次；不需要就不发】
 *  ⑥ 短事务落库：摘要 + 助手消息               ← 版本校验在这里
 * </pre>
 *
 * <p><b>为什么模型调用绝不包在事务里</b>：一轮的预算是 90 秒。
 * 如果事务把 ③⑤ 包住，就等于每个正在"思考"的会话都占着一条数据库连接和一把行锁 ——
 * 几十个用户同时聊天就能把连接池钉死在"等模型"上。
 * 所以事务边界只圈住 ⑥ 这一点点纯数据库操作（见 {@link ConversationStore}）。
 *
 * <p><b>失败时为什么不推进摘要</b>：一轮失败了，用户看到的是"这次没成功，可以重试"，
 * 而条件面板还是上一次成功的状态 —— 两边一致，重试就是把这一轮原样再跑一遍。
 * 如果失败时也把新条件写进去，就会出现"条件里有『食堂』，但没有任何一条回复是基于它的"
 * 这种半截状态：用户既不知道它生效没有，也没法把它撤回去。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationStore store;
    private final TurnInterpreter interpreter;
    /**
     * 召回入口 —— <b>这里是全项目唯一换成混合召回的地方</b>。
     * <p>发布后的自动匹配仍然直接用 {@link CandidateService}（2-gram）：
     * 系统主动推送要压打扰，用户主动找东西要少漏，两条路的风险方向相反，
     * 不能因为「混合检索更好」就顺手把另一条也改了。
     * <p>向量腿不可用时这里自动退回 2-gram，所以对调用方而言接口语义没变。
     */
    private final HybridRecallService hybridRecallService;
    private final AiService aiService;
    private final ItemService itemService;

    /**
     * 整轮时间预算（毫秒）。前端超时 120 秒，后端留在这里 ——
     * <b>上游超时必须大于下游最坏耗时</b>，反过来会出现"后端还在跑、前端先掐断"：
     * 用户看到失败，其实后端最后成功了。
     */
    @Value("${ai.conversation.turn-budget-ms:90000}")
    private long turnBudgetMs;

    /** 会话保留天数。过期即清理，并要求用户重新开始 */
    @Value("${ai.conversation.retention-days:7}")
    private int retentionDays;

    /** 带进模型上下文的最近几轮对话（计划里定的「默认最近 4 轮」） */
    @Value("${ai.conversation.history-turns:4}")
    private int historyTurns;

    /** 一次最多读回多少条历史消息（刷新恢复和模型上下文都受它约束） */
    @Value("${ai.conversation.history-messages:50}")
    private int historyMessages;

    /**
     * 「处理中」占位多久算陈旧、可以被后到的请求接管（毫秒）。
     *
     * <p>必须大于 {@link #turnBudgetMs}：比整轮预算还短的话，一轮还没跑完，
     * 占位就被判定成陈旧了，第二个请求会挤进来重跑 —— 于是"同时只有一轮"这条约束
     * 在最需要它的时候（模型慢）恰好失效。
     */
    @Value("${ai.conversation.in-progress-stale-ms:180000}")
    private long inProgressStaleMs;

    /** 召回后送进精排的候选条数 —— 和单轮链路共用同一个配置键 */
    @Value("${ai.recall.top-k:50}")
    private int recallTopK;

    /**
     * 启动校验：这几个值配错了不会有任何报错，只会让会话以奇怪的方式工作
     * （预算配成 0 → 一次模型都发不出去；保留天数配成 0 → 会话创建完立刻过期）。
     * 所以宁可起不来，也不要安静地跑在一个看起来正常的状态上。
     */
    @PostConstruct
    void validateConfig() {
        List<String> errors = new ArrayList<>();
        if (turnBudgetMs < 5_000) {
            errors.add("ai.conversation.turn-budget-ms 至少 5000（当前 " + turnBudgetMs
                    + "）—— 比一次模型调用的最短耗时还小，等于一次都发不出去");
        }
        if (retentionDays < 1) {
            errors.add("ai.conversation.retention-days 至少 1（当前 " + retentionDays + "）");
        }
        if (historyTurns < 1) {
            errors.add("ai.conversation.history-turns 至少 1（当前 " + historyTurns + "）");
        }
        if (historyMessages < historyTurns * 2) {
            errors.add("ai.conversation.history-messages（" + historyMessages
                    + "）装不下 " + historyTurns + " 轮对话（每轮 2 条）");
        }
        if (inProgressStaleMs <= turnBudgetMs) {
            errors.add("ai.conversation.in-progress-stale-ms（" + inProgressStaleMs
                    + "）必须大于 turn-budget-ms（" + turnBudgetMs
                    + "）—— 否则一轮还没跑完，占位就被判成陈旧，第二个请求会挤进来重跑");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("找物助手会话配置不合法：\n  - " + String.join("\n  - ", errors));
        }
    }

    // ===================== 会话 =====================

    @Override
    public SessionResponse currentSession(Long userId) {
        Long uid = requireUser(userId);
        AiConversation conversation = store.findCurrent(uid);
        if (conversation != null) {
            return new SessionResponse(toView(conversation), false);
        }
        // 没有可用的会话 —— 顺手清掉这个用户已过期的，并如实报告"刚清掉过一段"，
        // 让界面能说"之前那段超过保留期了"，而不是装作什么都没发生
        boolean expired = store.purgeExpiredOfUser(uid) > 0;
        return new SessionResponse(null, expired);
    }

    @Override
    public ConversationView startNewSession(Long userId) {
        Long uid = requireUser(userId);
        AiConversation conversation = store.create(uid, LocalDateTime.now().plusDays(retentionDays));
        log.info("用户 {} 开始新的找物会话: conversationId={}", uid, conversation.getId());
        return toView(conversation);
    }

    @Override
    public ConversationView getSession(Long userId, Long conversationId) {
        Long uid = requireUser(userId);
        AiConversation conversation = store.requireOwned(conversationId, uid);
        requireUnexpired(conversation);
        return toView(conversation);
    }

    // ===================== 一轮对话 =====================

    @Override
    public ConversationTurnView sendMessage(Long userId, Long conversationId, SendMessageRequest request) {
        Long uid = requireUser(userId);
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + turnBudgetMs;

        // ① 会话认领与前置校验 —— 会话 id 来自客户端，必须先证明它属于这个用户
        AiConversation conversation = store.requireOwned(conversationId, uid);
        requireUnexpired(conversation);

        String requestId = request.getRequestId();
        String content = request.getContent().trim();

        // ② 幂等重放：这一轮已经成功跑过 → 直接返回上次结果，不再调模型。
        //
        // ⚠️ 这一步必须排在**版本校验之前**：用户拿着一个旧标签页重发同一个请求，
        //    应当拿到他自己的那份结果，而不是一句"你看到的已经过期了"。
        //    重放是"请求已经处理过了"，和"页面看到的是旧状态"是两件事。
        AiMessage done = store.findAssistant(conversationId, requestId);
        if (done != null && AiMessage.STATUS_OK.equals(done.getStatus())) {
            log.info("重放已完成的轮次: conversationId={}, requestId={}", conversationId, requestId);
            return toTurnView(conversation, done);
        }

        // ③ 同一个 requestId 必须对应同一句话（否则重放会答非所问，见 Store 的说明）
        AiMessage priorUser = store.findUserMessage(conversationId, requestId);
        if (priorUser != null) {
            store.assertSameContent(requestId, content, priorUser);
        }

        // ④ 客户端所见版本校验：页面上的卡片列表可能是旧的，「第二个」会被解析成另一批结果
        if (request.getConversationVersion() != null
                && !request.getConversationVersion().equals(conversation.getVersion())) {
            log.warn("客户端所见版本已过期: 页面={}, 服务端={}", request.getConversationVersion(),
                    conversation.getVersion());
            throw new BusinessException(ResultCode.CONFLICT,
                    "你看到的这段对话已经变了，请刷新后再发送（刷新的目的是让页面上的卡片顺序与服务端一致）");
        }

        // ⑤ 组装上下文：只取最近若干条，且必须在写入本轮消息**之前**取 ——
        //    否则本轮原话会同时出现在"历史"和"本轮消息"里，模型会以为用户说了一遍。
        //    这一步也必须在占用之前：占位行还没产生，历史里就不会混进一个空回答
        List<AiMessage> recent = store.recentMessages(conversationId, historyMessages);
        List<TurnInterpreter.HistoryTurn> history = buildHistory(recent);
        List<ConversationCard> visible = lastVisibleCards(recent);

        // ⑥ 先落用户原话（幂等 + 内容一致性），**再**占用会话。
        //    顺序不能反：消息按自增 id 排序，反了刷新后会变成助手先说话。
        //    两步各自是短语句，不共用一个事务 —— 见 ConversationStore#beginTurn 的说明
        boolean insertedUserMessage = store.saveUserMessageIfAbsent(conversationId, requestId, content,
                userPayload(request));

        LocalDateTime now = LocalDateTime.now();
        ConversationStore.ClaimOutcome claim;
        try {
            claim = store.beginTurn(conversation, requestId,
                    now.minusNanos(inProgressStaleMs * 1_000_000L), now);
        } catch (BusinessException e) {
            // 抢占被拒（版本冲突/会话已不在）= 本轮**没有被接纳**。
            // 刚落的那句原话就成了没有下文的孤儿消息 —— 收回去，
            // 否则它会永久留在历史里（验收缺陷 2：409 之后历史里留下没有回复的 USER 行）
            discardOrphanUserMessage(insertedUserMessage, conversationId, requestId);
            throw e;
        }
        if (claim.claim() == ConversationStore.Claim.ALREADY_DONE) {
            // 抢占时才发现已完成（另一个并发请求刚写完）→ 一样重放
            return toTurnView(conversation, claim.message());
        }
        if (claim.claim() == ConversationStore.Claim.BUSY) {
            // 同样没被接纳：占用着的是另一轮，我们这句原话在库里等不到任何回复
            discardOrphanUserMessage(insertedUserMessage, conversationId, requestId);
            throw new BusinessException(ResultCode.CONFLICT, "这段对话正在处理上一条消息，请稍候再试一次");
        }
        AiMessage claimed = claim.message();

        SearchSummary summary = SearchSummary.fromJson(conversation.getSummaryJson());
        TurnMeter meter = new TurnMeter(turnBudgetMs);

        TurnOutcome outcome = runTurn(summary, history, visible, content, request, deadline, meter, startedAt);

        // ⑦ 结束这一轮。成功与失败走两条路：失败**不推进摘要**（见类注释）
        AiMessage assistant = buildMessage(requestId, outcome, conversation);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(retentionDays);
        try {
            if (outcome.summaryAdvanced()) {
                store.finishWithSummary(conversation, claimed, assistant,
                        outcome.summary().toJson(), expiresAt);
            } else {
                store.finishWithoutSummary(conversation, claimed, assistant, expiresAt);
            }
        } catch (ConversationStore.TurnSupersededException e) {
            // 这一轮的占用在模型调用期间被接管了（超时恢复）—— 本次结果整体作废。
            // 如果接管者已经跑出了 OK 的结果，就直接把它呈现给用户，别让他重试一次白跑
            AiMessage winner = store.findAssistant(conversationId, requestId);
            if (winner != null && AiMessage.STATUS_OK.equals(winner.getStatus())) {
                return toTurnView(conversation, winner);
            }
            throw new BusinessException(ResultCode.CONFLICT, "这一轮已被另一次操作接管，请刷新后重试");
        }
        log.info("会话轮次完成: conversationId={}, intent={}, status={}, 模型调用 {} 次，{} tokens，耗时 {} ms",
                conversationId, outcome.intent(), outcome.status(),
                outcome.diagnostics().getModelCalls(),
                outcome.diagnostics().getPromptTokens() + outcome.diagnostics().getCompletionTokens(),
                outcome.diagnostics().getElapsedMs());

        return toTurnView(conversation, outcome, requestId, store.currentVersion(conversationId));
    }

    /**
     * 用户消息附带的结构化说明 —— 目前只有「结构化撤回」需要它。
     *
     * <p>为什么必须落库：撤回失败后要能**原样重放**那条指令。
     * 只存一句人话「去掉「颜色」这个条件」，刷新之后就再也分不清
     * 那是用户点了 × 还是他自己打的字 —— 前者是确定操作，后者要交给模型理解，
     * 重试时把前者当后者，就等于让模型去猜一个本来不需要猜的东西。
     */
    private String userPayload(SendMessageRequest request) {
        if (request.getRetract() == null) {
            return null;
        }
        MessagePayload payload = new MessagePayload();
        RetractRequest retract = new RetractRequest();
        retract.setField(request.getRetract().getField());
        payload.setRetract(retract);
        return payload.toJson();
    }

    /**
     * 本轮没被接纳（409）时，收回这轮<b>刚刚插入</b>的用户消息。
     *
     * <p>只有"这行确实是本次调用插的"才收 —— 行本来就存在说明是同 requestId 的
     * 并发重试，那句话正被另一次（可能成功的）执行使用着。
     *
     * <p>补偿动作本身失败<b>不能盖掉原始的 409</b>：原始异常说的是"这一轮没跑"，
     * 补偿只是清理；把它一起抛出去，用户会看到两个互相矛盾的错误，且真正的
     * 冲突原因被淹没了。失败就记日志，留给"孤儿轮"前端兜底与人工排查。
     */
    private void discardOrphanUserMessage(boolean insertedUserMessage, Long conversationId, String requestId) {
        if (!insertedUserMessage) {
            return;
        }
        try {
            store.discardUserMessageIfOrphan(conversationId, requestId);
        } catch (Exception e) {
            log.error("收回未被接纳的用户消息失败（该轮仍未跑，但消息可能残留）: conversationId={}, requestId={}",
                    conversationId, requestId, e);
        }
    }

    // ===================== 轮次内部 =====================

    /**
     * 跑完一轮的"理解和检索"，<b>全程不碰数据库</b>。
     *
     * <p>任何失败都返回 FAILED 的 outcome 而不是抛异常 —— 模型超时是预期内的结果，
     * 前端要能区分"这次没查成（可重试）"和"查了，没有"。
     */
    private TurnOutcome runTurn(SearchSummary persisted, List<TurnInterpreter.HistoryTurn> history,
                                List<ConversationCard> visible, String content,
                                SendMessageRequest request, long deadline,
                                TurnMeter meter, long startedAt) {
        // ---- 撤回条件（用户点了条件标签上的 ×）：确定操作，不调模型 ----
        if (request.getRetract() != null) {
            String field = request.getRetract().getField();
            SearchSummary after = persisted.merge(List.of(), List.of(field), content, false);
            meter.intent = TurnIntent.RETRACT;
            String notice = "已删除条件「" + field + "」。";
            if (after.isEmpty()) {
                return TurnOutcome.reply(TurnIntent.RETRACT,
                        notice + "现在没有条件了，说说你丢的是什么、大概什么样？",
                        after, meter, startedAt);
            }
            return search(after, persisted, content, TurnIntent.RETRACT, deadline, meter, startedAt, notice);
        }

        // ---- 模型调用 1：理解这一轮 ----
        TurnInterpreter.Interpretation understanding =
                interpreter.interpret(persisted, history, labelsOf(visible), content, deadline);
        meter.recordCall(understanding.promptTokens(), understanding.completionTokens());
        if (understanding.failed()) {
            // 三种失败要分开报，给用户的建议完全不同：
            //   超时     → 这次等太久，等会儿再试
            //   返回不可用 → 这次没听懂，直接重试
            //   调用失败 → 服务暂时不可用，重试
            String reason = degradeReasonFor(understanding.failureKind(), deadline, false);
            return TurnOutcome.failed(TurnIntent.SEARCH, reason, understanding.failureDetail(), persisted,
                    meter.toDiagnostics(startedAt, reason, understanding.failureDetail()));
        }

        String intent = understanding.intent();
        meter.intent = intent;
        SearchSummary merged = mergeSummary(persisted, understanding, content);

        return switch (intent) {
            case TurnIntent.ASK_MORE, TurnIntent.PLATFORM, TurnIntent.SMALLTALK ->
                    TurnOutcome.reply(intent, orFallback(understanding.reply(), intent), merged, meter, startedAt);
            case TurnIntent.EXPLAIN_ITEM ->
                    explainItem(merged, persisted, understanding, visible, content, deadline, meter, startedAt);
            case TurnIntent.EXCLUDE_ITEM ->
                    excludeAndSearch(merged, persisted, understanding, visible, content, deadline, meter, startedAt);
            default -> search(merged, persisted, content, intent, deadline, meter, startedAt, null);
        };
    }

    /**
     * 把模型的"建议"合并进摘要。
     *
     * <p>模型只提出"用户这轮说了什么"，<b>能不能进摘要由这里决定</b>：
     * 长度越界、值为空、声称换了主体却没给新主体，全部丢掉或退化。
     * 换主体时先清空旧条件与旧排除项 —— 上一件东西的条件对新的那件毫无意义，
     * 留着会让检索描述变成「手机 黑色 钥匙」这种谁都匹配不上的拼接。
     */
    private SearchSummary mergeSummary(SearchSummary summary,
                                       TurnInterpreter.Interpretation understanding, String content) {
        List<SearchFact> facts = new ArrayList<>(understanding.facts());
        String subject = understanding.subject();
        String currentSubject = summary.currentValue(SearchSummary.FIELD_SUBJECT);

        // ⚠️ 模型说"换物品了"不能照单全收 —— 它会误判。
        //    真实模型实测：主体从「车钥匙」补了一句地点，它把主体报成「钥匙」并说换了物品，
        //    于是颜色、特征全被清空（用户只是补了个地点而已）。
        //    所以这里加一道确定性判断：**新旧主体互相包含就不算换**（车钥匙 ⊃ 钥匙）。
        //    真换了物品时（手机 → 车钥匙）两个词互不包含，照常重置。
        boolean subjectChanged = understanding.subjectChanged()
                && isRealSubjectChange(currentSubject, subject);

        if (subject != null && (subjectChanged || currentSubject == null)) {
            SearchFact subjectFact = new SearchFact();
            subjectFact.setField(SearchSummary.FIELD_SUBJECT);
            subjectFact.setValue(subject);
            subjectFact.setCertainty(SearchSummary.CERTAIN);
            // 主体排最前：buildQuery 的顺序因此稳定，排查时一眼能看出"在找什么"
            facts.add(0, subjectFact);
        }
        return summary.merge(facts, understanding.removedFields(), content, subjectChanged);
    }

    /**
     * 这是"真的换了物品"，还是模型把同一个东西说成了另一个词？
     *
     * <p>判据是<b>互相包含</b>：两个词只要有一个包含另一个，就当作同一个东西 ——
     * 「车钥匙」和「钥匙」、「校园卡」和「校园一卡通里的卡」这类说法，
     * 用户自己在下一句里就会来回换，而每换一次词就把颜色、特征、地点全清空一次，
     * 对用户来说完全是灾难：他会觉得"我说过的东西系统全忘了"。
     *
     * <p>反向的风险（该重置时没重置）要小得多：条件留着，用户还能自己点 × 撤掉。
     */
    static boolean isRealSubjectChange(String currentSubject, String newSubject) {
        if (currentSubject == null || newSubject == null) {
            return currentSubject != null && newSubject != null;   // 两边都有才谈得上比较
        }
        String a = currentSubject.trim();
        String b = newSubject.trim();
        return !a.contains(b) && !b.contains(a);
    }

    /**
     * 检索：召回（**先按排除项过滤**）→ 精排 → 出卡片。
     *
     * <p>query 用的是**合并后的完整描述**，不是用户刚说的那一句 ——
     * 「其实是在食堂」如果只拿这句去召回，主体和颜色全丢了，必然搜出一堆无关物品。
     *
     * @param notice 需要附加在回复前面的说明（比如"已删除条件…"）；可为空
     */
    private TurnOutcome search(SearchSummary summary, SearchSummary persisted, String content,
                               String intent, long deadline, TurnMeter meter, long startedAt,
                               String notice) {
        String query = summary.buildQuery();
        if (query.isBlank()) {
            // 一条条件都没有时去检索，必然搜出一堆无关物品 —— 不如直接问"你丢的是什么"。
            // 这是唯一一种由后端（而不是模型）发起的追问
            return TurnOutcome.reply(TurnIntent.ASK_MORE,
                    prefix(notice, "先告诉我你丢的是什么物品吧，比如「手机」「钥匙」「校园卡」，"
                            + "有了主体我就能帮你从全站招领信息里找。"),
                    summary, meter, startedAt);
        }

        Set<Long> excluded = new LinkedHashSet<>(summary.getExcludedItemIds());
        // 混合召回：2-gram + 向量两路融合，deadline 一并传下去 ——
        // 向量腿是"够时间才跑"的增强，不能吃掉整轮预算
        CandidateService.RecallResult recall =
                hybridRecallService.recall(query, recallTopK, excluded, deadline);
        meter.poolSize = recall.poolSize();
        meter.excludedCount = recall.excludedCount();

        AiQueryResponse match = aiService.matchAgainst(query, recall.candidates(), deadline);
        meter.candidateCount = match.getCandidateCount();
        meter.recordCall(match.getPromptTokens(), match.getCompletionTokens());

        // 精排这一层有**两种**不能当成结果的情况，都必须显式失败：
        //   · 调用失败（超时/网络）—— 服务不稳定；
        //   · 调通了但结构不可用（不是 JSON / 缺 matches 或 answer）—— 提示词或模型换了版本。
        // 两者都绝不能退化成"没找到"：那等于把模型的锅安在平台数据上，
        // 用户会因为一次坏返回而以为平台上根本没有自己的东西。
        String degrade = match.getDegradeReason();
        if (degrade != null && !AiQueryResponse.DEGRADE_NO_CANDIDATES.equals(degrade)) {
            boolean badOutput = AiQueryResponse.DEGRADE_BAD_RESPONSE.equals(degrade);
            String reason = badOutput
                    ? ConversationTurnView.DEGRADE_BAD_MODEL_OUTPUT
                    : (budgetExhausted(deadline)
                            ? ConversationTurnView.DEGRADE_TURN_TIMEOUT
                            : ConversationTurnView.DEGRADE_MATCH_FAILED);
            String detail = badOutput
                    ? "精排返回的结构不可用（缺 matches 数组或 answer）"
                    : (budgetExhausted(deadline)
                            ? "整轮预算（" + turnBudgetMs + "ms）已用完，精排没跑完"
                            : "精排的模型调用失败（超时/网络错误）");
            // ⚠️ 回传的是 **persisted**（上一次有效的那份），不是本轮合并出来的 merged：
            //    这一轮没有提交，前端面板和服务端状态必须停在同一个地方。
            //    回 merged 的话，用户会看到"条件里有食堂"，而服务端其实还是图书馆 ——
            //    他既不知道新条件生效没有，也没法把它撤掉（R8）
            return TurnOutcome.failed(intent, reason, detail, persisted,
                    meter.toDiagnostics(startedAt, reason, detail));
        }

        List<ConversationCard> cards = cardsFor(match);
        return TurnOutcome.ok(intent, prefix(notice, match.getAnswer()), cards, summary, meter,
                startedAt, match.getDegradeReason());
    }

    /** 「不是第二个，再找找」：先把那条记进排除项，再重新检索 */
    private TurnOutcome excludeAndSearch(SearchSummary summary, SearchSummary persisted,
                                         TurnInterpreter.Interpretation understanding,
                                         List<ConversationCard> visible, String content,
                                         long deadline, TurnMeter meter, long startedAt) {
        Long itemId = resolveReference(understanding.referenceIndex(), visible);
        if (itemId == null) {
            return TurnOutcome.reply(TurnIntent.ASK_MORE,
                    "我不确定你指的是哪一条。可以说「第几个」—— 按上面卡片的顺序数；也可以告诉我它的标题。",
                    summary, meter, startedAt);
        }
        // 记进**摘要**，而不是只在这一次的结果里剔掉：用户纠正过的错误必须留下来，
        // 否则下一次搜索这条物品又会被重新推荐上来
        summary.excludeItem(itemId);
        return search(summary, persisted, content, TurnIntent.EXCLUDE_ITEM, deadline, meter, startedAt, null);
    }

    /** 「第二个为什么像」：重新读这条物品的**当前**状态，再让模型说清像在哪 */
    private TurnOutcome explainItem(SearchSummary summary, SearchSummary persisted,
                                    TurnInterpreter.Interpretation understanding,
                                    List<ConversationCard> visible, String content,
                                    long deadline, TurnMeter meter, long startedAt) {
        Long itemId = resolveReference(understanding.referenceIndex(), visible);
        if (itemId == null) {
            return TurnOutcome.reply(TurnIntent.ASK_MORE,
                    "我不确定你问的是哪一条。可以说「第几个」，按上面卡片的顺序数。",
                    summary, meter, startedAt);
        }
        // ⚠️ 现查数据库，不用历史快照：这条可能已经被人认领走了。
        //    拿旧状态回答等于承诺一件已经没了的东西
        Item item = itemService.getById(itemId);
        if (item == null) {
            return TurnOutcome.reply(TurnIntent.ASK_MORE,
                    "这条信息现在查不到了，可能已经被发布者删掉。要不要我按你的条件重新找一遍？",
                    summary, meter, startedAt);
        }

        TurnInterpreter.Explanation explanation = interpreter.explain(item, summary, content, deadline);
        meter.recordCall(explanation.promptTokens(), explanation.completionTokens());
        if (explanation.failed()) {
            String reason = degradeReasonFor(explanation.failureKind(), deadline, true);
            return TurnOutcome.failed(TurnIntent.EXPLAIN_ITEM, reason, explanation.failureDetail(), persisted,
                    meter.toDiagnostics(startedAt, reason, explanation.failureDetail()));
        }

        StringBuilder text = new StringBuilder();
        if (Item.STATUS_CLAIMED.equals(item.getStatus())) {
            // 状态变了必须说在前面 —— 用户问"为什么像"多半是准备去认领，
            // 而这条已经归还给别人了。只回答"哪里像"是不够的
            text.append("先说明一下：这条物品的状态已经是「已被认领」，不能再申请了。\n");
        }
        text.append(explanation.answer());

        List<ConversationCard> cards = new ArrayList<>();
        // ⚠️ 序号沿用**它在搜索结果里的原编号**，不重新从 1 数 ——
        //    用户问的是「第二个」，屏幕上就得写着 2。写成 1 的话，
        //    他下一次说「不是第二个」就对不上了（见 ConversationCard#index）
        ConversationCard card = ConversationCard.from(item, null, "你正在查看的这条",
                understanding.referenceIndex());
        if (card != null) {
            cards.add(card);
        }
        return TurnOutcome.ok(TurnIntent.EXPLAIN_ITEM, text.toString(), cards, summary, meter, startedAt, null);
    }

    // ===================== 组装 =====================

    private List<ConversationCard> cardsFor(AiQueryResponse match) {
        List<MatchResult> matches = match.getMatches() == null ? List.of() : match.getMatches();
        if (matches.isEmpty()) {
            return List.of();
        }
        // 一次按 id 取回（避免 N 次查询），同时**拿到最新状态** ——
        // 不用召回缓存里那份（最长可能 5 分钟），卡片上的状态必须是当下的
        Map<Long, Item> fresh = itemService.mapByIds(
                matches.stream().map(MatchResult::getItemId).toList());
        List<ConversationCard> cards = new ArrayList<>(matches.size());
        for (MatchResult m : matches) {
            Item item = fresh.get(m.getItemId());
            if (item == null) {
                log.warn("匹配到的物品已不存在，跳过卡片: itemId={}", m.getItemId());
                continue;
            }
            // 序号就是它在这批结果里的位置（1 起）—— 由后端定，前端照着显示。
            // 前端按下标自己数的话，"解释某一条"那一轮只有一张卡，会被写成 1，
            // 用户下次说「不是第二个」就再也对不上了
            cards.add(ConversationCard.from(item, m.getScore(), m.getReason(), cards.size() + 1));
        }
        return cards;
    }

    /**
     * 把相邻的「用户 → 助手」配成轮次。
     * <p>配对而不是平铺，是因为提示词要按"一问一答"读；
     * 平铺会让模型分不清哪句是用户说的、哪句是自己上一轮说的。
     */
    private List<TurnInterpreter.HistoryTurn> buildHistory(List<AiMessage> messages) {
        List<TurnInterpreter.HistoryTurn> turns = new ArrayList<>();
        String pendingUser = null;
        for (AiMessage message : messages) {
            if (AiMessage.ROLE_USER.equals(message.getRole())) {
                pendingUser = message.getContent();
                continue;
            }
            MessagePayload payload = MessagePayload.fromJson(message.getPayloadJson());
            turns.add(new TurnInterpreter.HistoryTurn(pendingUser, message.getContent(),
                    labelsOf(payload.getCards())));
            pendingUser = null;
        }
        if (pendingUser != null) {
            turns.add(new TurnInterpreter.HistoryTurn(pendingUser, null, List.of()));
        }
        // 只带最近 N 轮：更早的内容已经沉进摘要了，重复喂进去只是烧 token
        return turns.size() <= historyTurns
                ? turns
                : new ArrayList<>(turns.subList(turns.size() - historyTurns, turns.size()));
    }

    /**
     * 用户**此刻能看到的**卡片，按当时展示的顺序 —— 「第二个」数的是它。
     *
     * <p>三条规则，都是实测出来的：
     * <ol>
     *   <li>取最近一次真的展示了卡片的那一轮，而不是"最后一条助手消息"：
     *       中间夹一轮纯文字回复（比如他先问了句平台怎么用）时，
     *       卡片并没有从屏幕上消失，这时说「第二个」指的仍然是那几张卡；</li>
     *   <li><b>「解释某一条」那一轮不算新的结果列表</b>。它自己带的那张卡是
     *       "我正在说这一条"，只是把被问的那条再亮一次。端到端跑的时候就撞上了：
     *       先搜出两张卡、再问「第二个为什么像」、接着说「不是第二个」——
     *       如果按最后一条消息算，此刻能看到只剩解释里的那一张，
     *       序号 2 越界，助手只好反问"你指哪一条"，而用户明明刚指着第二张说过话；</li>
     *   <li>顺序取自落库时的顺序，不重新排序 —— 序号必须和用户眼睛看到的一致。</li>
     * </ol>
     */
    private List<ConversationCard> lastVisibleCards(List<AiMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiMessage message = messages.get(i);
            if (!AiMessage.ROLE_ASSISTANT.equals(message.getRole())
                    || TurnIntent.EXPLAIN_ITEM.equals(message.getIntent())) {
                continue;
            }
            List<ConversationCard> cards = MessagePayload.fromJson(message.getPayloadJson()).getCards();
            if (cards != null && !cards.isEmpty()) {
                return cards;
            }
        }
        return List.of();
    }

    /** 卡片的一句话描述 —— 给模型看"第二个"是哪一条，也给用户核对 */
    private List<String> labelsOf(List<ConversationCard> cards) {
        if (cards == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>(cards.size());
        for (ConversationCard card : cards) {
            StringBuilder sb = new StringBuilder();
            sb.append(card.getTitle() == null ? "（无标题）" : card.getTitle());
            if (card.getLocation() != null && !card.getLocation().isBlank()) {
                sb.append("（拾获于").append(card.getLocation()).append("）");
            }
            if (card.getStatus() != null) {
                sb.append('[').append(TurnInterpreter.statusText(card.getStatus())).append(']');
            }
            if (card.getScore() != null) {
                sb.append(" 匹配度").append(card.getScore());
            }
            labels.add(sb.toString());
        }
        return labels;
    }

    /**
     * 「第二个」→ 具体 itemId。
     * <p>按**用户看到的那批卡片的顺序**解析（从 1 开始），序号越界一律返回 null，
     * 让调用方去追问，而不是"猜一个最接近的"：猜错会把用户否掉的物品记成排除项，
     * 之后它再也不会出现在结果里 —— 一个错误的猜测会变成一个永久的错误。
     */
    private Long resolveReference(Integer index, List<ConversationCard> visible) {
        if (index == null || index < 1 || index > visible.size()) {
            return null;
        }
        return visible.get(index - 1).getItemId();
    }

    private String orFallback(String reply, String intent) {
        if (reply != null && !reply.isBlank()) {
            return reply;
        }
        return switch (intent) {
            case TurnIntent.PLATFORM -> "这个问题我这边没有对应的信息，不好瞎猜 —— "
                    + "平台功能（发布物品、提交认领、查看申请进度）我可以帮你，"
                    + "学校的规定和办公地点建议直接问失物招领处。";
            case TurnIntent.SMALLTALK -> "我在。说说你丢了什么、大概什么样，我帮你从全站招领信息里找。";
            default -> "能再补充一点吗？比如物品的颜色、品牌型号，或者大概在哪里丢的 ——"
                    + "有其中一个我就能开始找。";
        };
    }

    private static String prefix(String notice, String text) {
        if (notice == null || notice.isBlank()) {
            return text;
        }
        return notice + "\n" + (text == null ? "" : text);
    }

    /**
     * 剩余时间已经不够再发一次请求 —— 用它把"等太久"和"服务挂了"分开。
     * <p>给用户的信息不一样：前者是"这次等太久了"，后者是"服务暂时不可用"。
     * 两者的重试意义也不同（一个等会儿再来、一个立刻重试就行）。
     */
    static boolean budgetExhausted(long deadline) {
        return LlmTransport.remainingMs(deadline) < LlmTransport.MIN_ATTEMPT_MS;
    }

    /**
     * 把模型层的失败原因翻译成给用户看的降级原因。
     *
     * <p>三者的建议完全不同，所以必须分开：
     * <ul>
     *   <li>{@code BAD_MODEL_OUTPUT} —— 模型答了，但答的东西用不了 → 直接重试；</li>
     *   <li>{@code TURN_TIMEOUT} —— 预算用完了 → 等一会儿再试；</li>
     *   <li>{@code UNDERSTAND_FAILED / MATCH_FAILED} —— 服务不稳定 → 重试。</li>
     * </ul>
     * 少分一种，排查时就只能看到"失败率上升"，却不知道该修网络、修预算还是修提示词。
     */
    private static String degradeReasonFor(String failureKind, long deadline, boolean matching) {
        if (TurnInterpreter.FAILED_OUTPUT.equals(failureKind)) {
            return ConversationTurnView.DEGRADE_BAD_MODEL_OUTPUT;
        }
        if (budgetExhausted(deadline)) {
            return ConversationTurnView.DEGRADE_TURN_TIMEOUT;
        }
        return matching ? ConversationTurnView.DEGRADE_MATCH_FAILED
                : ConversationTurnView.DEGRADE_UNDERSTAND_FAILED;
    }

    // ===================== 视图组装 =====================

    private ConversationView toView(AiConversation conversation) {
        ConversationView view = new ConversationView();
        view.setId(conversation.getId());
        view.setVersion(conversation.getVersion());
        view.setConditions(SearchSummary.fromJson(conversation.getSummaryJson()).viewConditions());
        view.setExpiresAt(conversation.getExpiresAt());
        view.setCreatedAt(conversation.getCreatedAt());
        view.setUpdatedAt(conversation.getUpdatedAt());

        List<ConversationMessageView> views = new ArrayList<>();
        for (AiMessage message : store.messages(conversation.getId(), historyMessages)) {
            views.add(toMessageView(message));
        }
        view.setMessages(views);
        return view;
    }

    private ConversationMessageView toMessageView(AiMessage message) {
        ConversationMessageView view = new ConversationMessageView();
        view.setRole(message.getRole());
        view.setText(message.getContent());
        view.setStatus(message.getStatus());
        view.setIntent(message.getIntent());
        view.setRequestId(message.getRequestId());
        view.setCreatedAt(message.getCreatedAt());
        MessagePayload payload = MessagePayload.fromJson(message.getPayloadJson());
        if (AiMessage.ROLE_ASSISTANT.equals(message.getRole())) {
            view.setCards(refreshCards(payload.getCards()));
            view.setConditions(payload.getConditions());
            view.setDiagnostics(payload.getDiagnostics());
            view.setDegradeReason(payload.getDegradeReason());
        } else {
            // 用户消息只可能带一件结构化说明：撤回指令（重试时要原样重放）
            view.setRetract(payload.getRetract());
        }
        return view;
    }

    /**
     * 读消息时把卡片的物品字段**从数据库重新回填**。
     *
     * <p>落库只存 itemId、分数和理由，标题/地点/状态一律现取 ——
     * 这样历史消息里的卡片显示的是物品**当前**状态：
     * 一条上轮还写着「未认领」的物品被认领走之后，
     * 历史记录里那句"可以点开申请认领"就成了假话。
     */
    private List<ConversationCard> refreshCards(List<ConversationCard> stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        List<Long> ids = stored.stream().map(ConversationCard::getItemId)
                .filter(Objects::nonNull).toList();
        Map<Long, Item> items = itemService.mapByIds(ids);
        List<ConversationCard> cards = new ArrayList<>(stored.size());
        for (ConversationCard card : stored) {
            Item item = items.get(card.getItemId());
            if (item == null) {
                // 本项目没有删除物品的入口，出现即异常 —— 丢掉这张卡，
                // 而不是给用户一个点不开的标题
                log.warn("历史卡片对应的物品已不存在，跳过: itemId={}", card.getItemId());
                continue;
            }
            // ⚠️ 序号要**原样带回来**：重新回填时漏掉它，界面上刚显示过的「2」
            //    刷新之后就会消失，用户再说「不是第二个」时就再也对不上了
            cards.add(ConversationCard.from(item, card.getScore(), card.getReason(), card.getIndex()));
        }
        return cards;
    }

    /**
     * 把一条**已完成的历史消息**组装成重放响应。
     *
     * <p>⚠️ 这里的每一项都必须是**那一轮当时的快照**：文本、卡片、条件来自消息载荷，
     * 版本来自落库时记下的 {@code conversationVersion} ——
     * <b>绝不能换成当前会话的版本/条件/卡片</b>。曾经的缺陷正是拼接：
     * 「当前 version=2 + 那一轮的旧条件」让页面退回旧条件却带着新版本，
     * 下一句「第二个」也按错的那批卡片解析。
     *
     * <p>{@code replayed=true} 是给客户端的显式提示：这是历史回复，
     * 不要覆盖页面上较新的会话状态（该自行刷新同步，见 AiChat.vue）。
     */
    private ConversationTurnView toTurnView(AiConversation conversation, AiMessage message) {
        ConversationTurnView view = new ConversationTurnView();
        MessagePayload payload = MessagePayload.fromJson(message.getPayloadJson());
        view.setConversationId(conversation.getId());
        // 当轮记了就用当时的；老数据没有这项就如实留空，不去拿当前版本凑数 ——
        // 拿当前版本正是这个缺陷本身
        view.setVersion(payload.getConversationVersion());
        view.setReplayed(true);
        view.setText(message.getContent());
        view.setCards(refreshCards(payload.getCards()));
        view.setConditions(payload.getConditions());
        view.setIntent(message.getIntent());
        view.setStatus(message.getStatus());
        view.setDegradeReason(payload.getDegradeReason());
        view.setRequestId(message.getRequestId());
        view.setDiagnostics(payload.getDiagnostics());
        return view;
    }

    private ConversationTurnView toTurnView(AiConversation conversation, TurnOutcome outcome,
                                            String requestId, Integer version) {
        ConversationTurnView view = new ConversationTurnView();
        view.setConversationId(conversation.getId());
        view.setVersion(version == null ? conversation.getVersion() : version);
        view.setText(outcome.text());
        view.setCards(outcome.cards());
        view.setConditions(outcome.summary().viewConditions());
        view.setIntent(outcome.intent());
        view.setStatus(outcome.status());
        view.setDegradeReason(outcome.degradeReason());
        view.setRequestId(requestId);
        view.setDiagnostics(outcome.diagnostics());
        return view;
    }

    private AiMessage buildMessage(String requestId, TurnOutcome outcome, AiConversation conversation) {
        AiMessage message = new AiMessage();
        message.setRequestId(requestId);
        message.setRole(AiMessage.ROLE_ASSISTANT);
        message.setContent(outcome.text());
        message.setStatus(outcome.status());
        message.setIntent(outcome.intent());

        MessagePayload payload = new MessagePayload();
        payload.setCards(outcome.cards());
        payload.setConditions(outcome.summary().viewConditions());
        payload.setDiagnostics(outcome.diagnostics());
        payload.setDegradeReason(outcome.degradeReason());
        payload.setDegradeDetail(outcome.diagnostics() == null ? null
                : outcome.diagnostics().getDegradeDetail());
        // 这一轮结束后的会话版本 —— 和 finishWithSummary / finishWithoutSummary 对 version 的
        // 实际效果一一对应（推进摘要才 +1）。将来重放这条消息时，回给客户端的是**当时的**版本，
        // 而不是被后续几轮推高的当前版本（那会拼出"旧条件 + 新版本"的矛盾状态）
        payload.setConversationVersion(outcome.summaryAdvanced()
                ? conversation.getVersion() + 1
                : conversation.getVersion());
        message.setPayloadJson(payload.toJson());
        return message;
    }

    // ===================== 杂项 =====================

    private Long requireUser(Long userId) {
        if (userId == null) {
            // GET 被拦截器放行，没有 token 时 userId 就是 null。
            // 「浏览公开」不适用于私人对话，这里必须拦住，不能让它掉进空指针
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录后再使用找物助手");
        }
        return userId;
    }

    /**
     * 过期会话 —— 用 <b>410 而不是 409</b>。
     * <p>两者对用户的含义完全不同：409 是"时机不对，重试就行"，
     * 而过期是"这段状态真的没了，只能重新开始"。混成一个码，前端就只能靠匹配文案来区分，
     * 于是用户会在该重试的时候被引导去开新对话、把刚说过的条件丢掉。
     */
    private void requireUnexpired(AiConversation conversation) {
        if (store.isExpired(conversation)) {
            throw new BusinessException(ResultCode.GONE,
                    "这段对话已超过 " + retentionDays + " 天，已经过期了。请点「新对话」重新开始。");
        }
    }

    /** 一轮里累计的用量与过程数字 —— 集中算诊断，不用一路传参 */
    private static final class TurnMeter {
        private final long budgetMs;
        private int calls;
        private int promptTokens;
        private int completionTokens;
        private int candidateCount;
        private int poolSize;
        private int excludedCount;
        private String intent;

        TurnMeter(long budgetMs) {
            this.budgetMs = budgetMs;
        }

        void recordCall(int prompt, int completion) {
            calls++;
            promptTokens += prompt;
            completionTokens += completion;
        }

        TurnDiagnostics toDiagnostics(long startedAt, String degradeReason, String degradeDetail) {
            TurnDiagnostics diagnostics = new TurnDiagnostics();
            diagnostics.setModelCalls(calls);
            diagnostics.setPromptTokens(promptTokens);
            diagnostics.setCompletionTokens(completionTokens);
            diagnostics.setElapsedMs(System.currentTimeMillis() - startedAt);
            diagnostics.setCandidateCount(candidateCount);
            diagnostics.setPoolSize(poolSize);
            diagnostics.setExcludedCount(excludedCount);
            diagnostics.setBudgetMs(budgetMs);
            diagnostics.setDegradeReason(degradeReason);
            diagnostics.setDegradeDetail(degradeDetail);
            return diagnostics;
        }
    }

    /**
     * 一轮跑完的中间结果 —— 还没有落库。
     * <p>{@code summaryAdvanced} 决定这次要不要推进摘要：失败的一轮为 false。
     */
    record TurnOutcome(String intent, String text, List<ConversationCard> cards, SearchSummary summary,
                       boolean summaryAdvanced, String status, String degradeReason,
                       TurnDiagnostics diagnostics) {

        static TurnOutcome ok(String intent, String text, List<ConversationCard> cards,
                              SearchSummary summary, TurnMeter meter, long startedAt, String degradeReason) {
            return new TurnOutcome(intent, text, cards, summary, true,
                    ConversationTurnView.STATUS_OK, degradeReason,
                    meter.toDiagnostics(startedAt, degradeReason, null));
        }

        static TurnOutcome reply(String intent, String text, SearchSummary summary,
                                 TurnMeter meter, long startedAt) {
            // 追问/平台问答/寒暄也要落库：它们是对话的一部分，刷新后必须还在，
            // 而且下一轮的模型要看得到自己问过什么，否则会重复追问同一个问题
            return new TurnOutcome(intent, text, List.of(), summary, true,
                    ConversationTurnView.STATUS_OK, null, meter.toDiagnostics(startedAt, null, null));
        }

        static TurnOutcome failed(String intent, String degradeReason, String detail,
                                  SearchSummary summary, TurnDiagnostics diagnostics) {
            String text = switch (degradeReason) {
                case ConversationTurnView.DEGRADE_TURN_TIMEOUT ->
                        "这次等得太久了，没查完。你的条件都还在，点「重试」我再跑一次。";
                case ConversationTurnView.DEGRADE_MATCH_FAILED ->
                        "条件我记住了，但这次没能完成检索。点「重试」我再试一次。";
                default -> "这次没能理解你的意思（服务暂时不可用）。你的条件没有变，点「重试」我再试一次。";
            };
            // summaryAdvanced = false：失败不推进摘要，保留上一次有效的状态
            return new TurnOutcome(intent, text, List.of(), summary, false,
                    ConversationTurnView.STATUS_FAILED, degradeReason, diagnostics);
        }
    }
}
