package com.lostfound.service.impl;

import com.lostfound.entity.Item;
import com.lostfound.service.CandidateService;
import com.lostfound.service.HybridRecallService;
import com.lostfound.service.ItemService;
import com.lostfound.service.ItemVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 混合召回的五类代表性检查（计划 D 组）—— 只测行为，不测内部方法：
 * <ol>
 *   <li>两路都有结果 → 按 id 去重、两路独有的候选都进得来、RRF 融合排序；</li>
 *   <li>条件与排除 → 合并后的完整描述进向量查询，排除项与已认领物品进不了候选；</li>
 *   <li>故障回退 → 向量腿不可用（null）时结果与改造前的 2-gram 完全一致；</li>
 *   <li>预算保护 → 剩余预算不足时连向量调用都不发；</li>
 *   <li>关闭开关 → 同上，且行为可预期。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class HybridRecallServiceImplTest {

    @Mock
    private CandidateService candidateService;

    @Mock
    private ItemVectorService itemVectorService;

    @Mock
    private ItemService itemService;

    @InjectMocks
    private HybridRecallServiceImpl service;

    private static final String QUERY = "黑色的苹果手机 图书馆";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "vectorEnabled", true);
        ReflectionTestUtils.setField(service, "overfetch", 3);
    }

    private static long deadline() {
        return System.currentTimeMillis() + 60_000;
    }

    private static Item item(long id, String status) {
        Item item = new Item();
        item.setId(id);
        item.setTitle("物品" + id);
        item.setType(Item.TYPE_FOUND);
        item.setStatus(status);
        return item;
    }

    private static List<Long> idsOf(List<Item> items) {
        return items.stream().map(Item::getId).toList();
    }

    // ===================== 1. 两路都有结果 =====================

    @Test
    @DisplayName("★ 两路结果：按 id 去重，两路独有候选都参与融合，RRF 按名次排序")
    void bothLegsMerge_dedupAndRank() {
        Item a = item(1L, Item.STATUS_UNCLAIMED);
        Item b = item(2L, Item.STATUS_UNCLAIMED);
        Item c = item(3L, Item.STATUS_UNCLAIMED);
        when(candidateService.recallDetailed(anyString(), anyString(), anyInt(), anySet()))
                .thenReturn(new CandidateService.RecallResult(List.of(a, b), 10, 0));
        // 向量腿顺序 [3, 1]：3 是只有向量找得到的，1 是两路都有的
        when(itemVectorService.searchIds(anyString(), anyInt())).thenReturn(List.of(3L, 1L));
        when(itemService.mapByIds(anyCollection()))
                .thenReturn(Map.of(3L, c, 1L, a));

        CandidateService.RecallResult result =
                service.recall(QUERY, 50, Set.of(), deadline());

        List<Long> merged = idsOf(result.candidates());
        // 去重：3 条，不是 2+2=4 条
        assertEquals(3, merged.size(), "重复出现的物品只能有一份：" + merged);
        // item1: 1/61(词列第1) + 1/62(向列第2) —— 两路都靠前，理应第一
        // item3: 1/61(向列第1) —— 只有向量找得到，但名次最高，压过词列第 2
        // item2: 1/62(词列第2)
        assertEquals(List.of(1L, 3L, 2L), merged, "RRF 融合排序错了");
        assertTrue(merged.contains(3L), "只有向量路找得到的候选必须进得来");
        assertTrue(merged.contains(2L), "只有 2-gram 找得到的候选必须进得来");
        // poolSize / excludedCount 沿用 2-gram 口径，诊断数字跨改造前后可比
        assertEquals(10, result.poolSize());
        assertEquals(0, result.excludedCount());
    }

    @Test
    @DisplayName("★ 融合后仍按 topK 截断（限制送进 LLM 的候选数），且向量腿多取 overfetch 倍")
    void fusedResultIsTruncatedToTopK() {
        when(candidateService.recallDetailed(anyString(), anyString(), anyInt(), anySet()))
                .thenReturn(new CandidateService.RecallResult(
                        List.of(item(1L, Item.STATUS_UNCLAIMED), item(2L, Item.STATUS_UNCLAIMED)), 4, 0));
        when(itemVectorService.searchIds(anyString(), anyInt())).thenReturn(List.of(3L, 4L));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(
                3L, item(3L, Item.STATUS_UNCLAIMED), 4L, item(4L, Item.STATUS_UNCLAIMED)));

        CandidateService.RecallResult result = service.recall(QUERY, 2, Set.of(), deadline());

        assertEquals(2, result.candidates().size(), "融合之后仍然是 topK 上限");
        // 多取：2 × 3 = 6 —— 过滤（已认领/已删除/排除）之后还剩得下 K 条
        verify(itemVectorService).searchIds(eq(QUERY), eq(6));
    }

    // ===================== 2. 条件与排除 =====================

    @Test
    @DisplayName("★ 排除项与已认领的物品进不了候选 —— 即使向量索引里还留着它")
    void excludedAndStaleVectorHitsAreDropped() {
        when(candidateService.recallDetailed(anyString(), anyString(), anyInt(), anySet()))
                .thenReturn(new CandidateService.RecallResult(
                        List.of(item(1L, Item.STATUS_UNCLAIMED)), 5, 1));
        // 向量索引滞后：9 的行已经没了，7 已经认领走，8 是用户明确否掉的
        when(itemVectorService.searchIds(anyString(), anyInt())).thenReturn(List.of(9L, 7L, 8L));
        when(itemService.mapByIds(anyCollection())).thenReturn(Map.of(
                7L, item(7L, Item.STATUS_CLAIMED),
                8L, item(8L, Item.STATUS_UNCLAIMED)));

        CandidateService.RecallResult result = service.recall(QUERY, 50, Set.of(8L), deadline());

        assertEquals(List.of(1L), idsOf(result.candidates()),
                "过期/已认领/被排除的向量命中必须在进 LLM 之前被剔掉");
        // 排除项要在**两路**都躲开：向量腿的 8 号被拒后也不能混进来
        verify(itemService).mapByIds(anyCollection());
    }

    @Test
    @DisplayName("★ 向量查询用的是会话合并后的完整条件（不是最后一句话）")
    void vectorLegUsesMergedQuery() {
        when(candidateService.recallDetailed(anyString(), anyString(), anyInt(), anySet()))
                .thenReturn(new CandidateService.RecallResult(List.of(), 0, 0));
        when(itemVectorService.searchIds(anyString(), anyInt())).thenReturn(List.of());

        service.recall(QUERY, 50, Set.of(), deadline());

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(itemVectorService).searchIds(query.capture(), anyInt());
        assertEquals(QUERY, query.getValue(), "召回必须拿合并后的完整描述");
    }

    // ===================== 3. 故障回退 =====================

    @Test
    @DisplayName("★ 向量腿不可用（返回 null）→ 结果就是原 2-gram 候选，一条不丢")
    void vectorUnavailable_fallsBackToLexical() {
        List<Item> lexical = List.of(item(1L, Item.STATUS_UNCLAIMED), item(2L, Item.STATUS_UNCLAIMED));
        when(candidateService.recallDetailed(anyString(), anyString(), anyInt(), anySet()))
                .thenReturn(new CandidateService.RecallResult(lexical, 7, 2));
        when(itemVectorService.searchIds(anyString(), anyInt())).thenReturn(null);

        CandidateService.RecallResult result = service.recall(QUERY, 50, Set.of(), deadline());

        assertEquals(lexical, result.candidates(), "回退后必须和改造前的 2-gram 结果一致");
        assertEquals(7, result.poolSize(), "诊断数字不能因为回退而变形");
        assertEquals(2, result.excludedCount());
        verifyNoInteractions(itemService);   // 都回退了，就别再为了复核去查库
    }

    @Test
    @DisplayName("★ 向量查得到但一条都不像（空列表）→ 同样用 2-gram 结果（故障与「没命中」要分得清）")
    void vectorEmpty_fallsBackToLexical() {
        List<Item> lexical = List.of(item(1L, Item.STATUS_UNCLAIMED));
        when(candidateService.recallDetailed(anyString(), anyString(), anyInt(), anySet()))
                .thenReturn(new CandidateService.RecallResult(lexical, 3, 0));
        when(itemVectorService.searchIds(anyString(), anyInt())).thenReturn(List.of());

        assertEquals(lexical, service.recall(QUERY, 50, Set.of(), deadline()).candidates());
    }

    @Test
    @DisplayName("★ 剩余预算不足 → 向量调用一次都不发（增强路径不能拖垮主路径）")
    void insufficientBudget_skipsVectorLeg() {
        List<Item> lexical = List.of(item(1L, Item.STATUS_UNCLAIMED));
        when(candidateService.recallDetailed(anyString(), anyString(), anyInt(), anySet()))
                .thenReturn(new CandidateService.RecallResult(lexical, 3, 0));

        CandidateService.RecallResult result =
                service.recall(QUERY, 50, Set.of(), System.currentTimeMillis() + 500);

        assertEquals(lexical, result.candidates());
        verifyNoInteractions(itemVectorService);
    }

    @Test
    @DisplayName("★ 开关关掉 → 完全回到改造前的 2-gram 行为")
    void disabled_skipsVectorLeg() {
        ReflectionTestUtils.setField(service, "vectorEnabled", false);
        List<Item> lexical = List.of(item(1L, Item.STATUS_UNCLAIMED));
        when(candidateService.recallDetailed(anyString(), anyString(), anyInt(), anySet()))
                .thenReturn(new CandidateService.RecallResult(lexical, 3, 0));

        CandidateService.RecallResult result = service.recall(QUERY, 50, Set.of(), deadline());

        assertEquals(lexical, result.candidates());
        verifyNoInteractions(itemVectorService);
        verify(itemService, never()).mapByIds(any());
    }

    @Test
    @DisplayName("排除项会传给 2-gram，并在向量腿查库前剔除")
    void exclusionAppliedBeforeTruncation() {
        when(candidateService.recallDetailed(anyString(), anyString(), anyInt(), anySet()))
                .thenReturn(new CandidateService.RecallResult(
                        List.of(item(1L, Item.STATUS_UNCLAIMED)), 9, 1));
        // 2 号在排除集合里 → 向量腿的这条命中连查库都不会发生（截断之前就滤掉了）
        when(itemVectorService.searchIds(anyString(), anyInt())).thenReturn(List.of(2L));

        CandidateService.RecallResult result = service.recall(QUERY, 2, Set.of(2L), deadline());

        assertEquals(List.of(1L), idsOf(result.candidates()),
                "被排除的那条不该出现在结果里，也不该占掉一个名额");
        verify(itemService, never()).mapByIds(any());
        // 排除集合原样传给 2-gram 那一路（它自己负责在打分前剔除）
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Set<Long>> excluded = (ArgumentCaptor) ArgumentCaptor.forClass((Class) Set.class);
        verify(candidateService).recallDetailed(anyString(), eq(Item.TYPE_FOUND), anyInt(), excluded.capture());
        assertEquals(Set.of(2L), excluded.getValue());
    }
}
