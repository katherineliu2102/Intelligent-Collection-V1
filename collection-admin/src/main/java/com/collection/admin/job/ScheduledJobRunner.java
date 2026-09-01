package com.collection.admin.job;

import com.collection.engine.metrics.CollectionMetrics;
import com.collection.ingestion.job.DpdStageRollHandler;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 调度任务执行器：把 {@link ScheduledJob} 分派到既有扫描逻辑，并承担单飞保护、失败隔离与指标埋点。
 *
 * <p>与 Pub/Sub 解耦，便于不依赖 GCP 单测；消息侧的陈旧丢弃与 ack 在 {@link PubSubScheduleConsumer}。
 *
 * <p><b>单飞保护</b>：Cloud Scheduler + Pub/Sub 是至少一次投递，且 ack deadline 内未 ack 会重投；同一任务的重复或
 * 并发投递必须不能同时跑两次扫描 （日切并发会重复推进同一页游标；到期扫描并发会放大重复事件）。 这里用进程内按任务的 CAS 闸门，跳过的投递记 {@code reason=IN_FLIGHT}
 * 指标。
 *
 * <p><b>不新增去重层</b>：跨投递的业务幂等仍由既有机制兜底——步骤幂等锁 {@code collection:lock:plan:}、 事件消费去重 {@code
 * collection:processed:}、日切 Redis 游标与当日完成标记。
 *
 * <p><b>失败不重投</b>：扫描失败只记录错误并计入 {@code collection.schedule.failed} 告警，不向上抛。 每分钟任务下一条消息会再来，nack
 * 重投只会堆积过期的调度消息；日切续跑依赖 Redis 游标，同样不依赖重投。
 */
@Component
public class ScheduledJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRunner.class);

    static final String SKIP_IN_FLIGHT = "IN_FLIGHT";

    private final PlanStepTriggerPublisher triggerPublisher;
    private final DpdStageRollHandler dailyRollHandler;
    private final CollectionMetrics metrics;
    private final Map<ScheduledJob, AtomicBoolean> inFlight = new EnumMap<>(ScheduledJob.class);

    public ScheduledJobRunner(
            PlanStepTriggerPublisher triggerPublisher,
            DpdStageRollHandler dailyRollHandler,
            CollectionMetrics metrics) {
        this.triggerPublisher = triggerPublisher;
        this.dailyRollHandler = dailyRollHandler;
        this.metrics = metrics;
        for (ScheduledJob job : ScheduledJob.values()) {
            inFlight.put(job, new AtomicBoolean(false));
        }
    }

    /**
     * 执行一次扫描。本方法不抛异常：调度消息无论成败都会被 ack。
     *
     * <p>全程带 {@code job} 与 {@code scanId} 的 MDC。指标是聚合值，分不开「同一任务的两次投递」——而 T5-S5（重复投递）与
     * T5-S6（并发单飞）恰恰要判定两条 tick 各自的去向，靠 {@code scanId} 才能把一轮扫描的日志切开。
     */
    public void run(ScheduledJob job) {
        AtomicBoolean gate = inFlight.get(job);
        MDC.put("job", job.attribute());
        try {
            if (!gate.compareAndSet(false, true)) {
                metrics.scheduleSkipped(job.attribute(), SKIP_IN_FLIGHT);
                log.warn("[Scheduler] job={} 上一轮扫描仍在执行，本次投递跳过（单飞保护）", job.attribute());
                return;
            }
            MDC.put("scanId", UUID.randomUUID().toString().substring(0, 8));
            long startNanos = System.nanoTime();
            try {
                metrics.scheduleTriggered(job.attribute());
                int scanned = scan(job);
                metrics.scheduleScanned(job.attribute(), scanned);
                log.info(
                        "[Scheduler] job={} 扫描完成 scanned={} costMs={}",
                        job.attribute(),
                        scanned,
                        (System.nanoTime() - startNanos) / 1_000_000L);
            } catch (Exception e) {
                metrics.scheduleFailed(job.attribute());
                log.error(
                        "[Scheduler] job={} 扫描失败，不重投，请按告警排查: {}", job.attribute(), e.toString(), e);
            } finally {
                gate.set(false);
                MDC.remove("scanId");
            }
        } finally {
            MDC.remove("job");
        }
    }

    private int scan(ScheduledJob job) {
        switch (job) {
            case PLAN_STEP_DUE:
                return triggerPublisher.publishDueSteps();
            case CALLBACK_TIMEOUT:
                return triggerPublisher.publishTimeoutSteps();
            case DAILY_ROLL:
                return dailyRollHandler.dailyRoll();
            default:
                throw new IllegalStateException("未接线的调度任务: " + job);
        }
    }
}
