package com.collection.engine.bus;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.event.EventHandler;
import com.collection.common.model.EventDlq;
import com.collection.common.repository.EventDlqRepository;
import com.collection.common.util.JsonUtil;
import com.collection.engine.metrics.CollectionMetrics;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

/** Redis Stream 事件总线：消费循环只拉取和提交，有界工作池执行业务并在成功后 XACK。 */
@Component
@ConditionalOnProperty(name = "collection.eventbus", havingValue = "redis")
public class RedisStreamEventBus implements CollectionEventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamEventBus.class);
    private static final String FIELD_EVENT = "event";
    private static final String PROCESSED_PREFIX = "collection:processed:";

    private final StringRedisTemplate redisTemplate;
    private final CollectionMetrics metrics;
    @Resource private EventDlqRepository eventDlqRepository;
    private final Map<EventType, List<EventHandler>> handlers = new ConcurrentHashMap<>();
    private volatile long lastBackpressureWarnNanos;
    private volatile int pendingSize;
    private volatile long streamLength;
    private volatile long dlqSize;
    private ThreadPoolExecutor consumerPool;

    @Value("${collection.redis.stream:collection:events}")
    private String streamKey;

    @Value("${collection.redis.processed-ttl-hours:24}")
    private long processedTtlHours;

    @Value("${collection.redis.consumer-group:collection-engine}")
    private String consumerGroup;

    @Value("${collection.redis.consumer-name:${HOSTNAME:engine-1}}")
    private String consumerName;

    @Value("${collection.redis.pel-min-idle-seconds:120}")
    private long pelMinIdleSeconds;

    @Value("${collection.redis.max-delivery-count:5}")
    private long maxDeliveryCount;

    @Value("${collection.redis.pel-batch-size:50}")
    private long pelBatchSize;

    @Value("${engine.consumer.thread-pool-size:8}")
    private int consumerThreadPoolSize;

    @Value("${engine.consumer.queue-capacity:256}")
    private int consumerQueueCapacity;

    public RedisStreamEventBus(StringRedisTemplate redisTemplate, CollectionMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
    }

    @PostConstruct
    public void initConsumerGroup() {
        initConsumerPool();
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

    private void initConsumerPool() {
        int poolSize = Math.max(1, consumerThreadPoolSize);
        int queueCapacity = Math.max(1, consumerQueueCapacity);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory =
                runnable -> new Thread(runnable, "engine-consumer-" + sequence.incrementAndGet());
        consumerPool =
                new ThreadPoolExecutor(
                        poolSize,
                        poolSize,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(queueCapacity),
                        threadFactory,
                        (runnable, executor) -> {
                            logBackpressure(executor);
                            runnable.run();
                        });
        consumerPool.prestartAllCoreThreads();
        metrics.bindExecutor("collection.event.consumer", consumerPool);
        // 三个 Redis 侧读数在 PEL 扫描周期内采样，避免每次抓取都打 Redis。
        metrics.gauge("collection.event.pending", () -> pendingSize);
        metrics.gauge("collection.event.stream.length", () -> streamLength);
        metrics.gauge("collection.event.dlq.size", () -> dlqSize);
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
        metrics.eventPublished(event.getEventType().name());
    }

    @Override
    public void subscribe(EventType eventType, EventHandler handler) {
        handlers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Pilot 的 Redis consumer；调度入口仅负责数据库 due/timeout/daily-roll 扫描并发事件到本总线。 */
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
            submit(record);
        }
    }

    /** 接管长时间未 ACK 的 PEL 消息。低于最大投递次数的消息 claim 后立即按相同逻辑处理；超过阈值 进入专用 DLQ stream，并确认原消息，避免永久积压。 */
    @Scheduled(fixedDelayString = "${collection.redis.pel-scan-interval-ms:30000}")
    public void reclaimPending() {
        PendingMessages pending =
                redisTemplate
                        .opsForStream()
                        .pending(streamKey, consumerGroup, Range.unbounded(), pelBatchSize);
        pendingSize = pending.size();
        sampleStreamGauges();
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
                submit(record);
            }
        }
    }

    private void sampleStreamGauges() {
        try {
            Long length = redisTemplate.opsForStream().size(streamKey);
            streamLength = length == null ? 0L : length;
            Long dlq = redisTemplate.opsForStream().size(streamKey + ":dlq");
            dlqSize = dlq == null ? 0L : dlq;
        } catch (Exception e) {
            log.warn("[RedisStreamEventBus] stream gauge sampling failed: {}", e.getMessage());
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
            persistDlq(record, "MAX_DELIVERY_EXCEEDED", message.getTotalDeliveryCount());
            acknowledge(record);
            log.error(
                    "[RedisStreamEventBus] DLQ eventId={} deliveries={}",
                    record.getValue().get(FIELD_EVENT),
                    message.getTotalDeliveryCount());
        }
    }

    private void submit(MapRecord<String, Object, Object> record) {
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        consumerPool.execute(
                () -> {
                    if (parentMdc != null) {
                        MDC.setContextMap(parentMdc);
                    }
                    try {
                        process(record);
                    } finally {
                        MDC.clear();
                    }
                });
    }

    private void process(MapRecord<String, Object, Object> record) {
        long started = System.nanoTime();
        Object raw = record.getValue().get(FIELD_EVENT);
        if (raw == null) {
            persistDlq(record, "DESERIALIZATION_FAILURE", 1);
            acknowledge(record);
            return;
        }
        CollectionEvent event;
        try {
            event = JsonUtil.fromJson(String.valueOf(raw), CollectionEvent.class);
        } catch (Exception e) {
            persistDlq(record, "DESERIALIZATION_FAILURE", 1);
            acknowledge(record);
            return;
        }
        putMdc(event);
        if (alreadyProcessed(event)) {
            log.info(
                    "[RedisStreamEventBus] duplicate delivery skipped, eventId={}",
                    event.getEventId());
            acknowledge(record);
            metrics.eventDeduped(event.getEventType().name());
            return;
        }
        List<EventHandler> eventHandlers = handlers.get(event.getEventType());
        if (eventHandlers == null || eventHandlers.isEmpty()) {
            log.warn("[RedisStreamEventBus] no handler for {}", event.getEventType());
            persistDlq(record, "NO_HANDLER", 1);
            acknowledge(record);
            return;
        }
        try {
            for (EventHandler handler : eventHandlers) {
                handler.handle(event);
            }
            markProcessed(event);
            acknowledge(record);
            metrics.eventConsumed(event.getEventType().name());
            metrics.eventDuration(event.getEventType().name(), System.nanoTime() - started);
        } catch (Exception e) {
            log.error(
                    "[RedisStreamEventBus] handler failed eventId={}, leaving pending",
                    event.getEventId(),
                    e);
        }
    }

    /** 消费去重：仅在 handler 全部成功后落标记，失败的消息保留在 PEL 仍可重投。 Redis 不可用时按"未处理"放行，由步骤幂等锁与渠道幂等兜底，避免去重故障阻断消费。 */
    private boolean alreadyProcessed(CollectionEvent event) {
        if (event.getEventId() == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(PROCESSED_PREFIX + event.getEventId()));
        } catch (Exception e) {
            log.warn("[RedisStreamEventBus] processed-key lookup failed: {}", e.getMessage());
            return false;
        }
    }

    private void markProcessed(CollectionEvent event) {
        if (event.getEventId() == null) {
            return;
        }
        try {
            redisTemplate
                    .opsForValue()
                    .set(
                            PROCESSED_PREFIX + event.getEventId(),
                            "1",
                            Duration.ofHours(Math.max(1, processedTtlHours)));
        } catch (Exception e) {
            log.warn("[RedisStreamEventBus] processed-key write failed: {}", e.getMessage());
        }
    }

    private Map<String, String> dlqValues(
            MapRecord<String, Object, Object> record, String reason, long deliveryCount) {
        Map<String, String> values = new HashMap<>();
        values.put(FIELD_EVENT, String.valueOf(record.getValue().get(FIELD_EVENT)));
        values.put("reason", reason);
        values.put("deliveries", String.valueOf(deliveryCount));
        return values;
    }

    private void putMdc(CollectionEvent event) {
        MDC.put("eventId", String.valueOf(event.getEventId()));
        putMdcIfPresent("caseId", event.getLong(CollectionEvent.CASE_ID));
        putMdcIfPresent("planId", event.getLong(CollectionEvent.PLAN_ID));
        putMdcIfPresent("stepId", event.getLong(CollectionEvent.STEP_ID));
    }

    private void putMdcIfPresent(String key, Long value) {
        if (value != null) {
            MDC.put(key, String.valueOf(value));
        }
    }

    /** Redis 隔离与 MySQL 审计必须同时成功；调用方仅在此成功后 ACK PEL。 */
    private void persistDlq(
            MapRecord<String, Object, Object> record, String reason, long deliveryCount) {
        String raw = String.valueOf(record.getValue().get(FIELD_EVENT));
        CollectionEvent event;
        try {
            event = JsonUtil.fromJson(raw, CollectionEvent.class);
        } catch (Exception ignored) {
            event = null;
        }
        redisTemplate
                .opsForStream()
                .add(
                        StreamRecords.newRecord()
                                .ofMap(dlqValues(record, reason, deliveryCount))
                                .withStreamKey(streamKey + ":dlq"));
        EventDlq row = new EventDlq();
        row.setEventId(
                event == null || event.getEventId() == null
                        ? record.getId().getValue()
                        : event.getEventId());
        row.setEventType(
                event == null || event.getEventType() == null
                        ? "UNKNOWN"
                        : event.getEventType().name());
        row.setPayload(raw);
        row.setFailureReason(reason);
        row.setDeliveryCount((int) deliveryCount);
        eventDlqRepository.upsert(row);
        metrics.eventDlq(reason);
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, record.getId());
    }

    private void logBackpressure(ThreadPoolExecutor executor) {
        long now = System.nanoTime();
        if (now - lastBackpressureWarnNanos < TimeUnit.SECONDS.toNanos(5)) {
            return;
        }
        lastBackpressureWarnNanos = now;
        log.warn(
                "[RedisStreamEventBus] backpressure queueDepth={} activeThreads={}",
                executor.getQueue().size(),
                executor.getActiveCount());
    }

    @PreDestroy
    public void shutdown() {
        if (consumerPool != null) {
            consumerPool.shutdown();
        }
    }
}
