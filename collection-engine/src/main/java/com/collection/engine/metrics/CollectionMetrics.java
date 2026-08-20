package com.collection.engine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** T5 指标的统一命名与注册入口，供 Prometheus 抓取、Alertmanager 告警。 */
@Component
public class CollectionMetrics {

    private final MeterRegistry registry;

    public CollectionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 无 Spring 容器时（纯逻辑单测、{@code SpiInvoker.direct()}）的本地注册表，指标不外发。 */
    public static CollectionMetrics local() {
        return new CollectionMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    public void eventPublished(String type) {
        Counter.builder("collection.event.published")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public void eventConsumed(String type) {
        Counter.builder("collection.event.consumed")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public void eventDeduped(String type) {
        Counter.builder("collection.event.deduped")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public void eventDlq(String reason) {
        Counter.builder("collection.event.dlq")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** 发件箱兜底重发数。这是「提交后即时发布失败」的唯一应用侧证据：正常链路恒为 0， 持续非 0 说明事件总线在抖动，事件正靠发件箱救回。 */
    public void outboxRepublished(String type) {
        Counter.builder("collection.outbox.republished")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /** 重发次数耗尽、已转人工的事件数。任何非 0 都必须告警——对应一个停摆的计划。 */
    public void outboxFailed(String type) {
        Counter.builder("collection.outbox.failed")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /** 非终态但不会再被任何扫描拾取的计划数。恒应为 0。 */
    public void planStuck(int count) {
        Counter.builder("collection.plan.stuck").register(registry).increment(count);
    }

    public void eventDuration(String type, long nanos) {
        registry.timer("collection.event.consume.duration", "type", type)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 跳过原因区分合规拦截与 Guard fail-close（reason=GUARD_ERROR 是告警信号）。 */
    public void stepSkipped(String reason) {
        Counter.builder("collection.step.skipped")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void touch(String channel) {
        Counter.builder("collection.touch.total")
                .tag("channel", channel)
                .register(registry)
                .increment();
    }

    public void stepDuration(String channel, long nanos) {
        registry.timer("collection.step.duration", "channel", channel)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void spiTimeout(String spi) {
        Counter.builder("collection.spi.timeout").tag("spi", spi).register(registry).increment();
    }

    /**
     * 调度指标（基础设施规范 §5.4）。迁出 XXL 后没有调度控制台的执行记录页面，Cloud Scheduler 只能证明消息已发出、
     * 不能证明扫描跑过，因此以下五个指标是「扫描是否真的在跑」的 唯一应用侧证据。
     */
    public void scheduleTriggered(String job) {
        Counter.builder("collection.schedule.triggered")
                .tag("job", job)
                .register(registry)
                .increment();
    }

    /** 单次触发扫描到并发布的条数；持续为 0 说明调度到了但没数据，持续等于 scan_limit 说明积压。 */
    public void scheduleScanned(String job, int rows) {
        Counter.builder("collection.schedule.scan.rows")
                .tag("job", job)
                .register(registry)
                .increment(rows);
    }

    /** 按 publishTime 丢弃的陈旧调度消息数；重启后应出现一次尖峰后归零，持续增长说明消费跟不上。 */
    public void scheduleStaleDiscarded(String job) {
        Counter.builder("collection.schedule.stale.discarded")
                .tag("job", job)
                .register(registry)
                .increment();
    }

    /** 扫描失败数。调度消息 ack 后不重投，本指标是失败的唯一告警来源。 */
    public void scheduleFailed(String job) {
        Counter.builder("collection.schedule.failed")
                .tag("job", job)
                .register(registry)
                .increment();
    }

    /** reason=IN_FLIGHT 为单飞跳过（重复/并发投递），reason=UNKNOWN_JOB 为未知 job 属性。 */
    public void scheduleSkipped(String job, String reason) {
        Counter.builder("collection.schedule.skipped")
                .tag("job", job)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** 线程池利用率、队列深度等由 Micrometer 标准绑定提供，命名固定为 {@code executor.*}。 */
    public void bindExecutor(String name, java.util.concurrent.ExecutorService executor) {
        io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics.monitor(
                registry, executor, name);
    }

    public void gauge(String name, Supplier<Number> value) {
        Gauge.builder(name, value, supplier -> supplier.get().doubleValue()).register(registry);
    }
}
