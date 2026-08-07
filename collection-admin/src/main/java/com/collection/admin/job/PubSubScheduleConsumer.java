package com.collection.admin.job;

import com.collection.admin.config.SchedulerProperties;
import com.collection.engine.metrics.CollectionMetrics;
import com.google.api.gax.batching.FlowControlSettings;
import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 生产调度入口：消费调度专用订阅的 Cloud Scheduler 消息并触发扫描（基础设施规范 §5）。
 *
 * <p>链路为 Cloud Scheduler（定时）→ 调度专用 Pub/Sub 主题 → 本应用专用订阅 → 扫 MySQL → 发事件 → 引擎 Consumer 执行业务。
 * <b>本类不调用任何渠道</b>：Trigger-to-Event 语义不变，渠道 I/O 只在引擎 Consumer 工作线程发生。
 *
 * <p><b>路由</b>：三个任务共用<b>一个</b>调度订阅，按消息属性 {@code job} 路由（{@code planStepDue} / {@code
 * callbackTimeout} / {@code dailyRoll}，见 {@link ScheduledJob}）。未知取值记录并 ack，不重投。
 *
 * <p><b>与案件接入解耦</b>：不复用接入订阅 {@code collection-cases-ai-v1-sub}；门控开关也独立于 {@code
 * collection.ingestion.enabled}。沿用 {@link Subscriber} 流式拉取 + {@link SmartLifecycle}，与 {@code
 * PubSubCaseConsumer} 同一套生命周期，凭证经 {@code GOOGLE_APPLICATION_CREDENTIALS}（ADC）加载， 不启用
 * spring-cloud-gcp 自动装配。
 *
 * <p><b>陈旧消息防抖</b>：Cloud Scheduler + Pub/Sub 是至少一次投递且会累积——应用停机 30 分钟后重启会一次性收到 约 30 条每分钟任务消息。按消息
 * {@code publishTime} 丢弃超过阈值者并计数，只放行属于当前 tick 的那一条， 避免启动时扫描风暴。阈值上界为任务自身周期，见 {@link
 * SchedulerProperties#staleThresholdSeconds(ScheduledJob)}。
 *
 * <p><b>ACK 语义</b>：调度消息<b>一律 ack</b>，且在触发扫描前 ack。扫描失败不靠 nack 重投（每分钟任务下一条消息
 * 会再来，重投只会堆积过期调度消息），只记录错误并由 {@code collection.schedule.failed} 告警；{@code dailyRoll} 的续跑依赖既有 Redis
 * 游标与当日完成标记。先 ack 也避免长时间日切页扫描 撑过 ack deadline 导致重投，重复投递由 {@link ScheduledJobRunner} 的单飞保护吸收。
 */
@Component
@ConditionalOnProperty(prefix = "collection.scheduler", name = "enabled", havingValue = "true")
public class PubSubScheduleConsumer implements SmartLifecycle, MessageReceiver {

    private static final Logger log = LoggerFactory.getLogger(PubSubScheduleConsumer.class);

    /** Cloud Scheduler 消息属性名，取值见 {@link ScheduledJob#attribute()}。 */
    static final String JOB_ATTRIBUTE = "job";

    static final String SKIP_UNKNOWN_JOB = "UNKNOWN_JOB";
    private static final String UNKNOWN_JOB_TAG = "unknown";

    @Resource private SchedulerProperties props;
    @Resource private ScheduledJobRunner runner;
    @Resource private CollectionMetrics metrics;

    private volatile Subscriber subscriber;
    private volatile boolean running;

    @Override
    public void start() {
        if (running) {
            return;
        }
        if (StringUtils.isBlank(props.getProjectId())
                || StringUtils.isBlank(props.getSubscription())) {
            throw new IllegalStateException(
                    "collection.scheduler.enabled=true 但 projectId/subscription 未配置"
                            + "（映射 GCP_PUBSUB_PROJECT / GCP_SCHEDULER_SUBSCRIPTION）");
        }
        ProjectSubscriptionName subscriptionName =
                ProjectSubscriptionName.of(props.getProjectId(), props.getSubscription());
        int concurrency = Math.max(1, props.getMaxConcurrency());
        FlowControlSettings flowControl =
                FlowControlSettings.newBuilder()
                        .setMaxOutstandingElementCount((long) concurrency)
                        .build();
        this.subscriber =
                Subscriber.newBuilder(subscriptionName, this)
                        .setParallelPullCount(1)
                        .setFlowControlSettings(flowControl)
                        .setExecutorProvider(
                                InstantiatingExecutorProvider.newBuilder()
                                        .setExecutorThreadCount(concurrency)
                                        .build())
                        .build();
        subscriber.startAsync().awaitRunning();
        running = true;
        log.info(
                "[Scheduler] 调度订阅消费已启动 subscription={} staleThresholdSeconds={} dailyRollStaleThresholdSeconds={}",
                subscriptionName,
                props.getStaleThresholdSeconds(),
                props.getDailyRollStaleThresholdSeconds());
    }

    @Override
    public void stop() {
        running = false;
        if (subscriber != null) {
            try {
                subscriber.stopAsync().awaitTerminated(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[Scheduler] 调度订阅消费 stop 超时/异常: {}", e.getMessage());
            }
            subscriber = null;
            log.info("[Scheduler] 调度订阅消费已停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void receiveMessage(PubsubMessage message, AckReplyConsumer reply) {
        ScheduledJob job = resolve(message);
        reply.ack();
        if (job != null) {
            runner.run(job);
        }
    }

    /** 解析并校验一条调度消息；返回 {@code null} 表示不触发扫描（未知任务或陈旧消息）。本方法不抛异常。 */
    private ScheduledJob resolve(PubsubMessage message) {
        String attribute = message.getAttributesOrDefault(JOB_ATTRIBUTE, null);
        ScheduledJob job = ScheduledJob.fromAttribute(attribute);
        if (job == null) {
            metrics.scheduleSkipped(UNKNOWN_JOB_TAG, SKIP_UNKNOWN_JOB);
            log.warn(
                    "[Scheduler] 未知调度任务 {}={} msgId={}，ack 跳过不重投",
                    JOB_ATTRIBUTE,
                    attribute,
                    message.getMessageId());
            return null;
        }
        long ageMillis = System.currentTimeMillis() - toMillis(message.getPublishTime());
        long thresholdMillis = TimeUnit.SECONDS.toMillis(props.staleThresholdSeconds(job));
        if (ageMillis > thresholdMillis) {
            metrics.scheduleStaleDiscarded(job.attribute());
            log.warn(
                    "[Scheduler] 陈旧调度消息丢弃 job={} ageMs={} thresholdMs={} msgId={}",
                    job.attribute(),
                    ageMillis,
                    thresholdMillis,
                    message.getMessageId());
            return null;
        }
        return job;
    }

    private static long toMillis(Timestamp ts) {
        return ts.getSeconds() * 1000L + ts.getNanos() / 1_000_000L;
    }
}
