package com.collection.admin.config;

import com.collection.admin.job.ScheduledJob;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 生产调度入口配置，prefix = {@code collection.scheduler}（基础设施规范 §5）。
 *
 * <p>调度通道为「Cloud Scheduler 定时发调度专用 Pub/Sub 主题 → 本应用专用订阅消费 → 扫表发事件」。 GCP
 * 项目与订阅名走环境变量占位符，<b>真值不入仓</b>；凭证由 {@code GOOGLE_APPLICATION_CREDENTIALS} 经 ADC 加载， 与 {@code
 * collection.ingestion} 复用同一服务账号。
 *
 * <p><b>与接入消费解耦</b>：{@link #enabled} 独立于 {@code collection.ingestion.enabled}，调度消息与业务接入消息
 * 各走各的订阅与消费者。本地 / CI 默认 {@code false}，此时 {@code local}/{@code test} profile 的 {@code
 * TriggerScanner}（Spring {@code @Scheduled}）是唯一调度入口。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.scheduler")
public class SchedulerProperties {

    /** 是否启动调度订阅消费。本地 / CI false；Pilot / 生产 true。 */
    private boolean enabled = false;

    /** GCP 项目（映射环境变量 {@code GCP_PUBSUB_PROJECT}）。 */
    private String projectId;

    /** 调度专用订阅短名（映射 {@code GCP_SCHEDULER_SUBSCRIPTION}）；不得复用案件接入订阅。 */
    private String subscription;

    /**
     * 每分钟任务（{@code planStepDue} / {@code callbackTimeout}）的陈旧消息阈值（秒）。
     *
     * <p>Cloud Scheduler + Pub/Sub 是至少一次投递且会累积：停机 30 分钟后重启会一次性收到约 30 条每分钟任务消息。 超过本阈值（按消息 {@code
     * publishTime} 判定）的调度消息一律丢弃并计数，避免启动时扫描风暴。
     */
    private int staleThresholdSeconds = 60;

    /**
     * {@code dailyRoll} 的陈旧消息阈值（秒），默认 300 = 其自身触发周期。
     *
     * <p>不能沿用 60s：日切 tick 每 5 分钟才有一条，60s 窗口下重启延迟极易把<b>当轮</b> tick 也判为陈旧， 白白损失一整页 keyset 推进；而上一轮及更早的
     * tick 至少已有 300s，仍会被本阈值挡住。
     */
    private int dailyRollStaleThresholdSeconds = 300;

    /** 调度订阅的拉取并发度；调度消息量极小，默认 1，配合单飞保护避免同一任务并行扫描。 */
    private int maxConcurrency = 1;

    /** 该任务的有效陈旧阈值（秒）。 */
    public int staleThresholdSeconds(ScheduledJob job) {
        return job == ScheduledJob.DAILY_ROLL
                ? dailyRollStaleThresholdSeconds
                : staleThresholdSeconds;
    }
}
