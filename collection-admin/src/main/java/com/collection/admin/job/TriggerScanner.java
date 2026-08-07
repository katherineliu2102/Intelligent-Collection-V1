package com.collection.admin.job;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 扫描触发器（Trigger-to-Event）。对应基础设施规范 §5 的 planStepDue / callbackTimeout 两个调度任务。
 *
 * <p>仅"扫表 → 发事件"，毫秒级返回，不跑业务逻辑（业务由 Consumer 线程池执行）。
 *
 * <p><b>仅 local / test 生效</b>：生产调度入口是 Cloud Scheduler → 调度专用 Pub/Sub 主题 → {@link
 * PubSubScheduleConsumer} → {@link ScheduledJobRunner}，两者调用同一个 {@link
 * PlanStepTriggerPublisher}，语义一致。 调度入口必须唯一，本类与生产调度不得同时激活，由 {@code SchedulerEntrypointValidator}
 * 在启动时强制。
 */
@Component
@Profile({"local", "test"})
public class TriggerScanner {

    private final PlanStepTriggerPublisher triggerPublisher;

    public TriggerScanner(PlanStepTriggerPublisher triggerPublisher) {
        this.triggerPublisher = triggerPublisher;
    }

    /** planStepDueHandler：trigger_time <= now 且步骤待触发、关联计划非终态 → 发 PLAN_STEP_DUE。 */
    @Scheduled(fixedDelayString = "${collection.scan.interval-ms:5000}")
    public void scanDueSteps() {
        triggerPublisher.publishDueSteps();
    }

    /** callbackTimeoutHandler：timeout_time <= now 且 status=EXECUTING → 发 CALLBACK_TIMEOUT。 */
    @Scheduled(fixedDelayString = "${collection.scan.interval-ms:5000}")
    public void scanTimeoutSteps() {
        triggerPublisher.publishTimeoutSteps();
    }
}
