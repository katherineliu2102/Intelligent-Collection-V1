package com.collection.engine.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.collection.common.model.EventDlq;
import com.collection.common.repository.EventDlqRepository;
import com.collection.engine.metrics.CollectionMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 启动建组与 DLQ 可诊断性。
 *
 * <p>2026-08-24 生产实测：pilot 的 DLQ 里躺着 15 条 {@code event=null} 的失败记录，条数正好等于重启次数。 根因是建组前先写一条 {@code
 * {"bootstrap":"1"}} 假记录把流撑起来——组建好之后每次重启写的那条 都会被投递，因缺 {@code event} 字段判成失败进 DLQ，DLQ
 * 深度随重启单调增长，真实故障被淹没。
 */
class RedisStreamEventBusStartupTest {

    private static final String STREAM = "collection:pilot:events";
    private static final String GROUP = "collection-engine-pilot";

    private StreamOperations<String, Object, Object> streamOps;
    private EventDlqRepository dlqRepository;
    private RedisStreamEventBus bus;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        dlqRepository = mock(EventDlqRepository.class);
        org.mockito.Mockito.when(redis.opsForStream()).thenReturn(streamOps);

        bus = new RedisStreamEventBus(redis, CollectionMetrics.local());
        ReflectionTestUtils.setField(bus, "streamKey", STREAM);
        ReflectionTestUtils.setField(bus, "consumerGroup", GROUP);
        ReflectionTestUtils.setField(bus, "consumerName", "engine-test");
        ReflectionTestUtils.setField(bus, "eventDlqRepository", dlqRepository);
        ReflectionTestUtils.setField(bus, "consumerThreadPoolSize", 1);
        ReflectionTestUtils.setField(bus, "consumerQueueCapacity", 1);
    }

    @Test
    @DisplayName("建组不再往流里写任何记录（XGROUP CREATE 自带 MKSTREAM）")
    void initDoesNotWriteBootstrapRecord() {
        bus.initConsumerGroup();

        verify(streamOps).createGroup(STREAM, ReadOffset.latest(), GROUP);
        verify(streamOps, never()).add(any(Record.class));
    }

    @Test
    @DisplayName("建组重复调用不抛错，重启幂等")
    void initIsIdempotentAcrossRestarts() {
        org.mockito.Mockito.when(streamOps.createGroup(STREAM, ReadOffset.latest(), GROUP))
                .thenThrow(
                        new IllegalStateException("BUSYGROUP Consumer Group name already exists"));

        bus.initConsumerGroup();
        bus.initConsumerGroup();

        verify(streamOps, never()).add(any(Record.class));
    }

    @Test
    @DisplayName("缺 event 字段：原因与解析失败区分，且整条记录留进 DLQ 而非字面 null")
    void recordWithoutEventFieldKeepsDiagnosablePayload() {
        Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("bootstrap", "1");
        MapRecord<String, Object, Object> record =
                StreamRecords.newRecord()
                        .ofMap(fields)
                        .withStreamKey(STREAM)
                        .withId(RecordId.of("1787534029272-0"));

        ReflectionTestUtils.invokeMethod(bus, "process", record);

        ArgumentCaptor<EventDlq> saved = ArgumentCaptor.forClass(EventDlq.class);
        verify(dlqRepository).upsert(saved.capture());
        assertThat(saved.getValue().getFailureReason()).isEqualTo("MISSING_EVENT_FIELD");
        assertThat(saved.getValue().getPayload()).contains("bootstrap").doesNotMatch("^null$");
        assertThat(saved.getValue().getEventId()).isEqualTo("1787534029272-0");
        verify(streamOps).acknowledge(eq(STREAM), eq(GROUP), any(RecordId.class));
    }

    /**
     * {@code t_event_dlq.payload} 是 JSON NOT NULL 列。解析失败的原文按定义就不是合法 JSON， 原样落库会让 INSERT 报错→不
     * ACK→毒消息回到 PEL 反复重投→再次走到这里，隔离变死循环。
     */
    @Test
    @DisplayName("event 字段不是合法 JSON：归类不变，载荷包一层以保证可落 JSON 列")
    void malformedEventJsonIsWrappedIntoValidJson() throws Exception {
        MapRecord<String, Object, Object> record =
                StreamRecords.newRecord()
                        .ofMap(Collections.<Object, Object>singletonMap("event", "{not-json"))
                        .withStreamKey(STREAM)
                        .withId(RecordId.of("1-1"));

        ReflectionTestUtils.invokeMethod(bus, "process", record);

        ArgumentCaptor<EventDlq> saved = ArgumentCaptor.forClass(EventDlq.class);
        verify(dlqRepository).upsert(saved.capture());
        assertThat(saved.getValue().getFailureReason()).isEqualTo("DESERIALIZATION_FAILURE");
        String payload = saved.getValue().getPayload();
        assertThat(new ObjectMapper().readTree(payload).get("raw").asText()).isEqualTo("{not-json");
    }

    /**
     * T5-R9 要求「恢复后消费与认领自愈，无需重启」。2026-08-25 实测未满足： Redis 未持久化重启后消费组消失，建组只在 {@code @PostConstruct}
     * 做过一次， 此后每秒抛一次 NOGROUP，总线空转十余小时。
     */
    @Test
    @DisplayName("消费组消失：就地重建而非永久停摆")
    void consumeRecreatesGroupWhenItDisappears() {
        org.mockito.Mockito.when(
                        streamOps.read(
                                any(
                                        org.springframework.data.redis.connection.stream.Consumer
                                                .class),
                                any(StreamReadOptions.class),
                                any(StreamOffset.class)))
                .thenThrow(
                        new IllegalStateException(
                                "NOGROUP No such key 'collection:pilot:events' or consumer group"));

        bus.consume();

        verify(streamOps).createGroup(STREAM, ReadOffset.latest(), GROUP);
        assertThat(bus.getConsumeFailure()).isNotNull();
        assertThat(bus.getConsecutiveConsumeFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("拉取恢复正常后清掉失败状态，健康检查随之转绿")
    void successfulConsumeClearsFailureState() {
        org.mockito.Mockito.when(
                        streamOps.read(
                                any(
                                        org.springframework.data.redis.connection.stream.Consumer
                                                .class),
                                any(StreamReadOptions.class),
                                any(StreamOffset.class)))
                .thenThrow(new IllegalStateException("NOGROUP"))
                .thenReturn(java.util.Collections.emptyList());

        bus.consume();
        assertThat(bus.getConsumeFailure()).isNotNull();

        bus.consume();

        assertThat(bus.getConsumeFailure()).isNull();
        assertThat(bus.getConsecutiveConsumeFailures()).isZero();
    }

    /** 非 NOGROUP 的故障不该被误当成建组问题反复重建，否则真实原因被掩盖。 */
    @Test
    @DisplayName("普通连接故障：记录状态但不重建组")
    void ordinaryFailureDoesNotRecreateGroup() {
        org.mockito.Mockito.when(
                        streamOps.read(
                                any(
                                        org.springframework.data.redis.connection.stream.Consumer
                                                .class),
                                any(StreamReadOptions.class),
                                any(StreamOffset.class)))
                .thenThrow(new IllegalStateException("Connection refused"));

        bus.consume();

        verify(streamOps, never()).createGroup(any(), any(ReadOffset.class), any());
        assertThat(bus.getConsumeFailure()).isNotNull();
    }

    /** PEL 深度取汇总值：明细形式受 count 限制，用它喂 gauge 会让指标恒被夹在 pel-batch-size。 */
    @Test
    @DisplayName("PEL 深度 gauge 取 XPENDING 汇总总数，不受明细条数上限影响")
    void pendingGaugeUsesSummaryTotalNotDetailCount() {
        PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        org.mockito.Mockito.when(summary.getTotalPendingMessages()).thenReturn(4200L);
        org.mockito.Mockito.when(streamOps.pending(STREAM, GROUP)).thenReturn(summary);
        org.mockito.Mockito.when(streamOps.size(any())).thenReturn(0L);

        ReflectionTestUtils.invokeMethod(bus, "sampleStreamGauges");

        assertThat((Long) ReflectionTestUtils.getField(bus, "pendingSize")).isEqualTo(4200L);
    }

    @Test
    @DisplayName("合法 JSON 的 event 原样保留，不额外包装")
    void wellFormedEventJsonIsStoredVerbatim() {
        String body = "{\"eventId\":\"e-1\",\"eventType\":\"STEP_COMPLETED\"}";
        MapRecord<String, Object, Object> record =
                StreamRecords.newRecord()
                        .ofMap(Collections.<Object, Object>singletonMap("event", body))
                        .withStreamKey(STREAM)
                        .withId(RecordId.of("1-2"));

        String payload = ReflectionTestUtils.invokeMethod(bus, "dlqPayload", record);

        assertThat(payload).isEqualTo(body);
    }
}
