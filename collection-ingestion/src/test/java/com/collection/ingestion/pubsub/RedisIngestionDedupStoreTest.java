package com.collection.ingestion.pubsub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** Redis 版接入去重的键名、TTL 与乱序水位写入方式。 */
class RedisIngestionDedupStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RedisIngestionDedupStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        store = new RedisIngestionDedupStore(redis);
    }

    @Test
    void messageMarkerUsesSevenDayTtl() {
        store.markMessageProcessed("m1");

        verify(valueOps).set("collection:ingestion:dedup:msg:m1", "1", Duration.ofDays(7));
    }

    @Test
    void ingestedMarkerUsesNinetyDayTtlAndIsDeletableOnSettlement() {
        store.markIngested(88L);
        store.clearIngested(88L);

        verify(valueOps).set("collection:ingestion:ingested:88", "1", Duration.ofDays(90));
        verify(redis).delete("collection:ingestion:ingested:88");
    }

    @Test
    void stalenessComparesStoredWatermark() {
        when(valueOps.get("collection:ingestion:last-seen:88")).thenReturn("5000");

        assertThat(store.isStale(88L, 4000L)).isTrue();
        assertThat(store.isStale(88L, 6000L)).isFalse();
        assertThat(store.isStale(88L, null)).isFalse();
    }

    @Test
    void unseenLoanIsNotStale() {
        when(valueOps.get("collection:ingestion:last-seen:99")).thenReturn(null);

        assertThat(store.isStale(99L, 1L)).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void watermarkIsWrittenOnlyWhenGreater() {
        store.recordSeen(88L, 7000L);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue()).containsExactly("collection:ingestion:last-seen:88");
        assertThat(args.getAllValues()).containsExactly("7000", "7776000");
    }

    @Test
    void nullInputsAreNoop() {
        store.markMessageProcessed(null);
        store.markIngested(null);
        store.clearIngested(null);
        store.recordSeen(null, 1L);

        assertThat(store.isMessageProcessed(null)).isFalse();
        assertThat(store.isIngested(null)).isFalse();
        verify(redis, org.mockito.Mockito.never()).delete(eq("collection:ingestion:ingested:null"));
    }
}
