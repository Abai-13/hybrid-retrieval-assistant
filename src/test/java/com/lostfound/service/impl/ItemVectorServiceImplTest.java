package com.lostfound.service.impl;

import com.lostfound.common.ItemTexts;
import com.lostfound.entity.Item;
import com.lostfound.service.EmbeddingService;
import com.lostfound.service.ItemService;
import com.lostfound.service.ItemVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemVectorServiceImplTest {

    private static final long ITEM_ID = 42L;
    private static final String MODEL = "test:mock-embedding";

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final QdrantClient qdrantClient = mock(QdrantClient.class);
    private final ItemService itemService = mock(ItemService.class);
    private final Item item = foundItem();

    private ItemVectorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ItemVectorServiceImpl(embeddingService, qdrantClient, itemService, Runnable::run);
        ReflectionTestUtils.setField(service, "vectorEnabled", true);

        when(embeddingService.dimension()).thenReturn(3);
        when(embeddingService.providerName()).thenReturn(MODEL);
        when(embeddingService.embedBatch(anyList())).thenReturn(List.of(new float[]{0.1f, 0.2f, 0.3f}));
        when(qdrantClient.ensureCollection(3, MODEL)).thenReturn(true);
        when(itemService.listFoundAfter(0L, 100)).thenReturn(List.of(item));
        when(qdrantClient.scrollAllIds()).thenReturn(List.of(ITEM_ID, QdrantClient.SENTINEL_ID));
    }

    @Test
    @DisplayName("补齐可重复运行：首次写入向量，第二次按模型和文本指纹跳过")
    void backfillIsIdempotentForUnchangedItems() {
        Map<Long, Map<String, Object>> existingPayload = Map.of(ITEM_ID, Map.of(
                "model", MODEL,
                "fp", ItemVectorServiceImpl.fingerprint(ItemTexts.docText(item))));
        when(qdrantClient.retrievePayloads(anyCollection())).thenReturn(Map.of(), existingPayload);
        when(qdrantClient.upsertPoints(anyList())).thenReturn(true);

        ItemVectorService.BackfillReport first = service.backfill(100, false);
        ItemVectorService.BackfillReport second = service.backfill(100, false);

        assertTrue(first.completed());
        assertEquals(1, first.indexed());
        assertEquals(0, first.skipped());
        assertEquals(0, first.failed());
        assertTrue(second.completed());
        assertEquals(0, second.indexed());
        assertEquals(1, second.skipped());
        assertEquals(0, second.failed());
        verify(embeddingService, times(1)).embedBatch(List.of(ItemTexts.docText(item)));
        verify(qdrantClient, times(1)).upsertPoints(anyList());
        verify(qdrantClient, never()).deletePoints(anyCollection());
    }

    private static Item foundItem() {
        Item item = new Item();
        item.setId(ITEM_ID);
        item.setType(Item.TYPE_FOUND);
        item.setTitle("校园卡");
        item.setCategory("证件");
        item.setLocation("图书馆");
        item.setDescription("蓝色挂绳");
        return item;
    }
}
