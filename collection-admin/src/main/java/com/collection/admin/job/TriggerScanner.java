package com.collection.admin.job;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 扫描触发器（Trigger-to-Event）。对应基础设施规范 §4 planStepDueHandler / callbackTimeoutHandler。
 *
 * <p>Phase 1 骨架用 Spring {@code @Scheduled} 代替 XXL-Job：仅"扫表 → 发事件"，毫秒级返回， 不跑业务逻辑（业务由 Consumer
 * 线程池执行）。生产替换为 XXL-Job Handler，保持本类语义。
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
