package com.collection.engine.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.util.JsonUtil;
import com.collection.engine.metrics.CollectionMetrics;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/** At-least-once 投递下的事件消费去重：重投与 DLQ 重放都可能把同一 eventId 再送一次， 只有 handler 成功过的事件才允许被跳过。 */
class RedisStreamEventBusDedupTest {

    private static final String STREAM = "collection:events";

    private StringRedisTemplate redis;
    private StreamOperations<String, Object, Object> streamOps;
    private ValueOperations<String, String> valueOps;
    private RedisStreamEventBus bus;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(redis.opsForValue()).thenReturn(valueOps);

        bus = new RedisStreamEventBus(redis, CollectionMetrics.local());
        ReflectionTestUtils.setField(bus, "streamKey", STREAM);
        ReflectionTestUtils.setField(bus, "consumerGroup", "collection-engine-test");
        ReflectionTestUtils.setField(bus, "processedTtlHours", 24L);
    }

    @Test
    void skipsEventAlreadyProcessedAndStillAcknowledges() {
        CollectionEvent event = CollectionEvent.of(EventType.STEP_COMPLETED);
        when(redis.hasKey("collection:processed:" + event.getEventId())).thenReturn(true);
        AtomicInteger handled = new AtomicInteger();
        bus.subscribe(EventType.STEP_COMPLETED, e -> handled.incrementAndGet());

        process(event);

        assertThat(handled.get()).isZero();
        verify(streamOps)
                .acknowledge(eq(STREAM), eq("collection-engine-test"), any(RecordId.class));
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void marksProcessedOnlyAfterHandlerSucceeds() {
        CollectionEvent event = CollectionEvent.of(EventType.STEP_COMPLETED);
        when(redis.hasKey(any(String.class))).thenReturn(false);
        bus.subscribe(EventType.STEP_COMPLETED, e -> {});

        process(event);

        verify(valueOps)
                .set("collection:processed:" + event.getEventId(), "1", Duration.ofHours(24));
    }

    @Test
    void leavesEventUnmarkedWhenHandlerFails() {
        CollectionEvent event = CollectionEvent.of(EventType.STEP_COMPLETED);
        when(redis.hasKey(any(String.class))).thenReturn(false);
        bus.subscribe(
                EventType.STEP_COMPLETED,
                e -> {
                    throw new IllegalStateException("boom");
                });

        process(event);

        verify(valueOps, never()).set(any(), any(), any(Duration.class));
        verify(streamOps, never()).acknowledge(any(), any(), any(RecordId.class));
    }

    /** 去重键读失败不能阻断消费，只退化为不去重，由步骤幂等锁兜底。 */
    @Test
    void processesEventWhenDedupLookupFails() {
        CollectionEvent event = CollectionEvent.of(EventType.STEP_COMPLETED);
        when(redis.hasKey(any(String.class))).thenThrow(new IllegalStateException("redis down"));
        AtomicInteger handled = new AtomicInteger();
        bus.subscribe(EventType.STEP_COMPLETED, e -> handled.incrementAndGet());

        process(event);

        assertThat(handled.get()).isEqualTo(1);
    }

    /**
     * MDC 写入不得成为一条失败路径。
     *
     * <p>putMdc 位于「反序列化失败」与「handler 失败」两段 try 之间，此前用 {@code getLong} 解析 planId， 一个非数字值就会让异常逃出
     * process 打死线程池的工作线程，而该记录既未 ACK 也未进 DLQ。 2026-08-25 Pilot 实测毒丸 t5r-poison-001 连杀 3 个线程。
     */
    @Test
    void nonNumericIdInPayloadReachesHandlerInsteadOfEscaping() {
        CollectionEvent event =
                CollectionEvent.of(EventType.PLAN_STEP_DUE)
                        .with(CollectionEvent.PLAN_ID, "not-a-number");
        when(redis.hasKey(any(String.class))).thenReturn(false);
        AtomicInteger handled = new AtomicInteger();
        bus.subscribe(EventType.PLAN_STEP_DUE, e -> handled.incrementAndGet());

        process(event);

        assertThat(handled.get()).as("畸形 id 应照常交给 handler，由其抛出有业务含义的错误").isEqualTo(1);
        assertThat(MDC.get("planId")).as("畸形值原样进 MDC，日志里要能直接看到").isEqualTo("not-a-number");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private void process(CollectionEvent event) {
        MapRecord<String, Object, Object> record =
                StreamRecords.newRecord()
                        .ofMap(
                                Collections.<Object, Object>singletonMap(
                                        "event", JsonUtil.toJson(event)))
                        .withStreamKey(STREAM)
                        .withId(RecordId.of("1-1"));
        ReflectionTestUtils.invokeMethod(bus, "process", record);
    }
}
