package com.collection.ingestion.pubsub;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link IngestionDedupStore} §3.3 三道检查单测。 */
class IngestionDedupStoreTest {

    private IngestionDedupStore store;

    @BeforeEach
    void setUp() {
        store = new IngestionDedupStore();
    }

    @Test
    void messageDedup_marksAndDetects() {
        assertFalse(store.isMessageProcessed("m1"));
        store.markMessageProcessed("m1");
        assertTrue(store.isMessageProcessed("m1"));
        assertFalse(store.isMessageProcessed(null));
    }

    @Test
    void staleness_olderPublishTimeIsStale() {
        store.recordSeen(100L, 5000L);
        assertTrue(store.isStale(100L, 4000L), "更旧 publish_time 视为乱序旧消息");
        assertFalse(store.isStale(100L, 6000L), "更新的不算旧");
        assertFalse(store.isStale(100L, null));
        assertFalse(store.isStale(999L, 1L), "未见过的 loan 不算旧");
    }

    @Test
    void ingested_markClearCycle() {
        assertFalse(store.isIngested(200L));
        store.markIngested(200L);
        assertTrue(store.isIngested(200L));
        store.clearIngested(200L);
        assertFalse(store.isIngested(200L), "全额结清后允许下一周期再次入催");
    }
}
