package com.collection.admin.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.collection.admin.config.SchedulerProperties;
import com.collection.engine.metrics.CollectionMetrics;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PubsubMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 调度消息的路由、陈旧丢弃与 ACK 语义。
 *
 * <p>只测 {@code receiveMessage} 这一层，不启动 {@code Subscriber}，因此不依赖 GCP 凭证或网络。
 */
class PubSubScheduleConsumerTest {

    private SchedulerProperties props;
    private ScheduledJobRunner runner;
    private SimpleMeterRegistry registry;
    private PubSubScheduleConsumer consumer;
    private AckReplyConsumer reply;

    @BeforeEach
    void setUp() {
        props = new SchedulerProperties();
        props.setEnabled(true);
        props.setProjectId("test-project");
        props.setSubscription("collection-schedule-test-sub");
        runner = mock(ScheduledJobRunner.class);
        registry = new SimpleMeterRegistry();
        reply = mock(AckReplyConsumer.class);
        consumer = new PubSubScheduleConsumer();
        ReflectionTestUtils.setField(consumer, "props", props);
        ReflectionTestUtils.setField(consumer, "runner", runner);
        ReflectionTestUtils.setField(consumer, "metrics", new CollectionMetrics(registry));
    }

    @Test
    void freshMessageTriggersScanAndAcks() {
        consumer.receiveMessage(message("planStepDue", secondsAgo(5)), reply);

        verify(runner).run(ScheduledJob.PLAN_STEP_DUE);
        verify(reply).ack();
        verify(reply, never()).nack();
    }

    @Test
    void routesEachJobAttributeToItsOwnTask() {
        consumer.receiveMessage(message("planStepDue", secondsAgo(1)), reply);
        consumer.receiveMessage(message("callbackTimeout", secondsAgo(1)), reply);
        consumer.receiveMessage(message("dailyRoll", secondsAgo(1)), reply);

        verify(runner).run(ScheduledJob.PLAN_STEP_DUE);
        verify(runner).run(ScheduledJob.CALLBACK_TIMEOUT);
        verify(runner).run(ScheduledJob.DAILY_ROLL);
        verify(reply, times(3)).ack();
    }

    /** 停机后重启会一次性收到累积的每分钟消息；超过阈值的必须丢弃并计数，避免扫描风暴。 */
    @Test
    void staleMessageIsDiscardedButStillAcked() {
        consumer.receiveMessage(message("planStepDue", secondsAgo(90)), reply);

        verify(runner, never()).run(ScheduledJob.PLAN_STEP_DUE);
        verify(reply).ack();
        verify(reply, never()).nack();
        assertThat(counter("collection.schedule.stale.discarded", "planStepDue")).isEqualTo(1.0);
    }

    /** 模拟停机 30 分钟后重启：约 30 条每分钟消息一次性到达，只有当前 tick 放行。 */
    @Test
    void backlogAfterDowntimeTriggersAtMostTheCurrentTick() {
        // 停机期间累积的 tick，重启后逐条到达时都已超过 60s（含进程启动耗时）。
        for (int minutesAgo = 30; minutesAgo >= 1; minutesAgo--) {
            consumer.receiveMessage(
                    message("planStepDue", secondsAgo(minutesAgo * 60L + 5)),
                    mock(AckReplyConsumer.class));
        }
        consumer.receiveMessage(message("planStepDue", secondsAgo(5)), reply);

        verify(runner, times(1)).run(ScheduledJob.PLAN_STEP_DUE);
        assertThat(counter("collection.schedule.stale.discarded", "planStepDue")).isEqualTo(30.0);
    }

    /** 日切每 5 分钟一轮，阈值 300s：当轮 tick 即使已过 60s 仍须放行，否则白白损失一整页 keyset 推进。 */
    @Test
    void dailyRollToleratesAgeUpToItsOwnPeriod() {
        consumer.receiveMessage(message("dailyRoll", secondsAgo(200)), reply);

        verify(runner).run(ScheduledJob.DAILY_ROLL);
        assertThat(registry.find("collection.schedule.stale.discarded").counter()).isNull();
    }

    /** 同样 200s，每分钟任务必须判为陈旧——阈值是按任务周期分别取的。 */
    @Test
    void perMinuteJobRejectsTheAgeDailyRollAccepts() {
        consumer.receiveMessage(message("planStepDue", secondsAgo(200)), reply);

        verify(runner, never()).run(ScheduledJob.PLAN_STEP_DUE);
        assertThat(counter("collection.schedule.stale.discarded", "planStepDue")).isEqualTo(1.0);
    }

    /** 上一轮的日切 tick（5 分钟前）仍须被挡住，300s 阈值不会放宽到多轮。 */
    @Test
    void dailyRollDiscardsPreviousTick() {
        consumer.receiveMessage(message("dailyRoll", secondsAgo(310)), reply);

        verify(runner, never()).run(ScheduledJob.DAILY_ROLL);
        assertThat(counter("collection.schedule.stale.discarded", "dailyRoll")).isEqualTo(1.0);
    }

    @Test
    void unknownJobAttributeIsAckedWithoutTriggering() {
        consumer.receiveMessage(message("ptpExpired", secondsAgo(1)), reply);

        verify(runner, never()).run(org.mockito.ArgumentMatchers.any());
        verify(reply).ack();
        verify(reply, never()).nack();
        assertThat(counter("collection.schedule.skipped", "unknown", "UNKNOWN_JOB")).isEqualTo(1.0);
    }

    @Test
    void missingJobAttributeIsAckedWithoutTriggering() {
        PubsubMessage noAttribute =
                PubsubMessage.newBuilder()
                        .setMessageId("m-no-attr")
                        .setPublishTime(secondsAgo(1))
                        .build();

        consumer.receiveMessage(noAttribute, reply);

        verify(runner, never()).run(org.mockito.ArgumentMatchers.any());
        verify(reply).ack();
    }

    /** 重复投递（至少一次投递语义）在消费者层不做去重，两次都触发；并发由 ScheduledJobRunner 单飞吸收。 */
    @Test
    void duplicateDeliveryOfSameMessageIsAckedEachTime() {
        PubsubMessage duplicate = message("planStepDue", secondsAgo(2));

        consumer.receiveMessage(duplicate, reply);
        consumer.receiveMessage(duplicate, reply);

        verify(runner, times(2)).run(ScheduledJob.PLAN_STEP_DUE);
        verify(reply, times(2)).ack();
        verify(reply, never()).nack();
    }

    @Test
    void startFailsFastWhenSubscriptionMissing() {
        props.setSubscription("  ");

        try {
            consumer.start();
            org.junit.jupiter.api.Assertions.fail("缺订阅配置必须启动失败");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("GCP_SCHEDULER_SUBSCRIPTION");
        }
    }

    private PubsubMessage message(String job, Timestamp publishTime) {
        return PubsubMessage.newBuilder()
                .setMessageId("m-" + job + "-" + publishTime.getSeconds())
                .putAttributes(PubSubScheduleConsumer.JOB_ATTRIBUTE, job)
                .setPublishTime(publishTime)
                .build();
    }

    private Timestamp secondsAgo(long seconds) {
        long millis = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(seconds);
        return Timestamp.newBuilder()
                .setSeconds(millis / 1000L)
                .setNanos((int) (millis % 1000L) * 1_000_000)
                .build();
    }

    private double counter(String name, String job) {
        return registry.get(name).tag("job", job).counter().count();
    }

    private double counter(String name, String job, String reason) {
        return registry.get(name).tag("job", job).tag("reason", reason).counter().count();
    }
}
