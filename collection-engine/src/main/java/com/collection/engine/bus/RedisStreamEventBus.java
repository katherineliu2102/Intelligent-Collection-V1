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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
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
    private volatile long pendingSize;
    private volatile long streamLength;
    private volatile long dlqSize;
    private volatile Throwable consumeFailure;
    private volatile long consecutiveConsumeFailures;
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
        ensureConsumerGroup();
    }

    /**
     * 幂等建组。除 {@code @PostConstruct} 外，消费循环发现 group 消失时也会调用。
     *
     * <p>createGroup 底层是 XGROUP CREATE ... MKSTREAM，流不存在会一并建出来。 曾用一条 {@code {"bootstrap":"1"}}
     * 假记录先把流撑起来，但那条记录没有 event 字段， 组建好之后每次重启写入的那条都会被投递并判成解析失败进 DLQ——DLQ 深度因此随重启次数 单调增长，真实故障被淹没在噪声里。
     */
    private void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), consumerGroup);
        } catch (Exception ignored) {
            // group 已存在是正常路径；连接异常交给首次 publish/consume 暴露。
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
        List<MapRecord<String, Object, Object>> records;
        try {
            records =
                    redisTemplate
                            .opsForStream()
                            .read(
                                    Consumer.from(consumerGroup, consumerName),
                                    StreamReadOptions.empty()
                                            .count(20)
                                            .block(Duration.ofSeconds(1)),
                                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        } catch (Exception e) {
            recordConsumeFailure(e);
            return;
        }
        consumeFailure = null;
        consecutiveConsumeFailures = 0;
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            submit(record);
        }
    }

    /**
     * 消费失败的收敛处理：group 消失时就地重建，其余只记录状态。
     *
     * <p>group 会因 Redis 未持久化重启或人为删除而消失，此后每次 XREADGROUP 都抛 NOGROUP。 建组原先只在 {@code @PostConstruct}
     * 做过一次，于是总线永久停摆直到有人重启进程—— 2026-08-25 实际发生：Redis 重启后应用空转十余小时，其间一条事件都没消费。 这里补上重建，满足「恢复后无需重启即自愈」。
     *
     * <p>异常不再上抛：@Scheduled 的默认行为是把栈打进日志，按 1 秒一轮会把磁盘刷满， 真实原因反而被自己的重复日志淹没。改为按退避打印，并把状态交给健康检查暴露。
     */
    private void recordConsumeFailure(Exception e) {
        consumeFailure = e;
        long failures = ++consecutiveConsumeFailures;
        if (isMissingGroup(e)) {
            log.error(
                    "[RedisStreamEventBus] consumer group '{}' on '{}' is gone (failure #{}), recreating",
                    consumerGroup,
                    streamKey,
                    failures,
                    failures == 1 ? e : null);
            ensureConsumerGroup();
            return;
        }
        // 1、2、4、8… 轮各打一次，避免每秒一条栈把真实原因淹掉。
        if (Long.bitCount(failures) == 1) {
            log.error("[RedisStreamEventBus] consume failed (failure #{})", failures, e);
        }
    }

    /** NOGROUP 只能从报文里认：Lettuce 把它归到通用的命令执行异常，没有独立异常类型。 */
    private static boolean isMissingGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("NOGROUP")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 供健康检查判定：非 null 表示最近一次拉取失败，总线当前不在消费。 */
    public Throwable getConsumeFailure() {
        return consumeFailure;
    }

    public long getConsecutiveConsumeFailures() {
        return consecutiveConsumeFailures;
    }

    /** 接管长时间未 ACK 的 PEL 消息。低于最大投递次数的消息 claim 后立即按相同逻辑处理；超过阈值 进入专用 DLQ stream，并确认原消息，避免永久积压。 */
    @Scheduled(fixedDelayString = "${collection.redis.pel-scan-interval-ms:30000}")
    public void reclaimPending() {
        PendingMessages pending;
        try {
            pending =
                    redisTemplate
                            .opsForStream()
                            .pending(streamKey, consumerGroup, Range.unbounded(), pelBatchSize);
        } catch (Exception e) {
            // 与 consume 同源：group 消失时这里也会抛，交给同一段收敛逻辑重建并退避打印。
            recordConsumeFailure(e);
            return;
        }
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

    /**
     * 采样三个 Redis 侧读数。
     *
     * <p>PEL 深度必须走 XPENDING 的<b>汇总</b>形式。明细形式受 {@code count} 限制（默认 50）， 用它的返回条数喂 gauge 会让指标恒被夹在
     * 50：积压涨到几千也只显示 50，阈值设在 50 以上的告警 永远不触发，Dashboard 上是一条直线。指标存在、能抓取、数值是错的，属最难发现的观测缺陷。
     */
    private void sampleStreamGauges() {
        try {
            PendingMessagesSummary summary =
                    redisTemplate.opsForStream().pending(streamKey, consumerGroup);
            pendingSize = summary == null ? 0L : summary.getTotalPendingMessages();
            Long length = redisTemplate.opsForStream().size(streamKey);
            streamLength = length == null ? 0L : length;
            Long dlq = redisTemplate.opsForStream().size(streamKey + ":dlq");
            dlqSize = dlq == null ? 0L : dlq;
        } catch (Exception e) {
            log.warn("[RedisStreamEventBus] stream gauge sampling failed: {}", e.getMessage());
        }
    }

    /**
     * T3o-O4 的可查询证据：Stream / PEL / DLQ 深度、消费去重键与工作池水位。
     *
     * <p>与三个 gauge 的区别是这里<b>实时打 Redis</b>。gauge 只在 PEL 扫描周期（默认 30s）采样一次，做故障注入时 读到的往往是注入前的旧值；判定
     * T5-R3 「消息滞留 PEL」这类用例必须是当下读数。
     *
     * <p>PEL 明细只取最老的若干条：判「有没有卡住、卡了多久」看最老一条即可，全量拉取在积压时会打爆响应。 深度一律取 XPENDING 汇总值，不用明细条数（见 {@link
     * #sampleStreamGauges()} 的说明）。
     */
    public Map<String, Object> evidenceSnapshot(int pelSampleSize, int dedupSampleSize) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("streamKey", streamKey);
        snapshot.put("consumerGroup", consumerGroup);
        snapshot.put("consumerName", consumerName);
        try {
            Long length = redisTemplate.opsForStream().size(streamKey);
            snapshot.put("streamLength", length == null ? 0L : length);
            Long dlq = redisTemplate.opsForStream().size(streamKey + ":dlq");
            snapshot.put("dlqSize", dlq == null ? 0L : dlq);
            PendingMessagesSummary summary =
                    redisTemplate.opsForStream().pending(streamKey, consumerGroup);
            snapshot.put("pendingTotal", summary == null ? 0L : summary.getTotalPendingMessages());
            snapshot.put(
                    "pendingPerConsumer",
                    summary == null
                            ? Collections.emptyMap()
                            : summary.getPendingMessagesPerConsumer());
            snapshot.put("oldestPending", oldestPending(pelSampleSize));
            snapshot.put("dedupKeys", dedupKeySample(dedupSampleSize));
        } catch (Exception e) {
            // 证据端点不能因 Redis 抖动整体 500，否则排障时连"读不到"都区分不出是没数据还是没连上。
            snapshot.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        snapshot.put("consumerPool", consumerPoolSnapshot());
        snapshot.put(
                "lastConsumeFailure", consumeFailure == null ? null : consumeFailure.toString());
        snapshot.put("consecutiveConsumeFailures", consecutiveConsumeFailures);
        return snapshot;
    }

    private List<Map<String, Object>> oldestPending(int sampleSize) {
        PendingMessages messages =
                redisTemplate
                        .opsForStream()
                        .pending(
                                streamKey,
                                consumerGroup,
                                Range.unbounded(),
                                Math.max(1, sampleSize));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PendingMessage message : messages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", message.getIdAsString());
            row.put("consumer", message.getConsumerName());
            row.put("idleMs", message.getElapsedTimeSinceLastDelivery().toMillis());
            row.put("deliveryCount", message.getTotalDeliveryCount());
            row.put("willDeadLetter", message.getTotalDeliveryCount() >= maxDeliveryCount);
            rows.add(row);
        }
        return rows;
    }

    /** 用 SCAN 取有界样本。这套 Redis 与其他服务同实例，KEYS 会阻塞单线程命令执行影响邻居服务。 */
    private Map<String, Object> dedupKeySample(int sampleSize) {
        int limit = Math.max(1, sampleSize);
        List<String> keys = new ArrayList<>();
        boolean truncated = false;
        ScanOptions options =
                ScanOptions.scanOptions().match(PROCESSED_PREFIX + "*").count(64).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                if (keys.size() >= limit) {
                    truncated = true;
                    break;
                }
                keys.add(cursor.next());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prefix", PROCESSED_PREFIX);
        result.put("sample", keys);
        result.put("truncated", truncated);
        return result;
    }

    private Map<String, Object> consumerPoolSnapshot() {
        Map<String, Object> pool = new LinkedHashMap<>();
        if (consumerPool == null) {
            return pool;
        }
        pool.put("poolSize", consumerPool.getPoolSize());
        pool.put("activeCount", consumerPool.getActiveCount());
        pool.put("queueSize", consumerPool.getQueue().size());
        pool.put("queueRemainingCapacity", consumerPool.getQueue().remainingCapacity());
        pool.put("completedTaskCount", consumerPool.getCompletedTaskCount());
        return pool;
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
            // 与解析失败区分开：这里根本没尝试解析，是有人往流里塞了不带 event 字段的记录。
            log.error(
                    "[RedisStreamEventBus] record without '{}' field, id={} fields={}",
                    FIELD_EVENT,
                    record.getId(),
                    record.getValue().keySet());
            persistDlq(record, "MISSING_EVENT_FIELD", 1);
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
        values.put(FIELD_EVENT, dlqPayload(record));
        values.put("reason", reason);
        values.put("deliveries", String.valueOf(deliveryCount));
        return values;
    }

    /**
     * DLQ 里留下的载荷，保证是合法 JSON。
     *
     * <p>缺 {@code event} 字段时退回整条记录，而不是写字面 {@code "null"}—— 进 DLQ
     * 的记录已经没法自我解释，再把唯一的线索丢掉，运维只能看到一条无从下手的失败。
     *
     * <p>非法 JSON 必须包一层：{@code t_event_dlq.payload} 是 {@code JSON NOT NULL} 列，把解析失败的原文直接塞进去 会让
     * INSERT 报错——而这正是「反序列化失败」这条路径的常态输入。落库失败就不会 ACK， 毒消息回到 PEL 反复重投，最终又走进同一段代码再次失败，「进 DLQ
     * 隔离」反而变成死循环。
     */
    private String dlqPayload(MapRecord<String, Object, Object> record) {
        Object raw = record.getValue().get(FIELD_EVENT);
        if (raw == null) {
            return JsonUtil.toJson(record.getValue());
        }
        String text = String.valueOf(raw);
        try {
            JsonUtil.fromJson(text, Object.class);
            return text;
        } catch (Exception notJson) {
            return JsonUtil.toJson(Collections.singletonMap("raw", text));
        }
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
        String raw = dlqPayload(record);
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
