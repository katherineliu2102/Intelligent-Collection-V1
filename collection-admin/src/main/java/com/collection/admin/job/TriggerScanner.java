package com.collection.admin.job;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.engine.config.EngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 扫描触发器（Trigger-to-Event）。对应基础设施规范 §4 planStepDueHandler / callbackTimeoutHandler。
 *
 * <p>Phase 1 骨架用 Spring {@code @Scheduled} 代替 XXL-Job：仅"扫表 → 发事件"，毫秒级返回，
 * 不跑业务逻辑（业务由 Consumer 线程池执行）。生产替换为 XXL-Job Handler，保持本类语义。
 */
@Component
public class TriggerScanner {

    private static final Logger log = LoggerFactory.getLogger(TriggerScanner.class);

    @Resource
    private ContactPlanRepository planRepository;
    @Resource
    private CollectionEventBus eventBus;
    @Resource
    private EngineProperties props;

    /** planStepDueHandler：trigger_time <= now 且步骤待触发、关联计划非终态 → 发 PLAN_STEP_DUE。 */
    @Scheduled(fixedDelayString = "${collection.scan.interval-ms:5000}")
    public void scanDueSteps() {
        int limit = props.getConsumer().getScanLimit();
        List<ContactPlanStep> due = planRepository.findDueSteps(LocalDateTime.now(), limit);
        for (ContactPlanStep step : due) {
            eventBus.publish(CollectionEvent.of(EventType.PLAN_STEP_DUE)
                    .with(CollectionEvent.PLAN_ID, step.getPlanId())
                    .with(CollectionEvent.STEP_ID, step.getId()));
        }
        if (!due.isEmpty()) {
            log.debug("[TriggerScanner] published {} PLAN_STEP_DUE", due.size());
        }
        if (due.size() == limit) {
            log.warn("[TriggerScanner] due-step scan hit LIMIT={}, backlog suspected (基础设施规范 §4)", limit);
        }
    }

    /** callbackTimeoutHandler：timeout_time <= now 且 status=EXECUTING → 发 CALLBACK_TIMEOUT。 */
    @Scheduled(fixedDelayString = "${collection.scan.interval-ms:5000}")
    public void scanTimeoutSteps() {
        int limit = props.getConsumer().getScanLimit();
        List<ContactPlanStep> timeout = planRepository.findTimeoutSteps(LocalDateTime.now(), limit);
        for (ContactPlanStep step : timeout) {
            eventBus.publish(CollectionEvent.of(EventType.CALLBACK_TIMEOUT)
                    .with(CollectionEvent.PLAN_ID, step.getPlanId())
                    .with(CollectionEvent.STEP_ID, step.getId()));
        }
        if (!timeout.isEmpty()) {
            log.debug("[TriggerScanner] published {} CALLBACK_TIMEOUT", timeout.size());
        }
    }
}
