package com.collection.admin.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.engine.metrics.CollectionMetrics;
import com.collection.ingestion.job.DpdStageRollHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 调度任务分派、单飞保护与失败隔离。 */
class ScheduledJobRunnerTest {

    private PlanStepTriggerPublisher triggerPublisher;
    private DpdStageRollHandler dailyRollHandler;
    private SimpleMeterRegistry registry;
    private ScheduledJobRunner runner;

    @BeforeEach
    void setUp() {
        triggerPublisher = mock(PlanStepTriggerPublisher.class);
        dailyRollHandler = mock(DpdStageRollHandler.class);
        registry = new SimpleMeterRegistry();
        runner =
                new ScheduledJobRunner(
                        triggerPublisher, dailyRollHandler, new CollectionMetrics(registry));
    }

    @Test
    void routesPlanStepDueToDueStepScan() {
        when(triggerPublisher.publishDueSteps()).thenReturn(7);

        runner.run(ScheduledJob.PLAN_STEP_DUE);

        verify(triggerPublisher).publishDueSteps();
        verify(triggerPublisher, never()).publishTimeoutSteps();
        verify(dailyRollHandler, never()).dailyRoll();
        assertThat(counter("collection.schedule.triggered", "planStepDue")).isEqualTo(1.0);
        assertThat(counter("collection.schedule.scan.rows", "planStepDue")).isEqualTo(7.0);
    }

    @Test
    void routesCallbackTimeoutToTimeoutScan() {
        when(triggerPublisher.publishTimeoutSteps()).thenReturn(2);

        runner.run(ScheduledJob.CALLBACK_TIMEOUT);

        verify(triggerPublisher).publishTimeoutSteps();
        verify(triggerPublisher, never()).publishDueSteps();
        assertThat(counter("collection.schedule.scan.rows", "callbackTimeout")).isEqualTo(2.0);
    }

    @Test
    void routesDailyRollToIngestionHandler() {
        when(dailyRollHandler.dailyRoll()).thenReturn(1000);

        runner.run(ScheduledJob.DAILY_ROLL);

        verify(dailyRollHandler).dailyRoll();
        verify(triggerPublisher, never()).publishDueSteps();
        assertThat(counter("collection.schedule.scan.rows", "dailyRoll")).isEqualTo(1000.0);
    }

    /** 扫描失败不得上抛：调度消息已 ack，靠 nack 重投只会堆积过期 tick，失败只能靠指标告警。 */
    @Test
    void scanFailureIsContainedAndCounted() {
        when(triggerPublisher.publishDueSteps()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> runner.run(ScheduledJob.PLAN_STEP_DUE)).doesNotThrowAnyException();

        assertThat(counter("collection.schedule.failed", "planStepDue")).isEqualTo(1.0);
    }

    /** 失败后闸门必须释放，否则一次故障会让该任务永久静默。 */
    @Test
    void singleFlightGateIsReleasedAfterFailure() {
        when(triggerPublisher.publishDueSteps())
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(3);

        runner.run(ScheduledJob.PLAN_STEP_DUE);
        runner.run(ScheduledJob.PLAN_STEP_DUE);

        verify(triggerPublisher, times(2)).publishDueSteps();
        assertThat(counter("collection.schedule.scan.rows", "planStepDue")).isEqualTo(3.0);
    }

    /** 重复投递（同一 tick 被投两次）在串行到达时各跑一次；跨投递幂等由步骤幂等锁与日切游标兜底，不在此加去重层。 */
    @Test
    void sequentialDuplicateDeliveriesEachRunOnce() {
        when(triggerPublisher.publishDueSteps()).thenReturn(1);

        runner.run(ScheduledJob.PLAN_STEP_DUE);
        runner.run(ScheduledJob.PLAN_STEP_DUE);

        verify(triggerPublisher, times(2)).publishDueSteps();
        assertThat(counter("collection.schedule.skipped", "planStepDue", "IN_FLIGHT")).isZero();
    }

    /** 并发投递必须单飞：同一任务不得同时跑两次扫描。 */
    @Test
    void concurrentDeliveryOfSameJobRunsOnlyOnce() throws Exception {
        CountDownLatch scanStarted = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        when(triggerPublisher.publishDueSteps())
                .thenAnswer(
                        invocation -> {
                            scanStarted.countDown();
                            releaseScan.await(5, TimeUnit.SECONDS);
                            return 5;
                        });

        Thread first = new Thread(() -> runner.run(ScheduledJob.PLAN_STEP_DUE));
        first.start();
        assertThat(scanStarted.await(5, TimeUnit.SECONDS)).isTrue();

        runner.run(ScheduledJob.PLAN_STEP_DUE);

        releaseScan.countDown();
        first.join(5_000);

        verify(triggerPublisher, times(1)).publishDueSteps();
        assertThat(counter("collection.schedule.skipped", "planStepDue", "IN_FLIGHT"))
                .isEqualTo(1.0);
    }

    /** 不同任务之间互不阻塞：日切在跑不应挡住每分钟的到期扫描。 */
    @Test
    void singleFlightIsPerJob() throws Exception {
        CountDownLatch rollStarted = new CountDownLatch(1);
        CountDownLatch releaseRoll = new CountDownLatch(1);
        when(dailyRollHandler.dailyRoll())
                .thenAnswer(
                        invocation -> {
                            rollStarted.countDown();
                            releaseRoll.await(5, TimeUnit.SECONDS);
                            return 1000;
                        });
        when(triggerPublisher.publishDueSteps()).thenReturn(4);

        Thread roll = new Thread(() -> runner.run(ScheduledJob.DAILY_ROLL));
        roll.start();
        assertThat(rollStarted.await(5, TimeUnit.SECONDS)).isTrue();

        runner.run(ScheduledJob.PLAN_STEP_DUE);

        releaseRoll.countDown();
        roll.join(5_000);

        verify(triggerPublisher).publishDueSteps();
        assertThat(counter("collection.schedule.scan.rows", "planStepDue")).isEqualTo(4.0);
    }

    private double counter(String name, String job) {
        return registry.get(name).tag("job", job).counter().count();
    }

    private double counter(String name, String job, String reason) {
        return registry.find(name).tag("job", job).tag("reason", reason).counter() == null
                ? 0.0
                : registry.get(name).tag("job", job).tag("reason", reason).counter().count();
    }
}
