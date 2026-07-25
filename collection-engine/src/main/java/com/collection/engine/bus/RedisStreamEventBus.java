package com.collection.engine.bus;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.event.EventHandler;
import com.collection.common.util.JsonUtil;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pilot 事件总线：XADD 后由 consumer group 拉取，成功处理才 XACK。处理异常不确认，让 Redis pending 列表保留给后续重放/人工 DLQ 处置。 */
@Component
@ConditionalOnProperty(name = "collection.eventbus", havingValue = "redis")
public class RedisStreamEventBus implements CollectionEventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamEventBus.class);
    private static final String FIELD_EVENT = "event";

    private final StringRedisTemplate redisTemplate;
    private final Map<EventType, List<EventHandler>> handlers = new ConcurrentHashMap<>();

    @Value("${collection.redis.stream:collection:pilot:events}")
    private String streamKey;

    @Value("${collection.redis.consumer-group:collection-engine}")
    private String consumerGroup;

    @Value("${collection.redis.consumer-name:${HOSTNAME:engine-1}}")
    private String consumerName;

    @Value("${collection.redis.pel-min-idle-seconds:60}")
    private long pelMinIdleSeconds;

    @Value("${collection.redis.max-delivery-count:5}")
    private long maxDeliveryCount;

    @Value("${collection.redis.pel-batch-size:50}")
    private long pelBatchSize;

    public RedisStreamEventBus(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void initConsumerGroup() {
        try {
            redisTemplate
                    .opsForStream()
                    .add(
                            StreamRecords.newRecord()
                                    .ofMap(Collections.singletonMap("bootstrap", "1"))
                                    .withStreamKey(streamKey));
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), consumerGroup);
        } catch (Exception ignored) {
            // group 已存在是正常启动路径；连接异常交给首次 publish/consume 暴露。
        }
    }

    @Override
    public void publish(CollectionEvent event) {
        redisTemplate
                .opsForStream()
                .add(
                        StreamRecords.newRecord()
                                .ofMap(
                                        Collections.singletonMap(
                                                FIELD_EVENT, JsonUtil.toJson(event)))
                                .withStreamKey(streamKey));
    }

    @Override
    public void subscribe(EventType eventType, EventHandler handler) {
        handlers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Pilot 的 Redis consumer；XXL 仅负责数据库 due/timeout/daily-roll 扫描。 */
    @Scheduled(fixedDelayString = "${collection.redis.poll-interval-ms:1000}")
    public void consume() {
        List<MapRecord<String, Object, Object>> records =
                redisTemplate
                        .opsForStream()
                        .read(
                                Consumer.from(consumerGroup, consumerName),
                                StreamReadOptions.empty().count(20).block(Duration.ofSeconds(1)),
                                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            Object raw = record.getValue().get(FIELD_EVENT);
            if (raw == null) {
                acknowledge(record);
                continue;
            }
            CollectionEvent event = JsonUtil.fromJson(String.valueOf(raw), CollectionEvent.class);
            List<EventHandler> eventHandlers = handlers.get(event.getEventType());
            if (eventHandlers == null || eventHandlers.isEmpty()) {
                log.warn("[RedisStreamEventBus] no handler for {}", event.getEventType());
                acknowledge(record);
                continue;
            }
            try {
                for (EventHandler handler : eventHandlers) {
                    handler.handle(event);
                }
                acknowledge(record);
            } catch (Exception e) {
                log.error(
                        "[RedisStreamEventBus] handler failed eventId={}, leaving pending",
                        event.getEventId(),
                        e);
            }
        }
    }

    /** 接管长时间未 ACK 的 PEL 消息。低于最大投递次数的消息 claim 后立即按相同逻辑处理；超过阈值 进入专用 DLQ stream，并确认原消息，避免永久积压。 */
    @Scheduled(fixedDelayString = "${collection.redis.pel-scan-interval-ms:30000}")
    public void reclaimPending() {
        PendingMessages pending =
                redisTemplate
                        .opsForStream()
                        .pending(streamKey, consumerGroup, Range.unbounded(), pelBatchSize);
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery()
                            .compareTo(Duration.ofSeconds(pelMinIdleSeconds))
                    < 0) {
                continue;
            }
            if (message.getTotalDeliveryCount() >= maxDeliveryCount) {
                deadLetter(message);
                continue;
            }
            List<MapRecord<String, Object, Object>> claimed =
                    redisTemplate
                            .opsForStream()
                            .claim(
                                    streamKey,
                                    consumerGroup,
                                    consumerName,
                                    Duration.ofSeconds(pelMinIdleSeconds),
                                    message.getId());
            for (MapRecord<String, Object, Object> record : claimed) {
                process(record);
            }
        }
    }

    private void deadLetter(PendingMessage message) {
        List<MapRecord<String, Object, Object>> claimed =
                redisTemplate
                        .opsForStream()
                        .claim(
                                streamKey,
                                consumerGroup,
                                consumerName,
                                Duration.ZERO,
                                message.getId());
        for (MapRecord<String, Object, Object> record : claimed) {
            redisTemplate
                    .opsForStream()
                    .add(
                            StreamRecords.newRecord()
                                    .ofMap(dlqValues(record, message.getTotalDeliveryCount()))
                                    .withStreamKey(streamKey + ":dlq"));
            acknowledge(record);
            log.error(
                    "[RedisStreamEventBus] DLQ eventId={} deliveries={}",
                    record.getValue().get(FIELD_EVENT),
                    message.getTotalDeliveryCount());
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        Object raw = record.getValue().get(FIELD_EVENT);
        if (raw == null) {
            acknowledge(record);
            return;
        }
        CollectionEvent event = JsonUtil.fromJson(String.valueOf(raw), CollectionEvent.class);
        List<EventHandler> eventHandlers = handlers.get(event.getEventType());
        if (eventHandlers == null || eventHandlers.isEmpty()) {
            log.warn("[RedisStreamEventBus] no handler for {}", event.getEventType());
            acknowledge(record);
            return;
        }
        try {
            for (EventHandler handler : eventHandlers) {
                handler.handle(event);
            }
            acknowledge(record);
        } catch (Exception e) {
            log.error(
                    "[RedisStreamEventBus] handler failed eventId={}, leaving pending",
                    event.getEventId(),
                    e);
        }
    }

    private Map<String, String> dlqValues(
            MapRecord<String, Object, Object> record, long deliveryCount) {
        Map<String, String> values = new HashMap<>();
        values.put(FIELD_EVENT, String.valueOf(record.getValue().get(FIELD_EVENT)));
        values.put("reason", "MAX_DELIVERY_EXCEEDED");
        values.put("deliveries", String.valueOf(deliveryCount));
        return values;
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, record.getId());
    }
}
