package com.collection.engine.bus;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.event.EventHandler;
import com.collection.engine.config.EngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存事件总线（Phase 1 链路验证默认实现）。对应 {@link CollectionEventBus} 抽象。
 *
 * <p>语义对齐生产 Redis Stream：发布后由 Consumer 线程池异步消费、与发布线程隔离。
 * 替换为 RedisStreamEventBus 时业务代码零改动（架构设计文档 §1.8.2）。
 *
 * <p>激活条件：collection.eventbus=memory（缺省默认）。
 */
@Component
@ConditionalOnProperty(name = "collection.eventbus", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventBus implements CollectionEventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final EngineProperties props;
    private final Map<EventType, List<EventHandler>> handlers = new ConcurrentHashMap<>();
    private ThreadPoolExecutor consumerPool;

    @Autowired
    public InMemoryEventBus(EngineProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        EngineProperties.Consumer c = props.getConsumer();
        AtomicInteger seq = new AtomicInteger();
        this.consumerPool = new ThreadPoolExecutor(
                c.getThreadPoolSize(), c.getThreadPoolSize(),
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(c.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "engine-consumer-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // 背压：队列满则由发布线程自跑（对齐生产 CallerRunsPolicy，基础设施规范 §1）
                new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("[InMemoryEventBus] started, consumer pool size={}", c.getThreadPoolSize());
    }

    @Override
    public void subscribe(EventType eventType, EventHandler handler) {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.info("[InMemoryEventBus] subscribed {} -> {}", eventType, handler.getClass().getName());
    }

    @Override
    public void publish(CollectionEvent event) {
        List<EventHandler> hs = handlers.get(event.getEventType());
        if (hs == null || hs.isEmpty()) {
            log.warn("[InMemoryEventBus] no handler for {}", event.getEventType());
            return;
        }
        for (EventHandler h : hs) {
            consumerPool.execute(() -> dispatch(h, event));
        }
    }

    private void dispatch(EventHandler handler, CollectionEvent event) {
        MDC.put("eventType", String.valueOf(event.getEventType()));
        MDC.put("eventId", String.valueOf(event.getEventId()));
        if (event.getLong(CollectionEvent.CASE_ID) != null) {
            MDC.put("caseId", String.valueOf(event.getLong(CollectionEvent.CASE_ID)));
        }
        if (event.getLong(CollectionEvent.PLAN_ID) != null) {
            MDC.put("planId", String.valueOf(event.getLong(CollectionEvent.PLAN_ID)));
        }
        try {
            handler.handle(event);
        } catch (Exception e) {
            // 生产（Redis Stream）下：不 ACK → 重投递 / DLQ。内存版仅记录告警（基础设施规范 §2）。
            log.error("[InMemoryEventBus] handler failed for {} eventId={}",
                    event.getEventType(), event.getEventId(), e);
        } finally {
            MDC.clear();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (consumerPool != null) {
            consumerPool.shutdown();
        }
    }
}
