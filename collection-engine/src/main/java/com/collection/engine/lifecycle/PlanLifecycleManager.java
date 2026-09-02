package com.collection.engine.lifecycle;

import com.collection.common.dto.ExhaustionResult;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.*;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.*;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.service.CaseService;
import com.collection.common.service.PredictiveDialerService;
import com.collection.common.spi.AdvancementPolicy;
import com.collection.common.spi.ExhaustionPolicy;
import com.collection.common.spi.PlanFactory;
import com.collection.common.util.JsonUtil;
import com.collection.engine.metrics.CollectionMetrics;
import com.collection.engine.outbox.OutboxEventSink;
import com.collection.engine.spi.SpiInvoker;
import com.collection.engine.spi.SpiType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计划级生命周期管理。对应核心引擎规格 §2。
 *
 * <p>每个 onXxx 方法即一次"短事务（SELECT FOR UPDATE → 校验终态 → 状态前置 → COMMIT）"。
 * 方法返回需在<b>提交后</b>发布的事件列表（核心引擎规格："事务外发布，保证写入已落盘"）， 由 {@link EventConsumerDispatcher} 在方法返回后投递。
 */
@Component
public class PlanLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(PlanLifecycleManager.class);
    private static final ZoneId PHT = ZoneId.of("Asia/Manila");
    private static final int COLLECTION_WINDOW_START_HOUR = 8;

    @Resource private ContactPlanRepository planRepository;
    @Resource private TimelineRepository timelineRepository;
    @Resource private StepOutcomeRecorder stepOutcomeRecorder;
    @Resource private CaseService caseService;
    @Resource private PlanFactory planFactory;
    @Resource private AdvancementPolicy advancementPolicy;
    @Resource private ExhaustionPolicy exhaustionPolicy;
    @Resource private PredictiveDialerService predictiveDialerService;
    @Resource private SpiInvoker spiInvoker;
    @Resource private CollectionMetrics metrics;

    @Autowired(required = false)
    private OutboxEventSink outboxEventSink;

    @Value("${collection.repayment-url-template:https://app.mocasa.com/repay/{caseId}}")
    private String repaymentUrlTemplate = "https://app.mocasa.com/repay/{caseId}";

    // ───────────────────────── 计划创建（§2.2） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onCaseIngested(CollectionEvent event) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        if (caseId == null) {
            // 关键键缺失：异常上抛 → NACK 重消费（接入侧已强校验，核心引擎规格 §7）
            throw new IllegalArgumentException("CASE_INGESTED missing caseId");
        }
        Stage stage = parseStage(event.getString(CollectionEvent.STAGE));
        Integer dpd = event.getInt(CollectionEvent.DPD);
        if (stage == null && dpd != null) {
            stage = Stage.fromDpd(dpd);
        }
        // 决策 B：payload 携带快照字段时据此组装，运行时不读旧库 t_collection；
        // 缺失（如旧调用 / 兜底）时降级 CaseService。
        ContextSnapshot snapshot =
                hasSnapshotPayload(event) ? buildSnapshotFromEvent(event, stage) : null;
        CaseInfo caseInfo = caseInfoFromSnapshot(snapshot);
        if (stage == null) {
            CaseInfo info = caseInfo != null ? caseInfo : caseService.getCaseInfo(caseId);
            stage = info != null ? info.getStage() : null;
        }
        createPlanForStage(caseId, stage, caseInfo, snapshot, null);
        return noEvents();
    }

    @Transactional
    public List<CollectionEvent> onStageChanged(CollectionEvent event) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        Stage newStage = parseStage(event.getString(CollectionEvent.STAGE));

        List<ContactPlan> oldPlans = planRepository.findActivePlansByCase(caseId);
        oldPlans.sort((a, b) -> Long.compare(a.getId(), b.getId())); // 按 id 升序加锁防死锁
        // v2 CASE_STAGE_CHANGED 携带完整快照，优先直接使用，避免无活跃计划时回读旧库。
        // 旧事件仍保留 carry-forward 兼容路径。
        ContextSnapshot carried =
                hasSnapshotPayload(event) ? buildSnapshotFromEvent(event, newStage) : null;
        if (carried == null) {
            for (ContactPlan p : oldPlans) {
                if (carried == null) {
                    carried = snapshotFromPlan(p);
                }
            }
        }
        carried =
                withRefreshedFields(
                        carried,
                        newStage,
                        event.getInt(CollectionEvent.DPD),
                        event.getBigDecimal(CollectionEvent.TOTAL_OUTSTANDING));
        CaseInfo carriedInfo = caseInfoFromSnapshot(carried);
        for (ContactPlan p : oldPlans) {
            ContactPlan locked = planRepository.findPlanWithLock(p.getId());
            if (locked == null || locked.isTerminal() || locked.getStage() == newStage) {
                continue;
            }
            if (locked.getStage() != newStage) {
                planRepository.updatePlanStatus(
                        locked.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.STAGE_UPGRADE);
                log.info(
                        "[stageChanged] cancelled old plan {} ({}→{})",
                        locked.getId(),
                        locked.getStage(),
                        newStage);
            }
        }
        createPlanForStage(caseId, newStage, carriedInfo, carried, null);
        return noEvents();
    }

    // ───────────────────────── 中断（§2.4） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onCaseCeased(CollectionEvent event) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        List<ContactPlan> plans = planRepository.findActivePlansByCase(caseId);
        plans.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (ContactPlan p : plans) {
            ContactPlan locked = planRepository.findPlanWithLock(p.getId());
            if (locked == null || locked.isTerminal()) {
                continue;
            }
            planRepository.updatePlanStatus(
                    locked.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.CEASED);
            log.info("[caseCeased] cancelled plan {} (CEASED)", locked.getId());
        }
        return noEvents();
    }

    @Transactional
    public List<CollectionEvent> onRepaymentReceived(CollectionEvent event) {
        Long userId = event.getLong(CollectionEvent.USER_ID);
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        if (caseId == null) {
            log.warn("[repayment] ignore event without caseId, user={}", userId);
            return noEvents();
        }
        List<ContactPlan> plans = planRepository.findActivePlansByCase(caseId);
        plans.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (ContactPlan p : plans) {
            ContactPlan locked = planRepository.findPlanWithLock(p.getId());
            if (locked == null || locked.isTerminal()) {
                continue;
            }
            planRepository.updatePlanStatus(
                    locked.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
            log.info("[repayment] cancelled plan {} (REPAID)", locked.getId());
        }
        try {
            predictiveDialerService.filterRepaidCase(userId, caseId);
        } catch (Exception e) {
            // 告警 + 继续：计划已取消是核心目标（核心引擎规格 §5）
            log.warn(
                    "[repayment] filterRepaidCase failed for user {} case {}: {}",
                    userId,
                    caseId,
                    e.getMessage());
        }
        return noEvents();
    }

    /** 部分还款的唯一计划变更：更新活跃计划快照中的余额。DPD、阶段、模板、渠道和话术决策字段保持原值。 */
    @Transactional
    public List<CollectionEvent> onCaseBalanceUpdated(CollectionEvent event) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        java.math.BigDecimal amount = event.getBigDecimal(CollectionEvent.TOTAL_OUTSTANDING);
        if (caseId == null || amount == null || amount.signum() < 0) {
            log.warn("[balanceUpdated] ignore invalid event case={} amount={}", caseId, amount);
            return noEvents();
        }
        for (ContactPlan plan : planRepository.findActivePlansByCase(caseId)) {
            ContactPlan locked = planRepository.findPlanWithLock(plan.getId());
            ContextSnapshot snapshot = snapshotFromPlan(locked);
            if (locked == null
                    || locked.isTerminal()
                    || snapshot == null
                    || snapshot.getCaseContext() == null) {
                continue;
            }
            CaseContext context = snapshot.getCaseContext();
            context.setTotalOutstanding(amount);
            if (event.has(CollectionEvent.OVERDUE_AMOUNT)) {
                context.setOverdueAmount(event.getBigDecimal(CollectionEvent.OVERDUE_AMOUNT));
            }
            if (event.has(CollectionEvent.DPD)) {
                context.setDpd(event.getInt(CollectionEvent.DPD));
            }
            if (event.has(CollectionEvent.PENALTY_AMOUNT)) {
                context.setPenaltyAmount(event.getBigDecimal(CollectionEvent.PENALTY_AMOUNT));
            }
            if (event.has(CollectionEvent.UPCOMING_AMOUNT)) {
                context.setUpcomingAmount(event.getBigDecimal(CollectionEvent.UPCOMING_AMOUNT));
            }
            if (event.has(CollectionEvent.NEXT_DUE_DATE)) {
                context.setNextDueDate(parseDate(event.getString(CollectionEvent.NEXT_DUE_DATE)));
            }
            if (event.has("collectionStatus")) {
                context.setCollectionStatus(event.getString("collectionStatus"));
            }
            if (planRepository.updateActivePlanContextSnapshot(
                    locked.getId(), JsonUtil.toJson(snapshot))) {
                log.info("[balanceUpdated] plan={} amount={}", locked.getId(), amount);
            }
        }
        return noEvents();
    }

    // ───────────────────────── 步骤到期分流（§2.3.1） ─────────────────────────

    @Transactional
    public StepDuePreparation prepareStepDue(CollectionEvent event) {
        Long planId = event.getLong(CollectionEvent.PLAN_ID);
        Long stepId = event.getLong(CollectionEvent.STEP_ID);

        ContactPlan plan = planRepository.findPlanWithLock(planId);
        if (plan == null || plan.isTerminal()) {
            return StepDuePreparation.noop(); // 终态拦截
        }
        ContactPlanStep step = planRepository.findStepById(stepId);
        if (step == null) {
            return StepDuePreparation.noop();
        }
        // 至少一次投递下重复的 PLAN_STEP_DUE 不得把已终结步骤改回 EXECUTING：
        // markStepExecuting 无状态前置且不清 completed_at，回退后该步骤再也不会收敛。
        if (step.getStatus() != null && step.getStatus().isTerminal()) {
            log.info(
                    "[stepDue] duplicate due for terminal step {} ({}), skip",
                    stepId,
                    step.getStatus());
            return StepDuePreparation.noop();
        }

        if (plan.getStatus() == PlanStatus.PENDING
                || plan.getStatus() == PlanStatus.STEP_SCHEDULED
                || plan.getStatus() == PlanStatus.STEP_EXECUTING) {
            // 先抢步骤再动计划：抢占失败时计划状态必须保持原样，否则会把已被上一次投递
            // 推进到下一步（或已终结）的计划按回 STEP_EXECUTING，造成计划侧停摆。
            // 清空 trigger_time 防止扫描器在处理窗口内重复投递（幂等锁亦兜底）。
            if (!planRepository.markStepExecuting(stepId)) {
                log.info("[stepDue] step {} 已终结或被并发抢占，跳过本次投递", stepId);
                return StepDuePreparation.noop();
            }
            // 状态前置（PENDING/SCHEDULED 首次执行；EXECUTING 为退避重试再触发）
            planRepository.updatePlanStatus(planId, PlanStatus.STEP_EXECUTING, null);
            planRepository.markStarted(planId);
            plan.setStatus(PlanStatus.STEP_EXECUTING);
            return StepDuePreparation.toExecute(plan, step);
        }

        if (plan.getStatus() == PlanStatus.STEP_WAITING) {
            // 观察期到期结转
            ContactResult result =
                    step.getResult() != null ? step.getResult() : ContactResult.SENT_NO_RESPONSE;
            planRepository.updateStepStatus(stepId, StepStatus.COMPLETED, result);
            StepDuePreparation prep = StepDuePreparation.noop();
            prep.getEvents().add(enqueued(EngineEvents.stepCompleted(plan, step)));
            return prep;
        }
        return StepDuePreparation.noop();
    }

    // ───────────────────────── 步骤完成推进（§2.3.2） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onStepCompleted(CollectionEvent event) {
        Long planId = event.getLong(CollectionEvent.PLAN_ID);
        Long stepId = event.getLong(CollectionEvent.STEP_ID);

        ContactPlan plan = planRepository.findPlanWithLock(planId);
        if (plan == null || plan.isTerminal()) {
            return noEvents();
        }
        // 异步步可能在计划已被后续 due 推走后才收口：步骤要关，但不得按旧步再 ADVANCE_NEXT。
        if (!planAwaitsAsyncOutcome(plan)) {
            log.info(
                    "[advance] plan {} already {}, skip STEP_COMPLETED for step {}",
                    planId,
                    plan.getStatus(),
                    stepId);
            return noEvents();
        }
        ContactPlanStep completed = planRepository.findStepById(stepId);
        StepResult stepResult = toStepResult(completed);

        // SPI 硬超时 10ms；异常/超时上抛 → 事务回滚 → NACK 延迟重消费（核心引擎规格 §4.1）
        com.collection.common.dto.ExecutionContext liteCtx = buildLiteContext(plan, completed);
        AdvancementDecision decision =
                spiInvoker.call(
                        SpiType.ADVANCEMENT_POLICY,
                        () -> advancementPolicy.decide(liteCtx, stepResult));

        switch (decision) {
            case ADVANCE_NEXT:
                if (completed.getChannelType() == ChannelType.AI_CALL
                        && completed.getResult() == ContactResult.ANSWERED) {
                    skipSameDayPendingAiCalls(planId, completed);
                }
                ContactPlanStep next = planRepository.getNextStep(planId, completed.getStepOrder());
                if (next == null) {
                    log.info("[advance] plan {} no next step → PLAN_EXHAUSTED", planId);
                    return single(enqueued(EngineEvents.planExhausted(plan)));
                }
                LocalDateTime triggerTime = next.getTriggerTime();
                if (triggerTime == null) {
                    triggerTime =
                            LocalDateTime.now(java.time.ZoneId.of("Asia/Manila"))
                                    .plusMinutes(Math.max(0, next.getDelayMinutes()));
                    planRepository.updateStepTriggerTime(
                            next.getId(), triggerTime, StepStatus.PENDING);
                }
                planRepository.updateCurrentStep(planId, next.getStepOrder());
                planRepository.updatePlanStatus(planId, PlanStatus.STEP_SCHEDULED, null);
                log.info(
                        "[advance] plan {} → STEP_SCHEDULED, next step {} at {}",
                        planId,
                        next.getId(),
                        triggerTime);
                return noEvents();

            case PLAN_COMPLETED:
                if (stillInCollection(plan)) {
                    log.info(
                            "[advance] plan {} last-step PLAN_COMPLETED while still collecting → PLAN_EXHAUSTED",
                            planId);
                    return single(enqueued(EngineEvents.planExhausted(plan)));
                }
                planRepository.updatePlanStatus(planId, PlanStatus.PLAN_COMPLETED, null);
                log.info("[advance] plan {} → PLAN_COMPLETED (no longer collecting)", planId);
                return noEvents();

            case PLAN_EXHAUSTED:
            default:
                return single(enqueued(EngineEvents.planExhausted(plan)));
        }
    }

    // ───────────────────────── 异步回调（§2.3.3） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onChannelCallback(CollectionEvent event) {
        Long planId = event.getLong(CollectionEvent.PLAN_ID);
        Long stepId = event.getLong(CollectionEvent.STEP_ID);

        ContactPlan plan = planRepository.findPlanWithLock(planId);
        ContactPlanStep step = planRepository.findStepById(stepId);
        // 收口看步骤是否仍 EXECUTING，不看计划态：后续 due 可能已把计划推到 STEP_SCHEDULED。
        // 计划终态（取消/完成）仍关掉悬挂步，避免超时扫描永远摸到它。
        if (plan == null || step == null) {
            return noEvents();
        }
        String callbackOutcome = event.getString(CollectionEvent.DISPOSITION);
        if (callbackOutcome == null) {
            callbackOutcome = event.getString(CollectionEvent.RESULT);
        }
        ContactResult result = mapCallbackToResult(callbackOutcome);
        if (!stepOutcomeRecorder.recordTerminal(
                plan,
                step,
                StepStatus.EXECUTING,
                StepStatus.COMPLETED,
                result,
                step.getChannelType(),
                event.getString(CollectionEvent.PROVIDER_MSG_ID),
                null)) {
            return noEvents();
        }
        log.info(
                "[callback] plan {} step {} result {} (planStatus={})",
                planId,
                stepId,
                result,
                plan.getStatus());
        // 入箱已由 recordTerminal 在同一事务内完成；计划已离开本步时不再即时推进。
        if (!planAwaitsAsyncOutcome(plan)) {
            return noEvents();
        }
        return single(EngineEvents.stepCompleted(plan, step));
    }

    // ───────────────────────── 回调超时兜底（§2.3.4） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onCallbackTimeout(CollectionEvent event) {
        Long planId = event.getLong(CollectionEvent.PLAN_ID);
        Long stepId = event.getLong(CollectionEvent.STEP_ID);

        ContactPlan plan = planRepository.findPlanWithLock(planId);
        ContactPlanStep step = planRepository.findStepById(stepId);
        if (plan == null || step == null) {
            return noEvents();
        }
        if (!stepOutcomeRecorder.recordTerminal(
                plan,
                step,
                StepStatus.EXECUTING,
                StepStatus.FAILED,
                ContactResult.FAILED,
                step.getChannelType(),
                null,
                JsonUtil.toJson(
                        java.util.Collections.singletonMap("errorCode", "CALLBACK_TIMEOUT")))) {
            return noEvents();
        }
        log.info(
                "[callbackTimeout] plan {} step {} → FAILED (planStatus={})",
                planId,
                stepId,
                plan.getStatus());
        if (!planAwaitsAsyncOutcome(plan)) {
            return noEvents();
        }
        return single(EngineEvents.stepCompleted(plan, step));
    }

    // ───────────────────────── 穷尽续建（§2.5） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onPlanExhausted(CollectionEvent event) {
        Long planId = event.getLong(CollectionEvent.PLAN_ID);
        ContactPlan plan = planRepository.findPlanWithLock(planId);
        if (plan == null || plan.isTerminal()) {
            return noEvents();
        }
        // 续建沿用旧计划已冻结快照（非外部案件事件，无新 payload）；
        // 缺失时降级 CaseService（兜底）。
        ContextSnapshot carried = snapshotFromPlan(plan);
        final ContextSnapshot snapshot =
                carried != null ? carried : caseService.getContextSnapshot(plan.getCaseId());
        CaseInfo ci = caseInfoFromSnapshot(snapshot);
        final CaseInfo caseInfo = ci != null ? ci : caseService.getCaseInfo(plan.getCaseId());

        // SPI 硬超时 50ms；异常/超时上抛 → NACK（穷尽是生命周期关键节点，不可丢）
        ExhaustionResult result =
                spiInvoker.call(
                        SpiType.EXHAUSTION_POLICY,
                        () -> exhaustionPolicy.handle(plan, caseInfo, snapshot));
        switch (result.getAction()) {
            case REBUILD:
                // 将旧计划排除出活跃唯一键后再插入新计划；三步同一事务，失败整体回滚。
                planRepository.markRenewalPending(planId);
                if (createPlanForStage(
                        plan.getCaseId(), plan.getStage(), caseInfo, snapshot, null, true)) {
                    planRepository.updatePlanStatus(
                            planId, PlanStatus.PLAN_COMPLETED, null); // 新计划落库后再完成旧计划
                    log.info("[exhausted] plan {} REBUILD same stage {}", planId, plan.getStage());
                    return noEvents();
                }
                // Factory 0 步（本阶段已无未来槽）不是故障：收口旧计划，有下一档则升档。
                planRepository.updatePlanStatus(planId, PlanStatus.PLAN_COMPLETED, null);
                if ((caseInfo != null && isCeased(caseInfo))
                        || (snapshot != null
                                && snapshot.getCaseContext() != null
                                && "CEASED"
                                        .equalsIgnoreCase(
                                                snapshot.getCaseContext().getCollectionStatus()))) {
                    log.info(
                            "[exhausted] plan {} REBUILD produced no successor (ceased), COMPLETE",
                            planId);
                    return noEvents();
                }
                Stage next = plan.getStage() == null ? null : plan.getStage().next();
                if (next != null) {
                    log.info(
                            "[exhausted] plan {} REBUILD produced no successor, ESCALATE → {}",
                            planId,
                            next);
                    return single(enqueued(EngineEvents.stageEscalated(plan, next.name())));
                }
                log.info("[exhausted] plan {} REBUILD produced no successor, COMPLETE", planId);
                return noEvents();
            case ESCALATE:
                planRepository.updatePlanStatus(planId, PlanStatus.PLAN_COMPLETED, null);
                log.info("[exhausted] plan {} ESCALATE → {}", planId, result.getTargetStage());
                return single(
                        enqueued(
                                EngineEvents.stageEscalated(plan, result.getTargetStage().name())));
            case COMPLETE:
            default:
                planRepository.updatePlanStatus(planId, PlanStatus.PLAN_COMPLETED, null);
                log.info("[exhausted] plan {} COMPLETE (stop)", planId);
                return noEvents();
        }
    }

    // ───────────────────────── PTP 到期（§2.6，Phase 2 预留） ─────────────────────────

    /**
     * Phase 2 预留：Phase 1 引擎不消费 PTP_EXPIRED（Dispatcher 未订阅，核心引擎规格 §2.6）。 方法体保留作 Phase 2 前向兼容，Phase 1
     * 不会被事件总线触发。
     */
    @Transactional
    public List<CollectionEvent> onPtpExpired(CollectionEvent event) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        // 实时查 DB，不用快照
        if (caseService.isRepaid(caseId)) {
            List<ContactPlan> active = planRepository.findActivePlansByCase(caseId);
            for (ContactPlan p : active) {
                planRepository.findPlanWithLock(p.getId());
                planRepository.updatePlanStatus(
                        p.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
            }
            log.info("[ptpExpired] case {} repaid → compensating cancel", caseId);
            return noEvents();
        }
        ContactPlan active = firstActive(planRepository.findActivePlansByCase(caseId));
        if (active != null) {
            return noEvents(); // 计划仍在执行，正常流程继续
        }
        // 无活跃计划 → 续建（复用穷尽策略）
        ContactPlan last = planRepository.getLastCompletedPlan(caseId);
        if (last == null) {
            return noEvents();
        }
        // 决策 B：carry-forward last 计划快照，缺失降级 CaseService。
        ContextSnapshot carried = snapshotFromPlan(last);
        final ContextSnapshot snapshot =
                carried != null ? carried : caseService.getContextSnapshot(caseId);
        CaseInfo ci = caseInfoFromSnapshot(snapshot);
        final CaseInfo caseInfo = ci != null ? ci : caseService.getCaseInfo(caseId);
        ExhaustionResult result =
                spiInvoker.call(
                        SpiType.EXHAUSTION_POLICY,
                        () -> exhaustionPolicy.handle(last, caseInfo, snapshot));
        if (result.getAction() == ExhaustionAction.REBUILD) {
            createPlanForStage(caseId, last.getStage(), caseInfo, snapshot, null, true);
            log.info("[ptpExpired] case {} broken → rebuild stage {}", caseId, last.getStage());
        }
        return noEvents();
    }

    // ───────────────────────── 私有：计划创建复用（§2.2） ─────────────────────────

    private boolean createPlanForStage(
            Long caseId,
            Stage stage,
            CaseInfo providedCaseInfo,
            ContextSnapshot providedSnapshot,
            Long excludedActivePlanId) {
        return createPlanForStage(
                caseId, stage, providedCaseInfo, providedSnapshot, excludedActivePlanId, false);
    }

    private boolean createPlanForStage(
            Long caseId,
            Stage stage,
            CaseInfo providedCaseInfo,
            ContextSnapshot providedSnapshot,
            Long excludedActivePlanId,
            boolean rebuildSameStage) {
        if (stage == null) {
            log.warn("[create] caseId={} stage is null, skip", caseId);
            metrics.planCreation(null, "NO_STAGE");
            return false;
        }
        ContactPlan activePlan = planRepository.findActivePlanByCaseAndStage(caseId, stage);
        if (activePlan != null && !activePlan.getId().equals(excludedActivePlanId)) {
            log.info(
                    "[create] caseId={} stage={} already has active plan, idempotent skip",
                    caseId,
                    stage);
            metrics.planCreation(stage.name(), "IDEMPOTENT_SKIP");
            return false; // 单活跃计划约束 / 幂等
        }
        // 决策 B：优先用传入的 caseInfo / snapshot（事件 payload / carry-forward）；
        // 缺失时降级 CaseService（仅兜底 / 对账，非主链路）。
        CaseInfo caseInfo =
                providedCaseInfo != null ? providedCaseInfo : caseService.getCaseInfo(caseId);
        if (caseInfo != null && isCeased(caseInfo)) {
            log.info("[create] caseId={} is CEASED, skip PlanFactory.create", caseId);
            metrics.planCreation(stage.name(), "CEASED");
            return false;
        }
        ContextSnapshot snapshot =
                providedSnapshot != null
                        ? providedSnapshot
                        : caseService.getContextSnapshot(caseId);
        if (snapshot != null
                && snapshot.getCaseContext() != null
                && "CEASED".equalsIgnoreCase(snapshot.getCaseContext().getCollectionStatus())) {
            log.info("[create] caseId={} snapshot collectionStatus=CEASED, skip", caseId);
            metrics.planCreation(stage.name(), "CEASED");
            return false;
        }

        // SPI 硬超时 50ms；异常/超时上抛 → NACK 延迟重消费（丢失整个计划 = 案件完全无触达，核心引擎规格 §4.1）
        ContactPlan plan =
                spiInvoker.call(
                        SpiType.PLAN_FACTORY, () -> planFactory.create(caseInfo, stage, snapshot));
        if (plan == null) {
            log.info(
                    "[create] PlanFactory returned null for case {} stage {}, no plan",
                    caseId,
                    stage);
            metrics.planCreation(stage.name(), "FACTORY_RETURNED_NULL");
            return false;
        }
        plan.setCaseId(caseId);
        if (plan.getUserId() == null) {
            plan.setUserId(caseInfo != null ? caseInfo.getUserId() : caseId);
        }
        plan.setStage(stage);
        plan.setStatus(PlanStatus.PENDING);
        plan.setContextSnapshot(JsonUtil.toJson(snapshot));
        plan.setTotalSteps(plan.getSteps().size());
        plan.setCurrentStep(0);
        plan.setIdempotencyKey(caseId + ":" + stage + ":" + System.currentTimeMillis());

        // Factory 预写的 DayBlock 绝对槽位必须保留；相对 delayMinutes 仅作 local/L4 回退。
        if (!plan.getSteps().isEmpty()) {
            ContactPlanStep first = plan.getSteps().get(0);
            first.setStepOrder(1);
            if (first.getTriggerTime() == null) {
                first.setTriggerTime(
                        LocalDateTime.now(PHT).plusMinutes(Math.max(0, first.getDelayMinutes())));
            }
            if (rebuildSameStage) {
                clampFirstStepToNextPhtMorning(first);
            }
            first.setStatus(StepStatus.PENDING);
        }
        planRepository.savePlan(plan);
        log.info(
                "[create] plan {} created for case {} stage {} ({} steps)",
                plan.getId(),
                caseId,
                stage,
                plan.getTotalSteps());
        metrics.planCreation(stage.name(), "CREATED");
        return true;
    }

    /**
     * CONNECT_AND_STOP：真人接通后，把同日尚未执行的 AI_CALL 标 SKIPPED，避免下午补呼。 只处理 PENDING；已在拨打中的 EXECUTING
     * 不打断。SMS/PUSH/EMAIL 同日步骤保留。
     */
    private void skipSameDayPendingAiCalls(Long planId, ContactPlanStep answered) {
        List<ContactPlanStep> steps = planRepository.findStepsByPlan(planId);
        if (steps == null || steps.isEmpty()) {
            return;
        }
        LocalDate answeredDay = stepDayPht(answered);
        if (answeredDay == null) {
            return;
        }
        for (ContactPlanStep candidate : steps) {
            if (candidate.getStepOrder() <= answered.getStepOrder()) {
                continue;
            }
            if (candidate.getChannelType() != ChannelType.AI_CALL) {
                continue;
            }
            if (candidate.getStatus() != StepStatus.PENDING) {
                continue;
            }
            LocalDate candidateDay = stepDayPht(candidate);
            if (candidateDay == null || !answeredDay.equals(candidateDay)) {
                continue;
            }
            planRepository.updateStepStatus(
                    candidate.getId(), StepStatus.SKIPPED, ContactResult.SKIPPED);
            log.info(
                    "[advance] CONNECT_AND_STOP skip plan {} step {} (same-day AI_CALL after ANSWERED)",
                    planId,
                    candidate.getId());
        }
    }

    private static LocalDate stepDayPht(ContactPlanStep step) {
        LocalDateTime when = step.getOriginalTriggerTime();
        if (when == null) {
            when = step.getTriggerTime();
        }
        if (when == null) {
            when = step.getCompletedAt();
        }
        if (when == null) {
            when = step.getExecutedAt();
        }
        return when == null ? null : when.atZone(ZoneId.of("Asia/Manila")).toLocalDate();
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private com.collection.common.dto.ExecutionContext buildLiteContext(
            ContactPlan plan, ContactPlanStep step) {
        return com.collection.common.dto.ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(
                        JsonUtil.fromJson(plan.getContextSnapshot(), ContextSnapshot.class))
                .recentTimeline(new ArrayList<>())
                .build();
    }

    private StepResult toStepResult(ContactPlanStep step) {
        ContactResult cr =
                step.getResult() != null ? step.getResult() : ContactResult.SENT_NO_RESPONSE;
        boolean success = cr != ContactResult.FAILED;
        return StepResult.builder().success(success).contactResult(cr).build();
    }

    /** 异步回调和超时的唯一 timeline 落数点。与步骤状态更新处于同一事务， 重复回调由 TimelineRepository 按 providerMsgId 幂等更新。 */
    private void writeCallbackTimeline(
            ContactPlan plan,
            ContactPlanStep step,
            ContactResult result,
            String providerMsgId,
            String errorCode) {
        ContactRecord record = new ContactRecord();
        record.setCaseId(plan.getCaseId());
        record.setUserId(plan.getUserId());
        record.setPlanId(plan.getId());
        record.setStepId(step.getId());
        record.setAttemptKey(plan.getId() + ":" + step.getStepOrder() + ":" + step.getRetryCount());
        record.setChannel(step.getChannelType());
        record.setDirection(Direction.OUT);
        record.setTemplateId(step.getTemplateId());
        record.setResult(result);
        record.setProviderMsgId(providerMsgId);
        record.setProviderCallback(
                errorCode == null
                        ? null
                        : JsonUtil.toJson(
                                java.util.Collections.singletonMap("errorCode", errorCode)));
        record.setSource(DataSource.SYSTEM);
        timelineRepository.writeTimeline(record);
    }

    private ContactResult mapCallbackToResult(String raw) {
        if (raw == null) {
            return ContactResult.FAILED;
        }
        try {
            return ContactResult.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Unknown provider outcomes must fail closed. Treating a vendor-specific
            // value (for example VOICEMAIL) as ANSWERED would falsely record contact.
            return ContactResult.FAILED;
        }
    }

    private boolean isCeased(CaseInfo caseInfo) {
        return "CEASED".equalsIgnoreCase(caseInfo.getCaseStatus());
    }

    /**
     * 仍在催收窗口：缺快照时偏向穷尽（避免安静停催）。仅 CEASED / SETTLED / 结清视为已离开催收。
     */
    private boolean stillInCollection(ContactPlan plan) {
        ContextSnapshot snap = snapshotFromPlan(plan);
        if (snap != null && snap.getCaseContext() != null) {
            String status = snap.getCaseContext().getCollectionStatus();
            if (status != null
                    && ("CEASED".equalsIgnoreCase(status) || "SETTLED".equalsIgnoreCase(status))) {
                return false;
            }
        }
        CaseInfo info = caseInfoFromSnapshot(snap);
        return info == null || (!isCeased(info) && !info.isRepaid());
    }

    /** REBUILD 首步不得早于次日 08:00 PHT；Factory 已排更晚则保留。 */
    private void clampFirstStepToNextPhtMorning(ContactPlanStep first) {
        LocalDateTime floor = LocalDate.now(PHT).plusDays(1).atTime(COLLECTION_WINDOW_START_HOUR, 0);
        if (first.getTriggerTime() == null || first.getTriggerTime().isBefore(floor)) {
            first.setTriggerTime(floor);
        }
        if (first.getOriginalTriggerTime() == null
                || first.getOriginalTriggerTime().isBefore(first.getTriggerTime())) {
            first.setOriginalTriggerTime(first.getTriggerTime());
        }
        log.info(
                "[create] REBUILD first step trigger_time clamped to {} PHT",
                first.getTriggerTime());
    }

    // ───────────── 决策 B：快照来源 = 事件 payload / carry-forward（不读旧库） ─────────────

    /** 事件是否携带快照字段（决策 B）。以 dpd / phone / 余额 任一存在为标记。 */
    private boolean hasSnapshotPayload(CollectionEvent event) {
        return event.has(CollectionEvent.DPD)
                || event.has(CollectionEvent.PHONE)
                || event.has(CollectionEvent.EMAIL)
                || event.has(CollectionEvent.TOTAL_OUTSTANDING);
    }

    /** 据 CASE_INGESTED payload 组装 ContextSnapshot（决策 B；映射对齐 RealCaseService 口径）。 */
    private ContextSnapshot buildSnapshotFromEvent(CollectionEvent event, Stage stage) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        Long userId = event.getLong(CollectionEvent.USER_ID);
        if (userId == null) {
            userId = caseId;
        }
        Integer dpdObj = event.getInt(CollectionEvent.DPD);
        int dpd = dpdObj == null ? 0 : dpdObj;

        CaseContext ctx = new CaseContext();
        ctx.setCaseId(caseId);
        ctx.setUserId(userId);
        ctx.setDpd(dpd);
        ctx.setStage(stage != null ? stage : Stage.fromDpd(dpd));
        ctx.setProduct(event.getString(CollectionEvent.PRODUCT));
        ctx.setOverdueAmount(event.getBigDecimal(CollectionEvent.OVERDUE_AMOUNT));
        ctx.setTotalOutstanding(event.getBigDecimal(CollectionEvent.TOTAL_OUTSTANDING));
        ctx.setPenaltyAmount(event.getBigDecimal(CollectionEvent.PENALTY_AMOUNT));
        ctx.setDueDate(parseDate(event.getString(CollectionEvent.DUE_DATE)));
        ctx.setUpcomingAmount(event.getBigDecimal(CollectionEvent.UPCOMING_AMOUNT));
        ctx.setNextDueDate(parseDate(event.getString(CollectionEvent.NEXT_DUE_DATE)));
        ctx.setRepaymentUrl(repaymentUrlTemplate.replace("{caseId}", String.valueOf(caseId)));
        ctx.setComplaintFrozen(false);
        // D+91 完全停催：collectionStatus=CEASED；createPlanForStage / PlanFactory 据此拒建。
        ctx.setCollectionStatus(dpd >= 91 ? "CEASED" : "ACTIVE");

        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setName(event.getString(CollectionEvent.NAME));
        basic.setPrimaryPhone(event.getString(CollectionEvent.PHONE));
        basic.setEmail(event.getString(CollectionEvent.EMAIL));
        String language = event.getString(CollectionEvent.LANGUAGE);
        basic.setLanguage(language == null || language.trim().isEmpty() ? "en" : language);
        profile.setBasic(basic);
        UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();
        device.setJpushToken(event.getString(CollectionEvent.JPUSH_TOKEN));
        profile.setDevice(device);

        ContactHistory history = new ContactHistory();
        history.setStageEntryDate(LocalDate.now());

        ContextSnapshot snap = new ContextSnapshot();
        snap.setCaseContext(ctx);
        snap.setUserProfile(profile);
        snap.setContactHistory(history);
        snap.setSnapshotTime(LocalDateTime.now());
        snap.setSnapshotVersion("event-v1");
        return snap;
    }

    /** 由快照派生 CaseInfo（CEASED 判定走 caseStatus=collectionStatus）。 */
    private CaseInfo caseInfoFromSnapshot(ContextSnapshot snapshot) {
        if (snapshot == null || snapshot.getCaseContext() == null) {
            return null;
        }
        CaseContext ctx = snapshot.getCaseContext();
        CaseInfo info = new CaseInfo();
        info.setCaseId(ctx.getCaseId());
        info.setUserId(ctx.getUserId());
        info.setDpd(ctx.getDpd());
        info.setStage(ctx.getStage());
        info.setProduct(ctx.getProduct());
        info.setCaseStatus(ctx.getCollectionStatus());
        info.setTotalOutstanding(ctx.getTotalOutstanding());
        info.setDueDate(ctx.getDueDate());
        info.setFrozen(ctx.isComplaintFrozen());
        // repaid 由实时守卫 PreFlightChecker 负责（[接入 §3.1]），快照不承载实时态
        info.setRepaid(false);
        return info;
    }

    /** carry-forward：从计划行反序列化已冻结快照（续建 / 升档复用，不回读旧库）。 */
    private ContextSnapshot snapshotFromPlan(ContactPlan plan) {
        if (plan == null || plan.getContextSnapshot() == null) {
            return null;
        }
        try {
            return JsonUtil.fromJson(plan.getContextSnapshot(), ContextSnapshot.class);
        } catch (Exception e) {
            log.warn(
                    "[carry-forward] deserialize plan {} snapshot failed: {}",
                    plan.getId(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * carry-forward 时按事件刷新目标 stage（§4.4 升档），并在事件携带日变字段时一并刷新 dpd / 余额。
     *
     * <p>日变字段可选：仅日切发布的 STAGE_CHANGED 会带，缺省则保持旧值。渲染时刻的真值另由 步骤② 的实时读覆盖（见 {@code
     * StepExecutionOrchestrator#refreshVolatileFields}），此处只是让快照列本身不至于长期陈旧。
     */
    private ContextSnapshot withRefreshedFields(
            ContextSnapshot snap, Stage stage, Integer dpd, java.math.BigDecimal totalOutstanding) {
        if (snap == null || snap.getCaseContext() == null) {
            return snap;
        }
        CaseContext ctx = snap.getCaseContext();
        if (stage != null) {
            ctx.setStage(stage);
        }
        if (dpd != null) {
            ctx.setDpd(dpd);
        }
        if (totalOutstanding != null) {
            ctx.setTotalOutstanding(totalOutstanding);
        }
        return snap;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            String t = s.trim();
            return LocalDate.parse(t.length() >= 10 ? t.substring(0, 10) : t);
        } catch (Exception e) {
            log.warn("[ingest] unparseable date '{}': {}", s, e.getMessage());
            return null;
        }
    }

    private Stage parseStage(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Stage.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ContactPlan firstActive(List<ContactPlan> plans) {
        return plans == null || plans.isEmpty() ? null : plans.get(0);
    }

    /**
     * 计划仍停在「等本步回调/观察期」时才允许 STEP_COMPLETED 推进。 STEP_SCHEDULED 表示后续 due 已经把日程推走，再推进会把 current_step
     * 拽回旧步。
     */
    private boolean planAwaitsAsyncOutcome(ContactPlan plan) {
        return plan.getStatus() == PlanStatus.STEP_EXECUTING
                || plan.getStatus() == PlanStatus.STEP_WAITING;
    }

    private List<CollectionEvent> noEvents() {
        return new ArrayList<>();
    }

    /**
     * 在当前事务内把事件写入发件箱，再交给调用方于提交后即时发布（核心引擎规格 §7.2）。
     *
     * <p>不入箱的话，提交后发布一旦失败，原事件重投时状态已是终态、这些分支不会被再次走到， 事件就永久消失、计划静默停摆。
     */
    private CollectionEvent enqueued(CollectionEvent event) {
        if (outboxEventSink != null) {
            outboxEventSink.enqueue(event);
        }
        return event;
    }

    private List<CollectionEvent> single(CollectionEvent e) {
        List<CollectionEvent> list = new ArrayList<>();
        list.add(e);
        return list;
    }
}
