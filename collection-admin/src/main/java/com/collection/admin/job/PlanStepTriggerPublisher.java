package com.collection.admin.job;

import com.collection.admin.config.ScanProperties;
import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.engine.config.EngineProperties;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 仅执行扫表和 Trigger-to-Event，由 {@code local}/{@code test} 的 {@link TriggerScanner} 与生产的 {@link
 * ScheduledJobRunner}（Cloud Scheduler → Pub/Sub 调度订阅）共同调用。
 */
@Component
public class PlanStepTriggerPublisher {

    private static final Logger log = LoggerFactory.getLogger(PlanStepTriggerPublisher.class);
    private static final ZoneId PHT = ZoneId.of("Asia/Manila");

    @Resource private ContactPlanRepository planRepository;
    @Resource private CollectionEventBus eventBus;
    @Resource private EngineProperties props;
    @Resource private ScanProperties scanProps;

    /** @return 本次扫描发布的事件条数（供调度指标记录） */
    public int publishDueSteps() {
        int limit = props.getConsumer().getScanLimit();
        List<ContactPlanStep> due =
                planRepository.findDueSteps(LocalDateTime.now(PHT), limit, scanProps.caseFilter());
        for (ContactPlanStep step : due) {
            eventBus.publish(
                    CollectionEvent.of(EventType.PLAN_STEP_DUE)
                            .with(CollectionEvent.PLAN_ID, step.getPlanId())
                            .with(CollectionEvent.STEP_ID, step.getId()));
        }
        logScan("due-step", due.size(), limit);
        return due.size();
    }

    /** @return 本次扫描发布的事件条数（供调度指标记录） */
    public int publishTimeoutSteps() {
        int limit = props.getConsumer().getScanLimit();
        List<ContactPlanStep> timeout =
                planRepository.findTimeoutSteps(
                        LocalDateTime.now(PHT), limit, scanProps.caseFilter());
        for (ContactPlanStep step : timeout) {
            eventBus.publish(
                    CollectionEvent.of(EventType.CALLBACK_TIMEOUT)
                            .with(CollectionEvent.PLAN_ID, step.getPlanId())
                            .with(CollectionEvent.STEP_ID, step.getId()));
        }
        logScan("callback-timeout", timeout.size(), limit);
        return timeout.size();
    }

    private void logScan(String scanType, int count, int limit) {
        if (count > 0) {
            log.debug("[PlanStepTriggerPublisher] published {} {} events", count, scanType);
        }
        if (count == limit) {
            log.warn(
                    "[PlanStepTriggerPublisher] {} scan hit LIMIT={}, backlog suspected",
                    scanType,
                    limit);
        }
    }
}
