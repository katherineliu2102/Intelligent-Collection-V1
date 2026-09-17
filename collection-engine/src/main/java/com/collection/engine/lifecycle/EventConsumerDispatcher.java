package com.collection.engine.lifecycle;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.engine.outbox.OutboxEventSink;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 核心引擎唯一入口。对应核心引擎规格 §1.1。
 *
 * <p>从事件总线消费事件，按类型路由到 {@link PlanLifecycleManager}（事务内状态前置）， 提交后发布链式事件；PLAN_STEP_DUE 在提交后于非事务上下文调用
 * {@link StepExecutionOrchestrator}（渠道 I/O 不占行锁）。
 */
@Component
public class EventConsumerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerDispatcher.class);

    /** {@code t_contact_plan} 上的单活跃计划唯一键：{@code (case_id, stage)}，计划终态时生成列置 NULL。 */
    private static final String ACTIVE_PLAN_CONSTRAINT = "uk_active_stage_key";

    @Resource private CollectionEventBus eventBus;
    @Resource private PlanLifecycleManager manager;
    @Resource private StepExecutionOrchestrator orchestrator;

    @Autowired(required = false)
    private OutboxEventSink outboxEventSink;

    @PostConstruct
    public void registerHandlers() {
        eventBus.subscribe(
                EventType.CASE_INGESTED, e -> creatingPlan(e, () -> manager.onCaseIngested(e)));
        eventBus.subscribe(
                EventType.STAGE_CHANGED, e -> creatingPlan(e, () -> manager.onStageChanged(e)));
        eventBus.subscribe(
                EventType.REPAYMENT_RECEIVED, e -> publishAll(manager.onRepaymentReceived(e)));
        eventBus.subscribe(
                EventType.CASE_BALANCE_UPDATED, e -> publishAll(manager.onCaseBalanceUpdated(e)));
        eventBus.subscribe(EventType.CASE_CEASED, e -> publishAll(manager.onCaseCeased(e)));
        eventBus.subscribe(
                EventType.CASE_OWNER_RECONCILED, e -> publishAll(manager.onCaseOwnerReconciled(e)));
        eventBus.subscribe(EventType.STEP_COMPLETED, e -> publishAll(manager.onStepCompleted(e)));
        eventBus.subscribe(
                EventType.CHANNEL_CALLBACK, e -> publishAll(manager.onChannelCallback(e)));
        eventBus.subscribe(
                EventType.CALLBACK_TIMEOUT, e -> publishAll(manager.onCallbackTimeout(e)));
        eventBus.subscribe(
                EventType.PLAN_EXHAUSTED, e -> creatingPlan(e, () -> manager.onPlanExhausted(e)));
        // Phase 2 预留：Phase 1 引擎不消费 PTP_EXPIRED（核心引擎规格 §2.6）。manager.onPtpExpired 保留作前向兼容。
        // eventBus.subscribe(EventType.PTP_EXPIRED, e -> publishAll(manager.onPtpExpired(e)));
        eventBus.subscribe(EventType.PLAN_STEP_DUE, this::onPlanStepDue);
        log.info("[Dispatcher] all engine handlers registered");
    }

    /**
     * 会创建计划的事件在此归一「单活跃计划」唯一键冲突。
     *
     * <p>{@code createPlanForStage} 的预检查 {@code findActivePlanByCaseAndStage} 是非加锁读：同一案件同一
     * 阶段的两条事件并发到达时（Pub/Sub 至少一次投递 + 数仓日常重推，必然发生），两条都能通过预检查， 先提交的建成计划，后提交的在 INSERT 处撞 {@code
     * uk_active_stage_key}。这不是故障——唯一键正是为此 存在，它已经保证了不会出现第二个活跃计划；后到者要做的事情，恰好就是预检查命中时的
     * IDEMPOTENT_SKIP。
     *
     * <p>捕获点必须在这里而不是 {@code createPlanForStage} 内部：{@code onCaseIngested} 带
     * {@code @Transactional}，约束冲突已把事务标记为 rollback-only，在方法内吞掉异常只会换来一个 {@code
     * UnexpectedRollbackException}。此处位于代理之外，事务已完成回滚，而该事务除这条失败的 INSERT 外没有其它写入，回滚即无副作用。
     *
     * <p>不捕获则后果是：一条 ERROR 噪声 + 一轮 reclaim 重试（重试时预检查能读到已提交的计划，自然收敛）。
     * 也就是说它本就自愈，这里消除的是噪声与无谓重试，不是数据错误。2026-08-25 Pilot 实测见台账。
     */
    private void creatingPlan(CollectionEvent event, Supplier<List<CollectionEvent>> handler) {
        try {
            publishAll(handler.get());
        } catch (DuplicateKeyException e) {
            if (!violatesSingleActivePlan(e)) {
                // 其它唯一键冲突（如发件箱 event_id）不在本方法的语义内，照常上抛走重试 / DLQ。
                throw e;
            }
            log.info(
                    "[Dispatcher] {} 并发重复，单活跃计划约束已生效，幂等跳过 eventId={} case={}",
                    event.getEventType(),
                    event.getEventId(),
                    event.getString(CollectionEvent.CASE_ID));
        }
    }

    /** 按约束名判定，而不是见 {@code DuplicateKeyException} 就吞——否则新增的任何唯一键都会被静默跳过。 */
    private static boolean violatesSingleActivePlan(Throwable error) {
        for (Throwable t = error; t != null && t != t.getCause(); t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(ACTIVE_PLAN_CONSTRAINT)) {
                return true;
            }
        }
        return false;
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

    /**
     * 提交后发布。事件已由 {@link PlanLifecycleManager} 在同一事务内写入发件箱（核心引擎规格 §7.2），
     * 这里成功即销账；抛错或进程被杀只是留下一条待重发记录，由 {@code OutboxPublisher} 兜底。
     *
     * <p>逐条独立 try：一条发布失败不应连带丢掉同批次其余事件的销账。
     */
    private void publishAll(List<CollectionEvent> events) {
        if (events == null) {
            return;
        }
        for (CollectionEvent e : events) {
            try {
                eventBus.publish(e);
            } catch (Exception ex) {
                log.error(
                        "[Dispatcher] publish failed, left to outbox republish: {} eventId={}",
                        e.getEventType(),
                        e.getEventId(),
                        ex);
                continue;
            }
            if (outboxEventSink != null) {
                outboxEventSink.markDelivered(e);
            }
        }
    }
}
