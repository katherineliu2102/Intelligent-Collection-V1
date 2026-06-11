package com.collection.engine.lifecycle;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

/**
 * 核心引擎唯一入口。对应核心引擎规格 §1.1。
 *
 * <p>从事件总线消费事件，按类型路由到 {@link PlanLifecycleManager}（事务内状态前置），
 * 提交后发布链式事件；PLAN_STEP_DUE 在提交后于非事务上下文调用
 * {@link StepExecutionOrchestrator}（渠道 I/O 不占行锁）。
 */
@Component
public class EventConsumerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerDispatcher.class);

    @Resource
    private CollectionEventBus eventBus;
    @Resource
    private PlanLifecycleManager manager;
    @Resource
    private StepExecutionOrchestrator orchestrator;

    @PostConstruct
    public void registerHandlers() {
        eventBus.subscribe(EventType.CASE_INGESTED, e -> publishAll(manager.onCaseIngested(e)));
        eventBus.subscribe(EventType.STAGE_CHANGED, e -> publishAll(manager.onStageChanged(e)));
        eventBus.subscribe(EventType.REPAYMENT_RECEIVED, e -> publishAll(manager.onRepaymentReceived(e)));
        eventBus.subscribe(EventType.CASE_CEASED, e -> publishAll(manager.onCaseCeased(e)));
        eventBus.subscribe(EventType.STEP_COMPLETED, e -> publishAll(manager.onStepCompleted(e)));
        eventBus.subscribe(EventType.CHANNEL_CALLBACK, e -> publishAll(manager.onChannelCallback(e)));
        eventBus.subscribe(EventType.CALLBACK_TIMEOUT, e -> publishAll(manager.onCallbackTimeout(e)));
        eventBus.subscribe(EventType.PLAN_EXHAUSTED, e -> publishAll(manager.onPlanExhausted(e)));
        eventBus.subscribe(EventType.PTP_EXPIRED, e -> publishAll(manager.onPtpExpired(e)));
        eventBus.subscribe(EventType.PLAN_STEP_DUE, this::onPlanStepDue);
        log.info("[Dispatcher] all engine handlers registered");
    }

    private void onPlanStepDue(CollectionEvent event) {
        // 事务内：lock → 校验终态 → 状态前置 → COMMIT
        StepDuePreparation prep = manager.prepareStepDue(event);
        // 提交后发布（观察期结转的 STEP_COMPLETED 等）
        publishAll(prep.getEvents());
        // 提交后、非事务上下文：渠道 I/O
        if (prep.isExecute()) {
            orchestrator.executeStep(prep.getPlan(), prep.getStep());
        }
    }

    private void publishAll(List<CollectionEvent> events) {
        if (events == null) {
            return;
        }
        for (CollectionEvent e : events) {
            eventBus.publish(e);
        }
    }
}
