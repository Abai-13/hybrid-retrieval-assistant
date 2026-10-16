package com.lostfound.service.impl;

import com.lostfound.entity.Item;
import com.lostfound.service.CandidateService;
import com.lostfound.service.ItemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 候选召回 —— 尤其是「用户明确否掉的物品」是在**截断之前**被剔掉的。
 *
 * <p>这条差别很细，但用户能直接感觉到：先取 Top-K 再删，他拿到的就是 K-1 条，
 * 而第 K+1 名（本来该补上来的）根本没机会进来。他说的是「再找找」，
 * 不是「少给我一条」。
 */
@ExtendWith(MockitoExtension.class)
class CandidateServiceImplTest {

    @Mock
    private ItemService itemService;

    @InjectMocks
    private CandidateServiceImpl candidateService;

    private static final String QUERY = "黑色苹果手机";

    private static Item item(long id, String title) {
        Item item = new Item();
        item.setId(id);
        item.setTitle(title);
        item.setType(Item.TYPE_FOUND);
        item.setStatus(Item.STATUS_UNCLAIMED);
        return item;
    }

    /**
     * 四件物品对「黑色苹果手机」的 2-gram 覆盖率依次下降：
     * A 完全命中，B 命中三个片段，C、D 各命中一个。
     * 于是「Top-2」就是 A、B —— 拿 A 当被排除项，才看得出截断发生在哪一步。
     */
    private void givenPool() {
        when(itemService.listUnclaimedFound()).thenReturn(List.of(
                item(1L, "黑色苹果手机"),
                item(2L, "苹果手机"),
                item(3L, "手机"),
                item(4L, "黑色")));
    }

    @Test
    @DisplayName("不排除时：按相似度取 Top-K")
    void recallWithoutExclusion() {
        givenPool();

        List<Item> top = candidateService.recall(QUERY, 2);

        assertEquals(List.of(1L, 2L), top.stream().map(Item::getId).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("★ 排除项在截断【之前】生效：剔掉第 1 名后，第 3 名要能补上来")
    void exclusionHappensBeforeTruncation() {
        givenPool();

        CandidateService.RecallResult result =
                candidateService.recallDetailed(QUERY, Item.TYPE_FOUND, 2, Set.of(1L));

        List<Long> ids = result.candidates().stream().map(Item::getId).collect(Collectors.toList());
        assertFalse(ids.contains(1L), "用户否掉的物品不能再出现");
        assertEquals(2, ids.size(),
                "先取 Top-2 再删的话这里只会剩 1 条 —— 用户说的是「再找找」，不是「少给我一条」");
        assertTrue(ids.contains(3L), "第 3 名要能补上被排除留下的空位");
        assertEquals(1, result.excludedCount(), "诊断要如实报告剔掉了几条");
        assertEquals(4, result.poolSize());
    }

    @Test
    @DisplayName("排除全部候选时返回空，而不是报错")
    void excludingEverythingIsEmpty() {
        givenPool();

        CandidateService.RecallResult result =
                candidateService.recallDetailed(QUERY, Item.TYPE_FOUND, 5, Set.of(1L, 2L, 3L, 4L));

        assertTrue(result.candidates().isEmpty());
        assertEquals(4, result.excludedCount());
    }

    @Test
    @DisplayName("完全没有字面重合时返回空：这些候选进 prompt 只会浪费 token 并分散注意力")
    void noOverlapReturnsEmpty() {
        givenPool();

        assertTrue(candidateService.recall("完全不相关的描述文字", 5).isEmpty());
    }

    @Test
    @DisplayName("空描述返回空，不去扫全量候选")
    void blankQueryReturnsEmpty() {
        assertTrue(candidateService.recall("   ", 5).isEmpty());
        assertTrue(candidateService.recall("", 5).isEmpty());
        // 空查询不该触发任何候选池查询（那是全表扫描）
        org.mockito.Mockito.verify(itemService, org.mockito.Mockito.never())
                .listUnclaimedFound();
        org.mockito.Mockito.verify(itemService, org.mockito.Mockito.never())
                .listUnclaimedLost();
    }

    @Test
    @DisplayName("指向失物池时用 listUnclaimedLost（发布招领后反向匹配走这条）")
    void lostPoolUsesLostSource() {
        when(itemService.listUnclaimedLost()).thenReturn(List.of(item(9L, "苹果手机")));

        List<Item> top = candidateService.recall(QUERY, Item.TYPE_LOST, 5, Set.of());

        assertEquals(List.of(9L), top.stream().map(Item::getId).collect(Collectors.toList()));
        org.mockito.Mockito.verify(itemService, org.mockito.Mockito.never()).listUnclaimedFound();
    }

    @Test
    @DisplayName("排除集合为 null 也不能炸（调用方可能没传）")
    void nullExclusionsAreTolerated() {
        givenPool();

        assertEquals(2, candidateService.recall(QUERY, Item.TYPE_FOUND, 2, null).size());
    }
}
