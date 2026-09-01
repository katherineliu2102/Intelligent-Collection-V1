package com.collection.engine.outbox;

import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.OutboxEvent;
import com.collection.common.repository.EventOutboxRepository;
import com.collection.common.util.JsonUtil;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.metrics.CollectionMetrics;
import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发件箱兜底发布器。对应核心引擎规格 §7.2。
 *
 * <p>这不是业务 Cron，而是引擎内部的可靠性守护进程：不经 Cloud Scheduler，调度链路本身故障时也能推进， 与 {@code
 * RedisStreamEventBus.consume()} 同一类角色，因此不受 {@code SchedulerEntrypointValidator} 的单入口约束。
 *
 * <p>只重发宽限期内没被销账的记录。多实例通过短租约原子认领同一行，避免并发重复投递；认领者崩溃后租约到期可重试。正常链路里事件在提交后毫秒级即时发布并销账，本轮询扫不到任何行。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    @Autowired(required = false)
    private EventOutboxRepository repository;

    @Resource private CollectionEventBus eventBus;
    @Resource private EngineProperties props;
    @Resource private CollectionMetrics metrics;

    @PostConstruct
    public void init() {
        if (repository == null || !props.getOutbox().isEnabled()) {
            log.info("[outbox] publisher disabled (no repository or engine.outbox.enabled=false)");
            return;
        }
        metrics.gauge("collection.outbox.pending", this::countPending);
    }

    @Scheduled(fixedDelayString = "${engine.outbox.poll-interval-ms:2000}")
    public void republishDue() {
        if (repository == null || !props.getOutbox().isEnabled()) {
            return;
        }
        EngineProperties.Outbox cfg = props.getOutbox();
        List<OutboxEvent> due;
        try {
            LocalDateTime now = LocalDateTime.now();
            due =
                    repository.claimDueForRepublish(
                            now, now.plusSeconds(cfg.getLeaseSeconds()), cfg.getBatchSize());
        } catch (Exception e) {
            // 下一轮自愈；此处上抛只会污染调度线程。
            log.error("[outbox] scan failed", e);
            return;
        }
        for (OutboxEvent row : due) {
            republish(row, cfg);
        }
    }

    private void republish(OutboxEvent row, EngineProperties.Outbox cfg) {
        final CollectionEvent event;
        try {
            event = JsonUtil.fromJson(row.getPayload(), CollectionEvent.class);
        } catch (Exception e) {
            markUndeserializable(row, e);
            return;
        }
        if (event == null || event.getEventType() == null) {
            markUndeserializable(row, null);
            return;
        }
        try {
            eventBus.publish(event);
            repository.markPublished(row.getEventId());
            metrics.outboxRepublished(row.getEventType());
            log.warn(
                    "[outbox] republished {} eventId={} planId={} retry={}",
                    row.getEventType(),
                    row.getEventId(),
                    row.getPlanId(),
                    row.getRetryCount());
        } catch (Exception e) {
            handleRepublishFailure(row, cfg, e);
        }
    }

    private void markUndeserializable(OutboxEvent row, Exception e) {
        // 反序列化不可能靠重试变好，直接转人工，避免占着扫描批次。
        String reason =
                e == null
                        ? "payload not deserializable"
                        : truncate("payload not deserializable: " + e);
        try {
            repository.markFailed(row.getEventId(), reason);
            metrics.outboxFailed(String.valueOf(row.getEventType()));
            log.error(
                    "[outbox] undeserializable payload, event={} id={}",
                    row.getEventType(),
                    row.getId(),
                    e);
        } catch (Exception markFailure) {
            log.error(
                    "[outbox] unable to mark undeserializable payload failed, eventId={}",
                    row.getEventId(),
                    markFailure);
        }
    }

    private void handleRepublishFailure(OutboxEvent row, EngineProperties.Outbox cfg, Exception e) {
        if (row.getRetryCount() + 1 >= cfg.getMaxRetryCount()) {
            repository.markFailed(row.getEventId(), truncate(e.toString()));
            metrics.outboxFailed(row.getEventType());
            log.error(
                    "[outbox] give up after {} retries, plan {} will stall until manual redrive:"
                            + " event={} eventId={}",
                    row.getRetryCount() + 1,
                    row.getPlanId(),
                    row.getEventType(),
                    row.getEventId(),
                    e);
            return;
        }
        repository.scheduleRetry(row.getEventId(), nextRetryAt(row, cfg), truncate(e.toString()));
        log.warn(
                "[outbox] republish failed, retry {} scheduled: event={} eventId={} err={}",
                row.getRetryCount() + 1,
                row.getEventType(),
                row.getEventId(),
                e.toString());
    }

    private LocalDateTime nextRetryAt(OutboxEvent row, EngineProperties.Outbox cfg) {
        long delay = cfg.getGraceSeconds();
        for (int i = 0; i < row.getRetryCount() && delay < cfg.getMaxBackoffSeconds(); i++) {
            delay *= cfg.getBackoffFactor();
        }
        return LocalDateTime.now().plusSeconds(Math.min(delay, cfg.getMaxBackoffSeconds()));
    }

    private long countPending() {
        try {
            return repository.countPending(LocalDateTime.now());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 500 ? message : message.substring(0, 500);
    }
}
