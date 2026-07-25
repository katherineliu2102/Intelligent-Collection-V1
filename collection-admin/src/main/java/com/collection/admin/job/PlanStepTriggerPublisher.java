package com.collection.admin.job;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.engine.config.EngineProperties;
import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 仅执行扫表和 Trigger-to-Event，可被 local scheduler 与 Pilot XXL handler 共同调用。 */
@Component
public class PlanStepTriggerPublisher {

    private static final Logger log = LoggerFactory.getLogger(PlanStepTriggerPublisher.class);

    @Resource private ContactPlanRepository planRepository;
    @Resource private CollectionEventBus eventBus;
    @Resource private EngineProperties props;

    public void publishDueSteps() {
        int limit = props.getConsumer().getScanLimit();
        List<ContactPlanStep> due = planRepository.findDueSteps(LocalDateTime.now(), limit);
        for (ContactPlanStep step : due) {
            eventBus.publish(
                    CollectionEvent.of(EventType.PLAN_STEP_DUE)
                            .with(CollectionEvent.PLAN_ID, step.getPlanId())
                            .with(CollectionEvent.STEP_ID, step.getId()));
        }
        logScan("due-step", due.size(), limit);
    }

    public void publishTimeoutSteps() {
        int limit = props.getConsumer().getScanLimit();
        List<ContactPlanStep> timeout = planRepository.findTimeoutSteps(LocalDateTime.now(), limit);
        for (ContactPlanStep step : timeout) {
            eventBus.publish(
                    CollectionEvent.of(EventType.CALLBACK_TIMEOUT)
                            .with(CollectionEvent.PLAN_ID, step.getPlanId())
                            .with(CollectionEvent.STEP_ID, step.getId()));
        }
        logScan("callback-timeout", timeout.size(), limit);
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
