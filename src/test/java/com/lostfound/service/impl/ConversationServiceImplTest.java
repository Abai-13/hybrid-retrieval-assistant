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
import com.lostfound.service.HybridRecallService;
import com.lostfound.service.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 找物助手一轮对话的编排 —— 模型与存储都被 mock 掉，不产生真实调用。
 *
 * <p>这组测试盯的是第一阶段里"看起来能跑但其实是错的"那几类：
 * <ul>
 *   <li><b>失败被伪装成没找到</b>：模型超时必须返回 FAILED + 可重试，
 *       而不是一段"没有匹配到"的普通文案；</li>
 *   <li><b>失败推进了摘要</b>：本轮没跑完，条件就不该被写进去；</li>
 *   <li><b>指代错位</b>：「第二个」必须按用户看到的那批卡片的顺序解析；</li>
 *   <li><b>排除没在截断前生效</b>：排除项要进召回参数，而不是取完 Top-K 再删。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock
    private ConversationStore store;

    @Mock
    private TurnInterpreter interpreter;

    @Mock
    private HybridRecallService hybridRecallService;

    @Mock
    private AiService aiService;

    @Mock
    private ItemService itemService;

    @InjectMocks
    private ConversationServiceImpl service;

    private static final Long USER = 1L;
    private static final Long CONV_ID = 100L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "turnBudgetMs", 90_000L);
        ReflectionTestUtils.setField(service, "retentionDays", 7);
        ReflectionTestUtils.setField(service, "historyTurns", 4);
        ReflectionTestUtils.setField(service, "historyMessages", 50);
        ReflectionTestUtils.setField(service, "recallTopK", 50);
        ReflectionTestUtils.setField(service, "inProgressStaleMs", 180_000L);

        // 这几个是每条路径都会走到的"底座"，单独 stub 会让每个用例都重复一遍。
        // 默认"抢到了这一轮"，需要验证 BUSY / 已完成 的用例再覆盖
        lenient().when(store.recentMessages(anyLong(), anyInt())).thenReturn(List.of());
        lenient().when(store.messages(anyLong(), anyInt())).thenReturn(List.of());
        lenient().when(store.currentVersion(anyLong())).thenReturn(1);
        lenient().when(store.beginTurn(any(), anyString(), any(), any())).thenReturn(
                new ConversationStore.ClaimOutcome(ConversationStore.Claim.CLAIMED, claimedPlaceholder()));
        
    }

    /** 抢占成功后拿到的占位消息（带版本号，终态写入靠它做 CAS） */
    private static AiMessage claimedPlaceholder() {
        AiMessage claimed = new AiMessage();
        claimed.setId(77L);
        claimed.setConversationId(CONV_ID);
        claimed.setRequestId("r1");
        claimed.setRole(AiMessage.ROLE_ASSISTANT);
        claimed.setStatus(AiMessage.STATUS_IN_PROGRESS);
        claimed.setVersion(0);
        return claimed;
    }

    // ===================== 幂等与重放 =====================

    @Test
    @DisplayName("★ 同一个 requestId 重复到达：重放上次结果，【一次模型调用都不发】")
    void duplicateRequest_replaysWithoutCallingModel() {
        givenConversation(3, "{}");
        AiMessage previous = storedAssistant("r1", "上次的回答", AiMessage.STATUS_OK,
                card(7L, "黑色 iPhone 15", 88));
        when(store.findAssistant(CONV_ID, "r1")).thenReturn(previous);
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(7L, item(7L, "黑色 iPhone 15")));

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "手机"));

        assertEquals("上次的回答", view.getText());
        assertEquals(1, view.getCards().size());
        assertEquals("黑色 iPhone 15", view.getCards().get(0).getTitle());
        verifyNoInteractions(interpreter, hybridRecallService, aiService);
        verify(store, never()).finishWithSummary(any(), any(), any(), any(), any());
        verify(store, never()).saveUserMessageIfAbsent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 上次这一轮失败了：这次要真的重跑，而不是把失败当结果重放")
    void previousFailedTurn_isRerun() {
        givenConversation(3, "{}");
        when(store.findAssistant(CONV_ID, "r1"))
                .thenReturn(storedAssistant("r1", "上次失败了", AiMessage.STATUS_FAILED, (ConversationCard[]) null));
        givenUnderstanding(understanding(TurnIntent.SMALLTALK, "我在。"));
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "在吗"));

        assertEquals(ConversationTurnView.STATUS_OK, view.getStatus());
        assertEquals("我在。", view.getText());
        verify(interpreter).interpret(any(), anyList(), anyList(), eq("在吗"), anyLong());
    }

    // ===================== 失败不能伪装成"没找到" =====================

    @Test
    @DisplayName("★ 理解失败：返回 FAILED + 可重试，且【不推进摘要】（失败不留下半截条件）")
    void understandingFailure_marksFailedAndKeepsSummary() {
        AiConversation conversation = givenConversation(3, "{\"conditions\":[]}");
        when(interpreter.interpret(any(), anyList(), anyList(), anyString(), anyLong()))
                .thenReturn(TurnInterpreter.Interpretation.fail(TurnInterpreter.FAILED_CALL, "模型超时"));
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "黑色手机"));

        assertEquals(ConversationTurnView.STATUS_FAILED, view.getStatus());
        assertEquals(ConversationTurnView.DEGRADE_UNDERSTAND_FAILED, view.getDegradeReason());
        assertTrue(view.getText().contains("重试"), "必须给用户一个可重试的出口");
        // 关键：走"不推进摘要"的那条路，而不是普通保存
        verify(store).finishWithoutSummary(eq(conversation), any(), any(), any());
        verify(store, never()).finishWithSummary(any(), any(), any(), any(), any());
        assertFalse(view.getConditions().stream()
                        .anyMatch(c -> "黑色".equals(c.getValue())),
                "失败的一轮不能把『黑色』写进条件");
    }

    @Test
    @DisplayName("★ 精排失败：必须是 FAILED，绝不能渲染成「没有匹配到」")
    void matchFailure_isFailedNotNoMatch() {
        // 有主体才谈得上"去检索"：一条条件都没有时后端会先追问，压根不会走到精排
        givenConversation(3, summary("主体", "手机"));
        givenUnderstanding(understanding(TurnIntent.SEARCH, ""));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(1L, "手机")), 10, 0));
        AiQueryResponse degraded = new AiQueryResponse();
        degraded.setMatches(List.of());
        degraded.setAnswer("AI 服务繁忙，请稍后重试");
        degraded.setDegradeReason(AiQueryResponse.DEGRADE_LLM_ERROR);
        when(aiService.matchAgainst(anyString(), anyList(), anyLong())).thenReturn(degraded);
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "我丢了一部手机"));

        assertEquals(ConversationTurnView.STATUS_FAILED, view.getStatus());
        assertEquals(ConversationTurnView.DEGRADE_MATCH_FAILED, view.getDegradeReason());
        assertTrue(view.getCards().isEmpty());
        assertNotEquals(ConversationTurnView.STATUS_OK, view.getStatus(),
                "调用失败和「查了但没有」必须分得开 —— 混在一起会让用户以为平台上真没有");
    }

    @Test
    @DisplayName("整轮预算耗尽 → 归类成 TURN_TIMEOUT（等太久），而不是「服务不可用」")
    void exhaustedBudget_isClassifiedAsTimeout() {
        // 归类是纯函数，直接测它：真把时钟等到 90 秒之后再跑一遍，既慢又不稳定
        assertTrue(ConversationServiceImpl.budgetExhausted(System.currentTimeMillis()));
        assertTrue(ConversationServiceImpl.budgetExhausted(
                System.currentTimeMillis() + LlmTransport.MIN_ATTEMPT_MS - 1));
        assertFalse(ConversationServiceImpl.budgetExhausted(System.currentTimeMillis() + 60_000));
    }

    @Test
    @DisplayName("诊断里带上本轮生效的预算与耗时 —— 排查「为什么这么慢」时不用去翻服务器配置")
    void diagnosticsCarryBudgetAndElapsed() {
        givenConversation(3, "{}");
        givenUnderstanding(understanding(TurnIntent.SMALLTALK, "在的"));
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "在吗"));

        assertEquals(90_000L, view.getDiagnostics().getBudgetMs());
        assertEquals(1, view.getDiagnostics().getModelCalls(), "寒暄只需要一次模型调用");
        assertTrue(view.getDiagnostics().getElapsedMs() >= 0);
        assertEquals(100, view.getDiagnostics().getPromptTokens(), "token 用量要如实累计");
    }

    // ===================== 搜索用的是完整有效描述 =====================

    @Test
    @DisplayName("★ 纠正地点后检索：用的是【合并后的完整描述】，不是用户刚说的那一句")
    void searchUsesMergedDescription() {
        givenConversation(3, summary("主体", "手机", "地点", "图书馆"));
        givenUnderstanding(understanding(TurnIntent.SEARCH, "", fact("地点", "食堂", SearchSummary.CERTAIN)));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(1L, "手机")), 10, 0));
        when(aiService.matchAgainst(anyString(), anyList(), anyLong())).thenReturn(okResponse("找到 1 条", 1L, 90));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(1L, item(1L, "黑色手机")));
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "其实是在食堂"));

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(hybridRecallService).recall(query.capture(), anyInt(), anySet(), anyLong());
        assertTrue(query.getValue().contains("手机") && query.getValue().contains("食堂"),
                "只拿「其实是在食堂」去召回，主体和颜色全丢了：" + query.getValue());
        assertFalse(query.getValue().contains("图书馆"), "被纠正掉的旧地点不能残留在检索描述里");
        // 摘要也要推进：纠正后的条件必须被记住
        assertTrue(view.getConditions().stream().anyMatch(c -> "食堂".equals(c.getValue())));
        assertTrue(view.getConditions().stream().noneMatch(c -> "图书馆".equals(c.getValue())));
    }

    @Test
    @DisplayName("★ 「不是第二个，再找找」：被否掉的 itemId 要进【召回参数】（不是取完 Top-K 再删）")
    void exclusionIsAppliedToRecall() {
        givenConversation(3, summary("主体", "手机"));
        when(store.recentMessages(anyLong(), anyInt())).thenReturn(List.of(
                storedMessage(AiMessage.ROLE_USER, "u1", "手机", null),
                storedAssistant("a1", "找到两条", AiMessage.STATUS_OK,
                        card(11L, "手机A", 90), card(12L, "手机B", 80))));
        givenUnderstanding(understanding(TurnIntent.EXCLUDE_ITEM, "再找找", 2));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(13L, "手机C")), 10, 1));
        when(aiService.matchAgainst(anyString(), anyList(), anyLong()))
                .thenReturn(okResponse("换了一条", 13L, 70));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(13L, item(13L, "手机C")));
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "不是第二个，再找找"));

        ArgumentCaptor<Set<Long>> excluded = ArgumentCaptor.forClass(Set.class);
        verify(hybridRecallService).recall(anyString(), anyInt(), excluded.capture(), anyLong());
        assertEquals(Set.of(12L), excluded.getValue(), "「第二个」＝上一轮卡片顺序里的第 2 条");
        assertEquals(1, view.getDiagnostics().getExcludedCount(), "诊断要能看出排除真的生效了");
        // 排除项进摘要：下次搜索也得躲开它
        assertTrue(view.getConditions().size() >= 1);
    }

    @Test
    @DisplayName("★ 「解释某一条」那一轮不改变编号基准：接着说的「第二个」仍指搜索结果里的第 2 条")
    void explainTurnDoesNotShiftReferenceNumbering() {
        givenConversation(3, summary("主体", "手机"));
        // 历史：一次搜索给出两张卡 → 一轮解释（只带被问的那一张）
        when(store.recentMessages(anyLong(), anyInt())).thenReturn(List.of(
                storedAssistant("a1", "找到两条", AiMessage.STATUS_OK,
                        card(11L, "手机A", 90), card(12L, "手机B", 80)),
                storedAssistantWithIntent("a2", "它像在这里", TurnIntent.EXPLAIN_ITEM,
                        card(12L, "手机B", null))));
        givenUnderstanding(understanding(TurnIntent.EXCLUDE_ITEM, "再找找", 2));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(13L, "手机C")), 10, 1));
        when(aiService.matchAgainst(anyString(), anyList(), anyLong()))
                .thenReturn(okResponse("换了一条", 13L, 70));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(13L, item(13L, "手机C")));
        

        service.sendMessage(USER, CONV_ID, request("r1", "不是第二个，再找找"));

        ArgumentCaptor<Set<Long>> excluded = ArgumentCaptor.forClass(Set.class);
        verify(hybridRecallService).recall(anyString(), anyInt(), excluded.capture(), anyLong());
        // 若按"最后一条带卡的消息"算，此刻能看到只剩解释里的那一张，序号 2 就越界了，
        // 助手会反问"你指哪一条"—— 而用户明明刚指着第二张说过话
        assertEquals(Set.of(12L), excluded.getValue(),
                "编号基准是最近一次【搜索】的结果列表，不是解释那一轮");
    }

    @Test
    @DisplayName("★ 序号越界时追问，而不是猜一条 —— 猜错会记成永久排除项，那条物品再也出不来")
    void outOfRangeReference_asksInsteadOfGuessing() {
        givenConversation(3, "{}");
        when(store.recentMessages(anyLong(), anyInt())).thenReturn(List.of(
                storedAssistant("a1", "找到一条", AiMessage.STATUS_OK, card(11L, "手机A", 90))));
        givenUnderstanding(understanding(TurnIntent.EXPLAIN_ITEM, "", 5));
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "第九个为什么像"));

        assertTrue(view.getText().contains("第几个"), view.getText());
        verifyNoInteractions(aiService, itemService);
    }

    @Test
    @DisplayName("「第二个为什么像」：读的是那一条物品的【当前】数据，并且知道它已被认领")
    void explainItem_readsFreshStatus() {
        givenConversation(3, summary("主体", "手机"));
        when(store.recentMessages(anyLong(), anyInt())).thenReturn(List.of(
                storedAssistant("a1", "找到两条", AiMessage.STATUS_OK,
                        card(11L, "手机A", 90), card(12L, "手机B", 80))));
        givenUnderstanding(understanding(TurnIntent.EXPLAIN_ITEM, "", 2));

        Item claimed = item(12L, "手机B");
        claimed.setStatus(Item.STATUS_CLAIMED);
        when(itemService.getById(12L)).thenReturn(claimed);
        when(interpreter.explain(eq(claimed), any(), anyString(), anyLong()))
                .thenReturn(new TurnInterpreter.Explanation("它们的颜色和品牌都对得上。", 12L, 10, 20, null, null));
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "第二个为什么像"));

        assertTrue(view.getText().contains("已被认领"),
                "状态变了必须先说 —— 用户问『为什么像』多半是准备去认领：" + view.getText());
        assertEquals(Item.STATUS_CLAIMED, view.getCards().get(0).getStatus());
        assertEquals(12L, view.getCards().get(0).getItemId());
    }

    @Test
    @DisplayName("撤回条件：走普通代码，不调模型；剩下的条件继续检索")
    void retractCondition_doesNotCallModelForUnderstanding() {
        givenConversation(3, summary("主体", "钥匙", "颜色", "银色"));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(1L, "钥匙")), 10, 0));
        when(aiService.matchAgainst(anyString(), anyList(), anyLong()))
                .thenReturn(okResponse("找到 1 条", 1L, 88));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(1L, item(1L, "银色钥匙")));
        

        SendMessageRequest request = request("r1", "去掉「颜色」这个条件");
        RetractRequest retract = new RetractRequest();
        retract.setField("颜色");
        request.setRetract(retract);

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request);

        verifyNoInteractions(interpreter);
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(hybridRecallService).recall(query.capture(), anyInt(), anySet(), anyLong());
        assertFalse(query.getValue().contains("银色"), "撤回后不该再用这个条件检索");
        assertTrue(query.getValue().contains("钥匙"));
        assertTrue(view.getText().contains("已删除条件"), view.getText());
    }

    @Test
    @DisplayName("把最后一个条件也撤回掉：追问主体，不去发一次注定搜不到东西的检索")
    void retractLastCondition_asksForSubject() {
        givenConversation(3, summary("主体", "钥匙"));
        

        SendMessageRequest request = request("r1", "去掉主体这个条件");
        RetractRequest retract = new RetractRequest();
        retract.setField(SearchSummary.FIELD_SUBJECT);
        request.setRetract(retract);

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request);

        verifyNoInteractions(hybridRecallService, aiService, interpreter);
        assertTrue(view.getText().contains("你丢的是什么"), view.getText());
    }

    // ===================== 不该搜索的就不搜索 =====================

    @Test
    @DisplayName("★ 寒暄/平台流程：只回答，【不搜物品】（一次召回和精排都不该发生）")
    void smallTalkDoesNotSearch() {
        givenConversation(3, "{}");
        givenUnderstanding(understanding(TurnIntent.SMALLTALK, "我在，说说你丢了什么？"));
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "你好"));

        assertEquals("我在，说说你丢了什么？", view.getText());
        verifyNoInteractions(hybridRecallService, aiService, itemService);
    }

    @Test
    @DisplayName("一条条件都没有就去检索 = 搜一堆无关物品，所以改为追问主体")
    void searchWithoutAnyCondition_asksForSubject() {
        givenConversation(3, "{}");
        givenUnderstanding(understanding(TurnIntent.SEARCH, ""));
        

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "帮我找找"));

        verifyNoInteractions(hybridRecallService, aiService);
        assertTrue(view.getText().contains("你丢的是什么"), view.getText());
    }

    // ===================== 坏模型输出（R1） =====================

    @Test
    @DisplayName("★ 精排返回结构不可用：判成 FAILED + BAD_MODEL_OUTPUT，【不推进摘要】")
    void badMatchOutput_failsAndDoesNotAdvanceSummary() {
        AiConversation conversation = givenConversation(3, summary("主体", "手机"));
        givenUnderstanding(understanding(TurnIntent.SEARCH, ""));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(1L, "手机")), 10, 0));
        // 模型调通了，但返回的不是 JSON —— 旧实现会把它当"没匹配到"，还照常推进摘要
        AiQueryResponse bad = new AiQueryResponse();
        bad.setMatches(List.of());
        bad.setAnswer("```json 我不是 JSON");
        bad.setDegradeReason(AiQueryResponse.DEGRADE_BAD_RESPONSE);
        when(aiService.matchAgainst(anyString(), anyList(), anyLong())).thenReturn(bad);

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "手机"));

        assertEquals(ConversationTurnView.STATUS_FAILED, view.getStatus());
        assertEquals(ConversationTurnView.DEGRADE_BAD_MODEL_OUTPUT, view.getDegradeReason(),
                "坏返回必须和「查了但没有」分得开，否则用户会以为平台上真没有");
        verify(store).finishWithoutSummary(eq(conversation), any(), any(), any());
        verify(store, never()).finishWithSummary(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 理解返回结构不可用（缺 intent）：同样判 BAD_MODEL_OUTPUT，不兜底去搜")
    void badUnderstandingOutput_failsInsteadOfFallingBackToSearch() {
        givenConversation(3, "{}");
        when(interpreter.interpret(any(), anyList(), anyList(), anyString(), anyLong()))
                .thenReturn(TurnInterpreter.Interpretation.fail(TurnInterpreter.FAILED_OUTPUT,
                        "模型没有返回可识别的意图（intent=null）"));

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "手机"));

        assertEquals(ConversationTurnView.STATUS_FAILED, view.getStatus());
        assertEquals(ConversationTurnView.DEGRADE_BAD_MODEL_OUTPUT, view.getDegradeReason());
        assertTrue(view.getText().contains("重试"));
        verifyNoInteractions(hybridRecallService, aiService);
    }

    // ===================== 并发占用与版本（R3/R4） =====================

    @Test
    @DisplayName("★ 这一轮还在处理中（另一个请求在跑）：明确 409，不重复调模型")
    void busyTurn_isRejectedNotDuplicated() {
        givenConversation(3, "{}");
        when(store.beginTurn(any(), anyString(), any(), any())).thenReturn(
                new ConversationStore.ClaimOutcome(ConversationStore.Claim.BUSY, claimedPlaceholder()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendMessage(USER, CONV_ID, request("r1", "手机")));

        assertEquals(ResultCode.CONFLICT, e.getCode());
        verifyNoInteractions(interpreter, aiService, hybridRecallService);
    }

    @Test
    @DisplayName("★ 抢占时才发现这一轮已经完成：重放结果，不再调模型（并发下的幂等兜底）")
    void claimFindsCompletedTurn_replaysIt() {
        givenConversation(3, "{}");
        AiMessage finished = storedAssistant("r1", "已经答完了", AiMessage.STATUS_OK, card(7L, "手机A", 90));
        when(store.beginTurn(any(), anyString(), any(), any())).thenReturn(
                new ConversationStore.ClaimOutcome(ConversationStore.Claim.ALREADY_DONE, finished));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(7L, item(7L, "手机A")));

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "手机"));

        assertEquals("已经答完了", view.getText());
        verifyNoInteractions(interpreter, aiService, hybridRecallService);
    }

    @Test
    @DisplayName("★ 客户端看到的是旧版本：409 并提示刷新（否则「第二个」会按另一批结果解析）")
    void staleClientVersion_isRejected() {
        givenConversation(5, "{}");
        SendMessageRequest request = request("r1", "第二个为什么像");
        request.setConversationVersion(2);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendMessage(USER, CONV_ID, request));

        assertEquals(ResultCode.CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("刷新"), e.getMessage());
        verifyNoInteractions(interpreter, aiService, hybridRecallService);
    }

    @Test
    @DisplayName("★ 版本校验必须排在重放之后：旧页面重发【已完成的】请求应当拿到它自己的结果")
    void replayWinsOverStaleClientVersion() {
        givenConversation(5, "{}");
        when(store.findAssistant(CONV_ID, "r1"))
                .thenReturn(storedAssistant("r1", "上次的回答", AiMessage.STATUS_OK, card(7L, "手机A", 90)));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(7L, item(7L, "手机A")));
        SendMessageRequest request = request("r1", "手机");
        request.setConversationVersion(2);

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request);

        assertEquals("上次的回答", view.getText(), "重放优先于版本校验：这个请求本来就处理过了");
        verifyNoInteractions(interpreter, aiService);
    }

    // ===================== 一阶段收尾：缺陷 1 回归 =====================

    @Test
    @DisplayName("★ 缺陷1回归：重放旧 requestId 返回【纯历史快照】（旧条件+当时的版本+旧卡片），"
            + "绝不混入当前状态；之后的「第二个」仍指最新那批卡片")
    void replayOfOldRequestId_isPureHistoricalSnapshot() {
        // 用户先找黑色手机（r1），再纠正成白色（r2）。此刻会话已被 r2 推进：
        // 当前 version=2、条件是「白色」—— 重放 r1 时这两样都绝不能跟着旧条件一起回来
        givenConversation(2, summary("主体", "手机", "颜色", "白色"));
        // r1 当时落库的载荷：条件=黑色、卡片=黑色结果、结束时的会话版本=1
        AiMessage blackTurn = storedAssistant("r1", "找到一部黑色手机", AiMessage.STATUS_OK,
                card(11L, "黑色手机A", 90, 1), card(12L, "黑色手机B", 80, 2));
        MessagePayload blackPayload = MessagePayload.fromJson(blackTurn.getPayloadJson());
        blackPayload.setConditions(List.of(
                new SearchCondition("颜色", "黑色", SearchSummary.CERTAIN, "黑色手机")));
        blackPayload.setConversationVersion(1);
        blackTurn.setPayloadJson(blackPayload.toJson());
        when(store.findAssistant(CONV_ID, "r1")).thenReturn(blackTurn);
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(
                11L, item(11L, "黑色手机A"), 12L, item(12L, "黑色手机B"), 23L, item(23L, "白色手机C")));

        // ── 重放：拿到的是那一轮自己的历史结果，状态字段一项都不掺当前的 ──
        ConversationTurnView replay = service.sendMessage(USER, CONV_ID, request("r1", "黑色手机"));

        assertTrue(replay.isReplayed(), "前端靠它区分「这条历史回复」和「当前会话状态」");
        assertEquals("找到一部黑色手机", replay.getText());
        assertEquals(List.of("颜色=黑色"), conditionsOf(replay),
                "条件是当时的黑色 —— 回成当前的白色，等于把这次历史结果伪装成最新状态");
        assertEquals(1, replay.getVersion(),
                "版本也必须是当时的 1 —— 拼上当前的 2（旧条件+新版本）正是验收复现的缺陷");
        assertEquals(List.of(11L, 12L),
                replay.getCards().stream().map(ConversationCard::getItemId).toList(),
                "卡片是那一轮的，不换成当前候选");
        verifyNoInteractions(interpreter, hybridRecallService, aiService);
        verify(store, never()).saveUserMessageIfAbsent(any(), anyString(), anyString(), any());

        // ── 重放之后接着说「不是第二个」：编号基准仍是最新一次搜索（白色那轮）的卡片顺序 ──
        when(store.recentMessages(anyLong(), anyInt())).thenReturn(List.of(
                storedMessage(AiMessage.ROLE_USER, "r1", "黑色手机", null),
                storedAssistant("r1", "找到一部黑色手机", AiMessage.STATUS_OK,
                        card(11L, "黑色手机A", 90, 1), card(12L, "黑色手机B", 80, 2)),
                storedMessage(AiMessage.ROLE_USER, "r2", "改成白色", null),
                storedAssistant("r2", "找到一条白色手机", AiMessage.STATUS_OK,
                        card(21L, "白色手机A", 90, 1), card(22L, "白色手机B", 80, 2))));
        givenUnderstanding(understanding(TurnIntent.EXCLUDE_ITEM, "再找找", 2));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(23L, "白色手机C")), 10, 1));
        when(aiService.matchAgainst(anyString(), anyList(), anyLong()))
                .thenReturn(okResponse("换了一条", 23L, 70));

        service.sendMessage(USER, CONV_ID, request("r3", "不是第二个，再找找"));

        ArgumentCaptor<Set<Long>> excluded = ArgumentCaptor.forClass(Set.class);
        verify(hybridRecallService).recall(anyString(), anyInt(), excluded.capture(), anyLong());
        assertEquals(Set.of(22L), excluded.getValue(),
                "重放不得把编号基准拉回旧结果 —— 「第二个」必须是用户当前看到的那批里的第 2 条");
    }

    // ===================== 一阶段收尾：缺陷 2 回归 =====================

    @Test
    @DisplayName("★ 缺陷2回归：会话忙时被 409 拒绝的请求，收回刚落的用户消息 —— "
            + "历史里不留没有回复的孤儿 USER 行")
    void busyRejection_reclaimsJustInsertedUserMessage() {
        givenConversation(3, "{}");
        // 这一行确实是本次调用插进去的
        when(store.saveUserMessageIfAbsent(any(), anyString(), anyString(), any())).thenReturn(true);
        when(store.beginTurn(any(), anyString(), any(), any())).thenReturn(
                new ConversationStore.ClaimOutcome(ConversationStore.Claim.BUSY, null));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendMessage(USER, CONV_ID, request("r2", "白色手机")));

        assertEquals(ResultCode.CONFLICT, e.getCode());
        verify(store).discardUserMessageIfOrphan(CONV_ID, "r2");
        verifyNoInteractions(interpreter, aiService, hybridRecallService);
    }

    @Test
    @DisplayName("抢占因版本冲突被拒（409）：同样收回刚插入的用户消息")
    void versionConflictDuringClaim_reclaimsJustInsertedUserMessage() {
        givenConversation(3, "{}");
        when(store.saveUserMessageIfAbsent(any(), anyString(), anyString(), any())).thenReturn(true);
        when(store.beginTurn(any(), anyString(), any(), any())).thenThrow(
                new BusinessException(ResultCode.CONFLICT, "你看到的这段对话已经变了，请刷新后再发送"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendMessage(USER, CONV_ID, request("r2", "白色手机")));

        assertEquals(ResultCode.CONFLICT, e.getCode());
        verify(store).discardUserMessageIfOrphan(CONV_ID, "r2");
    }

    @Test
    @DisplayName("★ 同 requestId 的并发重试正在跑：409 但【不收回】用户消息 —— "
            + "那一行正被另一次执行使用，删了会把进行中的那轮搞成没有原话")
    void busyWithPreexistingUserMessage_isNotReclaimed() {
        givenConversation(3, "{}");
        // 行已存在（幂等放行），不是这次调用插的
        when(store.saveUserMessageIfAbsent(any(), anyString(), anyString(), any())).thenReturn(false);
        when(store.beginTurn(any(), anyString(), any(), any())).thenReturn(
                new ConversationStore.ClaimOutcome(ConversationStore.Claim.BUSY, null));

        assertThrows(BusinessException.class,
                () -> service.sendMessage(USER, CONV_ID, request("r1", "手机")));

        verify(store, never()).discardUserMessageIfOrphan(any(), anyString());
    }

    @Test
    @DisplayName("版本一致时正常放行（校验不能误伤正常请求）")
    void matchingClientVersion_isAccepted() {
        givenConversation(3, summary("主体", "手机"));
        givenUnderstanding(understanding(TurnIntent.SMALLTALK, "在的"));
        SendMessageRequest request = request("r1", "你好");
        request.setConversationVersion(3);

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request);

        assertEquals(ConversationTurnView.STATUS_OK, view.getStatus());
    }

    // ===================== 落库顺序（R7） =====================

    @Test
    @DisplayName("★ 三步顺序：读历史 → 落用户原话 → 占用会话（每一步错了都会留下可见的毛病）")
    void persistOrderIsHistoryThenUserMessageThenClaim() {
        givenConversation(3, summary("主体", "手机"));
        givenUnderstanding(understanding(TurnIntent.SMALLTALK, "在的"));

        service.sendMessage(USER, CONV_ID, request("r1", "你好"));

        // ① 历史必须最先读：晚读的话，本轮原话会混进"最近四轮"里（模型以为用户说了一遍），
        //    助手占位行也会被当成一轮对话（一个空回答占掉一个名额）
        // ② 用户原话必须先于占用落库：消息按自增 id 排序，
        //    反了刷新之后界面上就变成"助手先说话、用户后说话"（R7 复现过）
        InOrder order = inOrder(store);
        order.verify(store).recentMessages(eq(CONV_ID), anyInt());
        order.verify(store).saveUserMessageIfAbsent(eq(CONV_ID), eq("r1"), eq("你好"), any());
        order.verify(store).beginTurn(any(), eq("r1"), any(), any());
    }

    // ===================== 失败时回传的条件（R8） =====================

    @Test
    @DisplayName("★ 精排失败：回传的必须是【上一次有效的条件】，不是本轮合并出来但没提交的那份")
    void failureReturnsPersistedConditionsNotMergedOnes() {
        // 会话里记着「地点=图书馆」，本轮用户改口说「其实是在食堂」
        AiConversation conversation = givenConversation(3, summary("主体", "手机", "地点", "图书馆"));
        givenUnderstanding(understanding(TurnIntent.SEARCH, "", fact("地点", "食堂", SearchSummary.CERTAIN)));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(1L, "手机")), 10, 0));
        AiQueryResponse degraded = new AiQueryResponse();
        degraded.setMatches(List.of());
        degraded.setAnswer("AI 服务繁忙");
        degraded.setDegradeReason(AiQueryResponse.DEGRADE_LLM_ERROR);
        when(aiService.matchAgainst(anyString(), anyList(), anyLong())).thenReturn(degraded);

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "其实是在食堂"));

        assertEquals(ConversationTurnView.STATUS_FAILED, view.getStatus());
        assertTrue(conditionsOf(view).contains("地点=图书馆"),
                "这一轮没提交，面板必须停在服务端的状态上：" + conditionsOf(view));
        assertFalse(conditionsOf(view).contains("地点=食堂"),
                "回传没提交的新条件，用户会以为它生效了，却没法把它撤掉");

        // 走的是"不推进摘要"的那条路，而且落库的条件快照同样是旧的那份
        verify(store).finishWithoutSummary(eq(conversation), any(), any(), any());
        ArgumentCaptor<AiMessage> saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(store).finishWithoutSummary(any(), any(), saved.capture(), any());
        MessagePayload payload = MessagePayload.fromJson(saved.getValue().getPayloadJson());
        assertTrue(payload.getConditions().stream().anyMatch(c -> "图书馆".equals(c.getValue())),
                "落库的条件快照也必须是旧的那份，否则刷新后两边又不一致");
    }

    @Test
    @DisplayName("★ 失败后重试成功：这一次才把新条件写进摘要（完整链路）")
    void retryAfterFailureCommitsThen() {
        AiConversation conversation = givenConversation(3, summary("主体", "手机", "地点", "图书馆"));
        // 第一次：理解成功、精排失败
        givenUnderstanding(understanding(TurnIntent.SEARCH, "", fact("地点", "食堂", SearchSummary.CERTAIN)));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(1L, "手机")), 10, 0));
        AiQueryResponse degraded = new AiQueryResponse();
        degraded.setMatches(List.of());
        degraded.setAnswer("AI 服务繁忙");
        degraded.setDegradeReason(AiQueryResponse.DEGRADE_LLM_ERROR);
        when(aiService.matchAgainst(anyString(), anyList(), anyLong())).thenReturn(degraded);

        ConversationTurnView failed = service.sendMessage(USER, CONV_ID, request("r1", "其实是在食堂"));
        assertEquals(ConversationTurnView.STATUS_FAILED, failed.getStatus());

        // 第二次（重试）：精排成功
        when(aiService.matchAgainst(anyString(), anyList(), anyLong()))
                .thenReturn(okResponse("找到 1 条", 1L, 90));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(1L, item(1L, "手机")));
        ConversationTurnView retried = service.sendMessage(USER, CONV_ID, request("r1", "其实是在食堂"));

        assertEquals(ConversationTurnView.STATUS_OK, retried.getStatus());
        assertTrue(conditionsOf(retried).contains("地点=食堂"), "重试成功之后，新条件才生效");
        assertFalse(conditionsOf(retried).contains("地点=图书馆"));
        verify(store).finishWithSummary(any(), any(), any(), any(), any());
    }

    // ===================== 占用被接管的收尾（R7） =====================

    @Test
    @DisplayName("★ 占用被接管：拿下接管者的结果呈现给用户，不让他白重试一次")
    void supersededTurnHandsOverTheWinnersResult() {
        givenConversation(3, summary("主体", "手机"));
        givenUnderstanding(understanding(TurnIntent.SMALLTALK, "在的"));
        doThrow(new ConversationStore.TurnSupersededException())
                .when(store).finishWithSummary(any(), any(), any(), any(), any());
        AiMessage winner = storedAssistant("r1", "接管者写好的回答", AiMessage.STATUS_OK, card(7L, "手机A", 90));
        when(store.findAssistant(CONV_ID, "r1")).thenReturn(null, winner);
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(7L, item(7L, "手机A")));

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "你好"));

        assertEquals("接管者写好的回答", view.getText());
        assertEquals(ConversationTurnView.STATUS_OK, view.getStatus());
    }

    @Test
    @DisplayName("★ 占用被接管且接管者还没写完：明确 409 请用户重试，不提交半截状态")
    void supersededTurnWithoutWinnerIs409() {
        givenConversation(3, summary("主体", "手机"));
        givenUnderstanding(understanding(TurnIntent.SMALLTALK, "在的"));
        doThrow(new ConversationStore.TurnSupersededException())
                .when(store).finishWithSummary(any(), any(), any(), any(), any());
        when(store.findAssistant(CONV_ID, "r1")).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendMessage(USER, CONV_ID, request("r1", "你好")));

        assertEquals(ResultCode.CONFLICT, e.getCode());
    }

    // ===================== 换主体不能误判（R9） =====================

    @Test
    @DisplayName("★ 主体从「车钥匙」说成「钥匙」不算换物品：颜色、特征统统不能丢")
    void sameSubjectParaphraseDoesNotResetConditions() {
        // 会话里已经记着 主体=车钥匙、颜色=银色、特征=挂着红色绳子
        AiConversation conversation = givenConversation(3,
                summary("主体", "车钥匙", "颜色", "银色", "特征", "挂着红色绳子"));
        // 模型误判成"换了物品"，并且只补了一个不确定的地点
        TurnInterpreter.Interpretation misjudged = new TurnInterpreter.Interpretation(
                TurnIntent.SEARCH, null, "钥匙", true, List.of(),
                List.of(), "", 100, 20, null, null);
        givenUnderstanding(misjudged);
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(1L, "钥匙")), 10, 0));
        when(aiService.matchAgainst(anyString(), anyList(), anyLong())).thenReturn(okResponse("找到 1 条", 1L, 88));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(1L, item(1L, "银色车钥匙")));

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "钥匙可能落在图书馆"));

        assertTrue(conditionsOf(view).contains("颜色=银色"),
                "「车钥匙」和「钥匙」是同一个东西，不能把说过的条件清空：" + conditionsOf(view));
        assertTrue(conditionsOf(view).contains("特征=挂着红色绳子"), String.valueOf(conditionsOf(view)));
        assertTrue(conditionsOf(view).contains("主体=车钥匙"), "主体也不该被改写成「钥匙」");
    }

    @Test
    @DisplayName("换成真的另一件东西（手机 → 车钥匙）：该清空的照常清空")
    void genuinelyDifferentSubjectResetsConditions() {
        AiConversation conversation = givenConversation(3, summary("主体", "手机", "颜色", "黑色"));
        TurnInterpreter.Interpretation switched = new TurnInterpreter.Interpretation(
                TurnIntent.SEARCH, null, "车钥匙", true, List.of(),
                List.of(), "", 100, 20, null, null);
        givenUnderstanding(switched);
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(1L, "钥匙")), 10, 0));
        when(aiService.matchAgainst(anyString(), anyList(), anyLong())).thenReturn(okResponse("找到 1 条", 1L, 88));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(1L, item(1L, "银色车钥匙")));

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "现在换找车钥匙"));

        assertTrue(conditionsOf(view).contains("主体=车钥匙"));
        assertFalse(conditionsOf(view).contains("颜色=黑色"), "上一件东西的颜色对新的一样没意义");
    }

    @Test
    @DisplayName("互相包含判定：包含关系一律不算换主体，互不包含才算")
    void subjectChangeDetection() {
        assertFalse(ConversationServiceImpl.isRealSubjectChange("车钥匙", "钥匙"));
        assertFalse(ConversationServiceImpl.isRealSubjectChange("钥匙", "车钥匙"));
        assertFalse(ConversationServiceImpl.isRealSubjectChange("手机", "手机"));
        assertTrue(ConversationServiceImpl.isRealSubjectChange("手机", "车钥匙"));
        assertTrue(ConversationServiceImpl.isRealSubjectChange("车钥匙", "校园卡"));
    }

    // ===================== 卡片序号（R2） =====================

    @Test
    @DisplayName("★ 解释卡片沿用【原编号】：问第二个，屏幕上就得写 2，不能重编成 1")
    void explainCardKeepsOriginalIndex() {
        givenConversation(3, summary("主体", "手机"));
        when(store.recentMessages(anyLong(), anyInt())).thenReturn(List.of(
                storedAssistant("a1", "找到两条", AiMessage.STATUS_OK,
                        card(11L, "手机A", 90, 1), card(12L, "手机B", 80, 2))));
        givenUnderstanding(understanding(TurnIntent.EXPLAIN_ITEM, "", 2));
        when(itemService.getById(12L)).thenReturn(item(12L, "手机B"));
        when(interpreter.explain(any(), any(), anyString(), anyLong()))
                .thenReturn(new TurnInterpreter.Explanation("像在这里。", 12L, 10, 20, null, null));

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "第二个为什么像"));

        assertEquals(12L, view.getCards().get(0).getItemId());
        assertEquals(2, view.getCards().get(0).getIndex(),
                "重编成 1 的话，用户下次说「不是第二个」就再也对不上了");
    }

    @Test
    @DisplayName("★ 刷新恢复后序号必须还在 —— 回填时漏掉它，界面上刚显示过的「2」就没了")
    void refreshedCardsKeepTheirIndex() {
        AiConversation conversation = conversation(3, summary("主体", "手机"));
        when(store.requireOwned(CONV_ID, USER)).thenReturn(conversation);
        when(store.messages(anyLong(), anyInt())).thenReturn(List.of(
                storedMessage(AiMessage.ROLE_USER, "u1", "手机", null),
                storedAssistant("a1", "找到两条", AiMessage.STATUS_OK,
                        card(11L, "手机A", 90, 1), card(12L, "手机B", 80, 2))));
        when(itemService.mapByIds(anyCollection())).thenReturn(
                Map.of(11L, item(11L, "手机A"), 12L, item(12L, "手机B")));

        ConversationView view = service.getSession(USER, CONV_ID);

        ConversationMessageView assistant = view.getMessages().get(1);
        assertEquals(List.of(1, 2),
                assistant.getCards().stream().map(ConversationCard::getIndex).toList());
    }

    @Test
    @DisplayName("搜索结果卡片的序号就是它在结果里的位置（1 起，由后端定）")
    void searchCardsCarryPositionIndex() {
        givenConversation(3, summary("主体", "手机"));
        givenUnderstanding(understanding(TurnIntent.SEARCH, ""));
        when(hybridRecallService.recall(anyString(), anyInt(), anySet(), anyLong()))
                .thenReturn(new CandidateService.RecallResult(List.of(item(1L, "手机")), 10, 0));
        AiQueryResponse response = okResponse("找到 2 条", 1L, 90);
        MatchResult second = new MatchResult();
        second.setItemId(2L);
        second.setScore(80);
        second.setReason("也像");
        response.setMatches(List.of(response.getMatches().get(0), second));
        when(aiService.matchAgainst(anyString(), anyList(), anyLong())).thenReturn(response);
        when(itemService.mapByIds(anyCollection()))
                .thenReturn(Map.of(1L, item(1L, "手机A"), 2L, item(2L, "手机B")));

        ConversationTurnView view = service.sendMessage(USER, CONV_ID, request("r1", "手机"));

        assertEquals(List.of(1, 2), view.getCards().stream().map(ConversationCard::getIndex).toList());
    }

    // ===================== 会话与并发 =====================

    @Test
    @DisplayName("★ 同会话并发写：版本冲突时抛 409，绝不覆盖别人刚写好的状态")
    void versionConflict_throws409() {
        givenConversation(3, "{}");
        givenUnderstanding(understanding(TurnIntent.SMALLTALK, "在的"));
        doThrow(new ConversationStore.TurnSupersededException()).when(store).finishWithSummary(any(), any(), any(), any(), any());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendMessage(USER, CONV_ID, request("r1", "在吗")));

        assertEquals(ResultCode.CONFLICT, e.getCode());
    }

    @Test
    @DisplayName("★ 过期会话：用 410 而不是 409 —— 「状态没了」和「时机不对」必须机器可辨")
    void expiredConversation_isRejectedWithGone() {
        AiConversation expired = conversation(3, "{}");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(store.requireOwned(CONV_ID, USER)).thenReturn(expired);
        when(store.isExpired(expired)).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.sendMessage(USER, CONV_ID, request("r1", "手机")));

        assertEquals(ResultCode.GONE, e.getCode(),
                "混成 409 的话，前端只能靠匹配文案来区分「该重试」和「该重新开始」");
        assertTrue(e.getMessage().contains("过期"));
    }

    @Test
    @DisplayName("★ 没登录：401，绝不放行成「没有会话」（GET 会被拦截器放行，这里必须自己拦）")
    void nullUser_isUnauthorized() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.currentSession(null));

        assertEquals(ResultCode.UNAUTHORIZED, e.getCode());
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("刷新恢复：没有可用会话时如实报告「刚清掉过一段过期的」")
    void currentSession_reportsPreviousExpired() {
        when(store.findCurrent(USER)).thenReturn(null);
        when(store.purgeExpiredOfUser(USER)).thenReturn(2);

        SessionResponse response = service.currentSession(USER);

        assertNull(response.getConversation());
        assertTrue(response.isPreviousExpired(), "过期和「第一次来」要分得开，否则用户以为条件被弄丢了");
    }

    @Test
    @DisplayName("新对话：创建一段保留期为 7 天的空会话")
    void startNewSession_createsEmptyConversation() {
        AiConversation created = conversation(0, SearchSummary.empty().toJson());
        when(store.create(eq(USER), any())).thenReturn(created);

        ConversationView view = service.startNewSession(USER);

        assertEquals(CONV_ID, view.getId());
        assertTrue(view.getConditions().isEmpty());
        assertTrue(view.getMessages().isEmpty());
    }

    // ===================== 辅助 =====================

    /** 把返回体里的条件渲染成 `字段=值` 便于断言 */
    private static List<String> conditionsOf(ConversationTurnView view) {
        return (view.getConditions() == null ? List.<SearchCondition>of() : view.getConditions())
                .stream().map(c -> c.getField() + "=" + c.getValue()).toList();
    }

    private AiConversation givenConversation(int version, String summaryJson) {
        AiConversation conversation = conversation(version, summaryJson);
        when(store.requireOwned(CONV_ID, USER)).thenReturn(conversation);
        return conversation;
    }

    private static AiConversation conversation(int version, String summaryJson) {
        AiConversation conversation = new AiConversation();
        conversation.setId(CONV_ID);
        conversation.setUserId(USER);
        conversation.setVersion(version);
        conversation.setSummaryJson(summaryJson);
        conversation.setExpiresAt(LocalDateTime.now().plusDays(7));
        return conversation;
    }

    private void givenUnderstanding(TurnInterpreter.Interpretation interpretation) {
        when(interpreter.interpret(any(), anyList(), anyList(), anyString(), anyLong()))
                .thenReturn(interpretation);
    }

    private static SendMessageRequest request(String requestId, String content) {
        SendMessageRequest request = new SendMessageRequest();
        request.setRequestId(requestId);
        request.setContent(content);
        return request;
    }

    private static TurnInterpreter.Interpretation understanding(String intent, String reply,
                                                                SearchFact... facts) {
        return new TurnInterpreter.Interpretation(intent, null, null, false, List.of(facts),
                List.of(), reply, 100, 20, null, null);
    }

    private static TurnInterpreter.Interpretation understanding(String intent, String reply, int referenceIndex) {
        return new TurnInterpreter.Interpretation(intent, referenceIndex, null, false, List.of(),
                List.of(), reply, 100, 20, null, null);
    }

    private static SearchFact fact(String field, String value, String certainty) {
        SearchFact fact = new SearchFact();
        fact.setField(field);
        fact.setValue(value);
        fact.setCertainty(certainty);
        return fact;
    }

    /** 用一串"字段/值"构造一份已有摘要的 JSON */
    private static String summary(String... fieldValuePairs) {
        SearchSummary summary = SearchSummary.empty();
        for (int i = 0; i + 1 < fieldValuePairs.length; i += 2) {
            summary = summary.merge(List.of(fact(fieldValuePairs[i], fieldValuePairs[i + 1], null)),
                    List.of(), "（历史）", false);
        }
        return summary.toJson();
    }

    private static Item item(Long id, String title) {
        Item item = new Item();
        item.setId(id);
        item.setTitle(title);
        item.setCategory("电子产品");
        item.setLocation("图书馆三楼");
        item.setType(Item.TYPE_FOUND);
        item.setStatus(Item.STATUS_UNCLAIMED);
        return item;
    }

    private static ConversationCard card(Long itemId, String title, Integer score) {
        return card(itemId, title, score, null);
    }

    private static ConversationCard card(Long itemId, String title, Integer score, Integer index) {
        ConversationCard card = new ConversationCard();
        card.setItemId(itemId);
        card.setTitle(title);
        card.setCategory("电子产品");
        card.setLocation("图书馆三楼");
        card.setStatus(Item.STATUS_UNCLAIMED);
        card.setScore(score);
        card.setReason("很像");
        card.setIndex(index);
        return card;
    }

    private static AiMessage storedAssistant(String requestId, String text, String status,
                                             ConversationCard... cards) {
        AiMessage message = new AiMessage();
        message.setConversationId(CONV_ID);
        message.setRequestId(requestId);
        message.setRole(AiMessage.ROLE_ASSISTANT);
        message.setContent(text);
        message.setStatus(status);
        message.setIntent(TurnIntent.SEARCH);
        MessagePayload payload = new MessagePayload();
        payload.setCards(cards == null ? List.of() : List.of(cards));
        payload.setConditions(List.of());
        message.setPayloadJson(payload.toJson());
        return message;
    }

    private static AiMessage storedAssistantWithIntent(String requestId, String text, String intent,
                                                       ConversationCard... cards) {
        AiMessage message = storedAssistant(requestId, text, AiMessage.STATUS_OK, cards);
        message.setIntent(intent);
        return message;
    }

    private static AiMessage storedMessage(String role, String requestId, String text, String payloadJson) {
        AiMessage message = new AiMessage();
        message.setConversationId(CONV_ID);
        message.setRequestId(requestId);
        message.setRole(role);
        message.setContent(text);
        message.setStatus(AiMessage.STATUS_OK);
        message.setPayloadJson(payloadJson);
        return message;
    }

    private static AiQueryResponse okResponse(String answer, Long itemId, int score) {
        AiQueryResponse response = new AiQueryResponse();
        response.setAnswer(answer);
        MatchResult match = new MatchResult();
        match.setItemId(itemId);
        match.setScore(score);
        match.setReason("很像");
        response.setMatches(List.of(match));
        response.setCandidateCount(1);
        response.setModel("test-model");
        response.setPromptTokens(500);
        response.setCompletionTokens(60);
        response.setElapsedMs(1200);
        response.setMinScore(0);
        response.setMaxResults(5);
        return response;
    }
}
