package com.lostfound.service.impl;

import com.lostfound.dto.AiQueryResponse;
import com.lostfound.dto.MatchResult;
import com.lostfound.entity.Item;
import com.lostfound.entity.Notification;
import com.lostfound.entity.NotificationMatch;
import com.lostfound.service.AiService;
import com.lostfound.service.CandidateService;
import com.lostfound.service.ItemService;
import com.lostfound.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 自动匹配执行体的单元测试（纯 Mockito，<b>不调用任何模型</b>）。
 *
 * <p>两个方向都覆盖：
 * <ul>
 *   <li><b>发布失物</b> → 候选池是招领，收件人是发布者，一次发布一条汇总通知；</li>
 *   <li><b>发布招领</b> → 候选池是失物，收件人是各位失主，每人最多一条。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AutoMatchRunnerTest {

    @Mock
    private AiService aiService;
    @Mock
    private CandidateService candidateService;
    @Mock
    private ItemService itemService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private AutoMatchRunner runner;

    private static final Long LOST_OWNER = 100L;
    private static final Long OTHER_USER = 200L;
    private static final Long THIRD_USER = 300L;
    /** 招领的发布者 —— 必须是"别人"，否则会触发"跳过自己发的"那条规则 */
    private static final Long FINDER = 400L;
    private static final Long LOST_ID = 7L;
    private static final Long FOUND_ID = 9L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(runner, "autoMinScore", 80);
        ReflectionTestUtils.setField(runner, "autoMaxResults", 5);
        ReflectionTestUtils.setField(runner, "recallTopK", 50);
    }

    // ===================== 测试数据 =====================

    private Item lostItem() {
        Item item = new Item();
        item.setId(LOST_ID);
        item.setUserId(LOST_OWNER);
        item.setType(Item.TYPE_LOST);
        item.setTitle("黑色华为手机");
        item.setCategory("电子产品");
        item.setLocation("图书馆三楼");
        item.setDescription("屏幕右下角有个小磕痕");
        return item;
    }

    private Item foundItem() {
        Item item = new Item();
        item.setId(FOUND_ID);
        item.setUserId(FINDER);
        item.setType(Item.TYPE_FOUND);
        item.setTitle("拾到黑色华为手机");
        item.setCategory("电子产品");
        item.setLocation("图书馆三楼");
        item.setDescription("在图书馆三楼捡到黑色华为手机");
        return item;
    }

    private Item itemRef(Long id, Long ownerId, String category) {
        Item item = new Item();
        item.setId(id);
        item.setUserId(ownerId);
        item.setCategory(category);
        item.setTitle("物品" + id);
        return item;
    }

    private static MatchResult match(long itemId, int score) {
        MatchResult m = new MatchResult();
        m.setItemId(itemId);
        m.setScore(score);
        m.setReason("颜色和型号都对得上");
        return m;
    }

    private void givenAiQuery(String degradeReason, MatchResult... matches) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setMatches(List.of(matches));
        resp.setDegradeReason(degradeReason);
        when(aiService.query(anyString())).thenReturn(resp);
    }

    private void givenAiMatchAgainst(String degradeReason, MatchResult... matches) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setMatches(List.of(matches));
        resp.setDegradeReason(degradeReason);
        when(aiService.matchAgainst(anyString(), any())).thenReturn(resp);
    }

    private void givenItems(Map<Long, Item> map) {
        when(itemService.mapByIds(any())).thenReturn(map);
    }

    private static Map<Long, Item> mapOf(Item... items) {
        Map<Long, Item> m = new HashMap<>();
        for (Item i : items) {
            m.put(i.getId(), i);
        }
        return m;
    }

    // ===================== 场景A：发布失物 → 找招领 =====================

    @Test
    @DisplayName("A. 够格的 3 条匹配合成【一条】汇总通知，明细里有分数和理由")
    void lostPublishCreatesOneAggregatedNotification() {
        givenAiQuery(null, match(11L, 95), match(12L, 88), match(13L, 82));
        givenItems(mapOf(itemRef(11L, OTHER_USER, "电子产品"),
                itemRef(12L, OTHER_USER, "电子产品"), itemRef(13L, OTHER_USER, "电子产品")));
        when(notificationService.createWithMatches(any(), any())).thenReturn(true);

        runner.match(lostItem());

        ArgumentCaptor<Notification> nCaptor = ArgumentCaptor.forClass(Notification.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationMatch>> dCaptor = ArgumentCaptor.forClass(List.class);
        verify(notificationService).createWithMatches(nCaptor.capture(), dCaptor.capture());

        assertEquals(LOST_OWNER, nCaptor.getValue().getReceiverId(), "收件人是失物主人");
        assertEquals(LOST_ID, nCaptor.getValue().getSourceItemId());
        assertTrue(nCaptor.getValue().getContent().contains("3 条"), nCaptor.getValue().getContent());
        assertEquals(3, dCaptor.getValue().size(), "点开要能看到具体是哪三条");
        assertEquals(11L, dCaptor.getValue().get(0).getFoundItemId());
        assertEquals(95, dCaptor.getValue().get(0).getScore());
        assertEquals(LOST_ID, dCaptor.getValue().get(0).getLostItemId());
    }

    @Test
    @DisplayName("A. 门槛：79 挡下、80 保留")
    void lostPublishFiltersByThreshold() {
        givenAiQuery(null, match(11L, 95), match(12L, 80), match(13L, 79));
        givenItems(mapOf(itemRef(11L, OTHER_USER, "电子产品"), itemRef(12L, OTHER_USER, "电子产品"),
                itemRef(13L, OTHER_USER, "电子产品")));
        when(notificationService.createWithMatches(any(), any())).thenReturn(true);

        runner.match(lostItem());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationMatch>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationService).createWithMatches(any(), captor.capture());
        assertEquals(List.of(11L, 12L),
                captor.getValue().stream().map(NotificationMatch::getFoundItemId).toList());
    }

    @Test
    @DisplayName("A. 没有达标结果 / 降级 / 全部是自己发的 → 都不发通知")
    void lostPublishSendsNothingInSeveralCases() {
        // ① 都不够分
        givenAiQuery(null, match(11L, 70), match(12L, 40));
        runner.match(lostItem());
        verify(notificationService, never()).createWithMatches(any(), any());

        // ② 降级（模型挂了）—— 不能把"调用失败"当成"没匹配到"
        reset(aiService, notificationService);
        givenAiQuery(AiQueryResponse.DEGRADE_LLM_ERROR, match(11L, 99));
        runner.match(lostItem());
        verify(notificationService, never()).createWithMatches(any(), any());

        // ③ 召回为空
        reset(aiService, notificationService);
        givenAiQuery(AiQueryResponse.DEGRADE_NO_CANDIDATES);
        runner.match(lostItem());
        verify(notificationService, never()).createWithMatches(any(), any());
    }

    @Test
    @DisplayName("A. 自己发给自己不算：招领也是同一人发的 → 不通知")
    void lostPublishSkipsOwnFoundItems() {
        givenAiQuery(null, match(11L, 95));
        givenItems(mapOf(itemRef(11L, LOST_OWNER, "电子产品")));

        runner.match(lostItem());

        verify(notificationService, never()).createWithMatches(any(), any());
    }

    @Test
    @DisplayName("A. 异常隔离：查询抛异常、写通知抛异常、item 为 null，都不能冒出去")
    void lostPublishSwallowsEverything() {
        when(aiService.query(anyString())).thenThrow(new RuntimeException("模型炸了"));
        assertDoesNotThrow(() -> runner.match(lostItem()));

        reset(aiService, itemService, notificationService);
        givenAiQuery(null, match(11L, 95));
        givenItems(mapOf(itemRef(11L, OTHER_USER, "电子产品")));
        when(notificationService.createWithMatches(any(), any())).thenThrow(new RuntimeException("库抖动"));
        assertDoesNotThrow(() -> runner.match(lostItem()));

        assertDoesNotThrow(() -> runner.match(null));
    }

    // ===================== 场景B：发布招领 → 找失物 =====================

    @Test
    @DisplayName("★ B. 反向匹配：候选池换成失物，整轮【只调用一次】大模型")
    void foundPublishRecallsLostPoolWithSingleLlmCall() {
        when(candidateService.recall(anyString(), eq(Item.TYPE_LOST), anyInt()))
                .thenReturn(List.of(itemRef(11L, OTHER_USER, "电子产品")));
        givenAiMatchAgainst(null, match(11L, 92));
        givenItems(mapOf(itemRef(11L, OTHER_USER, "电子产品")));
        when(notificationService.createWithMatches(any(), any())).thenReturn(true);

        runner.match(foundItem());

        // 池子必须是失物 —— 这是反向匹配的全部秘密
        verify(candidateService).recall(anyString(), eq(Item.TYPE_LOST), eq(50));
        // 整轮只调一次模型，而且用的是抽出来的"精排"那一半
        verify(aiService, times(1)).matchAgainst(anyString(), any());
        verify(aiService, never()).query(anyString());

        ArgumentCaptor<Notification> nCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).createWithMatches(nCaptor.capture(), any());
        assertEquals(OTHER_USER, nCaptor.getValue().getReceiverId(), "收件人是失物的主人，不是招领发布者");
        assertEquals(FOUND_ID, nCaptor.getValue().getSourceItemId());
        assertTrue(nCaptor.getValue().getContent().contains("拾到黑色华为手机"), nCaptor.getValue().getContent());
    }

    @Test
    @DisplayName("★ B. 按失主分组：同一次发布，每位失主最多收到一条汇总通知")
    void foundPublishGroupsByOwner() {
        when(candidateService.recall(anyString(), eq(Item.TYPE_LOST), anyInt()))
                .thenReturn(List.of(itemRef(11L, OTHER_USER, "电子产品"), itemRef(12L, OTHER_USER, "电子产品"),
                        itemRef(13L, THIRD_USER, "电子产品")));
        givenAiMatchAgainst(null, match(11L, 95), match(12L, 88), match(13L, 91));
        givenItems(mapOf(itemRef(11L, OTHER_USER, "电子产品"), itemRef(12L, OTHER_USER, "电子产品"),
                itemRef(13L, THIRD_USER, "电子产品")));
        when(notificationService.createWithMatches(any(), any())).thenReturn(true);

        runner.match(foundItem());

        // 3 条匹配、2 位失主 → 2 条通知（不是 3 条）
        ArgumentCaptor<Notification> nCaptor = ArgumentCaptor.forClass(Notification.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationMatch>> dCaptor = ArgumentCaptor.forClass(List.class);
        verify(notificationService, times(2)).createWithMatches(nCaptor.capture(), dCaptor.capture());

        List<Notification> notifications = nCaptor.getAllValues();
        assertEquals(List.of(OTHER_USER, THIRD_USER),
                notifications.stream().map(Notification::getReceiverId).sorted().toList());

        int owner200 = notifications.get(0).getReceiverId().equals(OTHER_USER) ? 0 : 1;
        assertEquals(2, dCaptor.getAllValues().get(owner200).size(), "同一失主的两条失物合成一条通知");
        assertEquals(1, dCaptor.getAllValues().get(1 - owner200).size());
    }

    @Test
    @DisplayName("★ B. 类别守卫：明确不一致（证件 vs 电子产品）→ 不通知")
    void foundPublishSkipsClearCategoryMismatch() {
        when(candidateService.recall(anyString(), eq(Item.TYPE_LOST), anyInt()))
                .thenReturn(List.of(itemRef(11L, OTHER_USER, "证件")));
        givenAiMatchAgainst(null, match(11L, 95));   // 模型给了高分，但类别明显不对
        givenItems(mapOf(itemRef(11L, OTHER_USER, "证件")));

        runner.match(foundItem());

        verify(notificationService, never()).createWithMatches(any(), any());
    }

    @Test
    @DisplayName("B. 类别守卫：泛分类「其他」放行，交给大模型按文本判断")
    void foundPublishAllowsGenericCategory() {
        when(candidateService.recall(anyString(), eq(Item.TYPE_LOST), anyInt()))
                .thenReturn(List.of(itemRef(11L, OTHER_USER, "其他")));
        givenAiMatchAgainst(null, match(11L, 95));
        givenItems(mapOf(itemRef(11L, OTHER_USER, "其他")));
        when(notificationService.createWithMatches(any(), any())).thenReturn(true);

        runner.match(foundItem());

        verify(notificationService).createWithMatches(any(), any());
    }

    @Test
    @DisplayName("B. 跳过自己发的失物，也不提醒自己")
    void foundPublishSkipsOwnLostItems() {
        when(candidateService.recall(anyString(), eq(Item.TYPE_LOST), anyInt()))
                .thenReturn(List.of(itemRef(11L, OTHER_USER, "电子产品")));
        givenAiMatchAgainst(null, match(11L, 95));
        // 那条失物的主人就是招领发布者本人
        Item ownLost = itemRef(11L, OTHER_USER, "电子产品");

        Item found = foundItem();
        found.setUserId(OTHER_USER);
        givenItems(mapOf(ownLost));

        runner.match(found);

        verify(notificationService, never()).createWithMatches(any(), any());
    }

    @Test
    @DisplayName("B. 降级时不通知（同 A）")
    void foundPublishDegradeSendsNothing() {
        when(candidateService.recall(anyString(), eq(Item.TYPE_LOST), anyInt()))
                .thenReturn(List.of(itemRef(11L, OTHER_USER, "电子产品")));
        givenAiMatchAgainst(AiQueryResponse.DEGRADE_LLM_ERROR, match(11L, 99));

        runner.match(foundItem());

        verify(notificationService, never()).createWithMatches(any(), any());
    }

    // ===================== 类别守卫的真值表 =====================

    @Test
    @DisplayName("类别守卫真值表：相同放行 / 泛分类放行 / 明确不同拦住 / 空值放行")
    void categoryCompatibilityTruthTable() {
        assertTrue(AutoMatchRunner.categoryCompatible("电子产品", "电子产品"), "完全相同");
        assertTrue(AutoMatchRunner.categoryCompatible("其他", "电子产品"), "失物是泛分类 → 交给 AI");
        assertTrue(AutoMatchRunner.categoryCompatible("证件", "其他"), "招领是泛分类 → 交给 AI");
        assertTrue(AutoMatchRunner.categoryCompatible(null, "电子产品"), "没填类别 → 不据此拦截");
        assertTrue(AutoMatchRunner.categoryCompatible("  ", "电子产品"), "空白类别 → 不据此拦截");

        assertFalse(AutoMatchRunner.categoryCompatible("证件", "电子产品"), "明确不一致 → 不通知");
        assertFalse(AutoMatchRunner.categoryCompatible("书籍", "衣物"), "明确不一致 → 不通知");
    }
}
