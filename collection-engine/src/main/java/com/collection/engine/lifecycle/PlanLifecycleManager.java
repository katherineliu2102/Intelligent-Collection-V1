package com.collection.engine.lifecycle;

import com.collection.common.dto.ExhaustionResult;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.*;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.*;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.service.CaseService;
import com.collection.common.service.PredictiveDialerService;
import com.collection.common.spi.AdvancementPolicy;
import com.collection.common.spi.ExhaustionPolicy;
import com.collection.common.spi.PlanFactory;
import com.collection.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划级生命周期管理。对应核心引擎规格 §2。
 *
 * <p>每个 onXxx 方法即一次"短事务（SELECT FOR UPDATE → 校验终态 → 状态前置 → COMMIT）"。
 * 方法返回需在<b>提交后</b>发布的事件列表（核心引擎规格："事务外发布，保证写入已落盘"），
 * 由 {@link EventConsumerDispatcher} 在方法返回后投递。
 */
@Component
public class PlanLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(PlanLifecycleManager.class);

    @Resource
    private ContactPlanRepository planRepository;
    @Resource
    private CaseService caseService;
    @Resource
    private PlanFactory planFactory;
    @Resource
    private AdvancementPolicy advancementPolicy;
    @Resource
    private ExhaustionPolicy exhaustionPolicy;
    @Resource
    private PredictiveDialerService predictiveDialerService;

    // ───────────────────────── 计划创建（§2.2） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onCaseIngested(CollectionEvent event) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        Stage stage = parseStage(event.getString(CollectionEvent.STAGE));
        if (stage == null) {
            CaseInfo info = caseService.getCaseInfo(caseId);
            stage = info != null ? info.getStage() : null;
        }
        createPlanForStage(caseId, stage);
        return noEvents();
    }

    @Transactional
    public List<CollectionEvent> onStageChanged(CollectionEvent event) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        Stage newStage = parseStage(event.getString(CollectionEvent.STAGE));

        List<ContactPlan> oldPlans = planRepository.findActivePlansByCase(caseId);
        oldPlans.sort((a, b) -> Long.compare(a.getId(), b.getId())); // 按 id 升序加锁防死锁
        for (ContactPlan p : oldPlans) {
            if (p.getStage() != newStage) {
                planRepository.findPlanWithLock(p.getId());
                planRepository.updatePlanStatus(p.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.STAGE_UPGRADE);
                log.info("[stageChanged] cancelled old plan {} ({}→{})", p.getId(), p.getStage(), newStage);
            }
        }
        createPlanForStage(caseId, newStage);
        return noEvents();
    }

    // ───────────────────────── 中断（§2.4） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onCaseCeased(CollectionEvent event) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        List<ContactPlan> plans = planRepository.findActivePlansByCase(caseId);
        plans.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (ContactPlan p : plans) {
            planRepository.findPlanWithLock(p.getId());
            planRepository.updatePlanStatus(p.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.CEASED);
            log.info("[caseCeased] cancelled plan {} (CEASED)", p.getId());
        }
        return noEvents();
    }

    @Transactional
    public List<CollectionEvent> onRepaymentReceived(CollectionEvent event) {
        Long userId = event.getLong(CollectionEvent.USER_ID);
        List<ContactPlan> plans = planRepository.findActivePlansByUser(userId);
        plans.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (ContactPlan p : plans) {
            planRepository.findPlanWithLock(p.getId());
            planRepository.updatePlanStatus(p.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
            log.info("[repayment] cancelled plan {} (REPAID)", p.getId());
        }
        try {
            predictiveDialerService.filterRepaidUser(userId);
        } catch (Exception e) {
            // 告警 + 继续：计划已取消是核心目标（核心引擎规格 §5.1）
            log.warn("[repayment] filterRepaidUser failed for user {}: {}", userId, e.getMessage());
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

        if (plan.getStatus() == PlanStatus.PENDING || plan.getStatus() == PlanStatus.STEP_SCHEDULED
                || plan.getStatus() == PlanStatus.STEP_EXECUTING) {
            // 状态前置（PENDING/SCHEDULED 首次执行；EXECUTING 为退避重试再触发）
            planRepository.updatePlanStatus(planId, PlanStatus.STEP_EXECUTING, null);
            planRepository.markStarted(planId);
            // 清空 trigger_time 防止扫描器在处理窗口内重复投递（幂等锁亦兜底）
            planRepository.updateStepTriggerTime(stepId, null, StepStatus.EXECUTING);
            plan.setStatus(PlanStatus.STEP_EXECUTING);
            return StepDuePreparation.toExecute(plan, step);
        }

        if (plan.getStatus() == PlanStatus.STEP_WAITING) {
            // 观察期到期结转
            ContactResult result = step.getResult() != null ? step.getResult() : ContactResult.SENT_NO_RESPONSE;
            planRepository.updateStepStatus(stepId, StepStatus.COMPLETED, result);
            StepDuePreparation prep = StepDuePreparation.noop();
            prep.getEvents().add(stepCompletedEvent(plan, step));
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
        ContactPlanStep completed = planRepository.findStepById(stepId);
        StepResult stepResult = toStepResult(completed);

        AdvancementDecision decision = advancementPolicy.decide(
                buildLiteContext(plan, completed), stepResult);

        switch (decision) {
            case ADVANCE_NEXT:
                ContactPlanStep next = planRepository.getNextStep(planId, completed.getStepOrder());
                if (next == null) {
                    log.info("[advance] plan {} no next step → PLAN_EXHAUSTED", planId);
                    return single(planExhaustedEvent(plan));
                }
                LocalDateTime triggerTime = LocalDateTime.now().plusMinutes(Math.max(0, next.getDelayMinutes()));
                planRepository.updateStepTriggerTime(next.getId(), triggerTime, StepStatus.PENDING);
                planRepository.updateCurrentStep(planId, next.getStepOrder());
                planRepository.updatePlanStatus(planId, PlanStatus.STEP_SCHEDULED, null);
                log.info("[advance] plan {} → STEP_SCHEDULED, next step {} at {}", planId, next.getId(), triggerTime);
                return noEvents();

            case PLAN_COMPLETED:
                planRepository.updatePlanStatus(planId, PlanStatus.PLAN_COMPLETED, null);
                log.info("[advance] plan {} → PLAN_COMPLETED", planId);
                return noEvents();

            case PLAN_EXHAUSTED:
            default:
                return single(planExhaustedEvent(plan));
        }
    }

    // ───────────────────────── 异步回调（§2.3.3） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onChannelCallback(CollectionEvent event) {
        Long planId = event.getLong(CollectionEvent.PLAN_ID);
        Long stepId = event.getLong(CollectionEvent.STEP_ID);

        ContactPlan plan = planRepository.findPlanWithLock(planId);
        if (plan == null || plan.getStatus() != PlanStatus.STEP_EXECUTING) {
            return noEvents(); // 非执行态（已处理/已取消），静默吸收
        }
        ContactPlanStep step = planRepository.findStepById(stepId);
        ContactResult result = mapCallbackToResult(event.getString("result"));
        planRepository.updateStepStatus(stepId, StepStatus.COMPLETED, result);
        log.info("[callback] plan {} step {} result {}", planId, stepId, result);
        return single(stepCompletedEvent(plan, step));
    }

    // ───────────────────────── 回调超时兜底（§2.3.4） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onCallbackTimeout(CollectionEvent event) {
        Long planId = event.getLong(CollectionEvent.PLAN_ID);
        Long stepId = event.getLong(CollectionEvent.STEP_ID);

        ContactPlan plan = planRepository.findPlanWithLock(planId);
        if (plan == null || plan.getStatus() != PlanStatus.STEP_EXECUTING) {
            return noEvents(); // 回调已正常处理
        }
        ContactPlanStep step = planRepository.findStepById(stepId);
        planRepository.updateStepStatus(stepId, StepStatus.FAILED, ContactResult.FAILED);
        log.info("[callbackTimeout] plan {} step {} → FAILED", planId, stepId);
        return single(stepCompletedEvent(plan, step));
    }

    // ───────────────────────── 穷尽续建（§2.5） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onPlanExhausted(CollectionEvent event) {
        Long planId = event.getLong(CollectionEvent.PLAN_ID);
        ContactPlan plan = planRepository.findPlanWithLock(planId);
        if (plan == null || plan.isTerminal()) {
            return noEvents();
        }
        CaseInfo caseInfo = caseService.getCaseInfo(plan.getCaseId());
        ContextSnapshot snapshot = caseService.getContextSnapshot(plan.getCaseId());

        ExhaustionResult result = exhaustionPolicy.handle(plan, caseInfo, snapshot);
        switch (result.getAction()) {
            case REBUILD:
                planRepository.updatePlanStatus(planId, PlanStatus.PLAN_COMPLETED, null); // 旧计划正常完成
                createPlanForStage(plan.getCaseId(), plan.getStage());
                log.info("[exhausted] plan {} REBUILD same stage {}", planId, plan.getStage());
                return noEvents();
            case ESCALATE:
                planRepository.updatePlanStatus(planId, PlanStatus.PLAN_COMPLETED, null);
                log.info("[exhausted] plan {} ESCALATE → {}", planId, result.getTargetStage());
                return single(CollectionEvent.of(EventType.STAGE_CHANGED)
                        .with(CollectionEvent.CASE_ID, plan.getCaseId())
                        .with(CollectionEvent.STAGE, result.getTargetStage().name()));
            case COMPLETE:
            default:
                planRepository.updatePlanStatus(planId, PlanStatus.PLAN_COMPLETED, null);
                log.info("[exhausted] plan {} COMPLETE (stop)", planId);
                return noEvents();
        }
    }

    // ───────────────────────── PTP 到期（§2.6） ─────────────────────────

    @Transactional
    public List<CollectionEvent> onPtpExpired(CollectionEvent event) {
        Long caseId = event.getLong(CollectionEvent.CASE_ID);
        // 实时查 DB，不用快照
        if (caseService.isRepaid(caseId)) {
            List<ContactPlan> active = planRepository.findActivePlansByCase(caseId);
            for (ContactPlan p : active) {
                planRepository.findPlanWithLock(p.getId());
                planRepository.updatePlanStatus(p.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
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
        CaseInfo caseInfo = caseService.getCaseInfo(caseId);
        ContextSnapshot snapshot = caseService.getContextSnapshot(caseId);
        ExhaustionResult result = exhaustionPolicy.handle(last, caseInfo, snapshot);
        if (result.getAction() == ExhaustionAction.REBUILD) {
            createPlanForStage(caseId, last.getStage());
            log.info("[ptpExpired] case {} broken → rebuild stage {}", caseId, last.getStage());
        }
        return noEvents();
    }

    // ───────────────────────── 私有：计划创建复用（§2.2） ─────────────────────────

    private void createPlanForStage(Long caseId, Stage stage) {
        if (stage == null) {
            log.warn("[create] caseId={} stage is null, skip", caseId);
            return;
        }
        if (planRepository.findActivePlanByCaseAndStage(caseId, stage) != null) {
            log.info("[create] caseId={} stage={} already has active plan, idempotent skip", caseId, stage);
            return; // 单活跃计划约束 / 幂等
        }
        CaseInfo caseInfo = caseService.getCaseInfo(caseId);
        if (caseInfo != null && isCeased(caseInfo)) {
            log.info("[create] caseId={} is CEASED, skip PlanFactory.create", caseId);
            return;
        }
        ContextSnapshot snapshot = caseService.getContextSnapshot(caseId);
        if (snapshot != null && snapshot.getCaseContext() != null
                && "CEASED".equalsIgnoreCase(snapshot.getCaseContext().getCollectionStatus())) {
            log.info("[create] caseId={} snapshot collectionStatus=CEASED, skip", caseId);
            return;
        }

        ContactPlan plan = planFactory.create(caseInfo, stage, snapshot); // SPI：异常→NACK
        if (plan == null) {
            log.info("[create] PlanFactory returned null for case {} stage {}, no plan", caseId, stage);
            return;
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

        // 首步设置 trigger_time（相对计划创建时间）；其余步骤等推进时再注册
        if (!plan.getSteps().isEmpty()) {
            ContactPlanStep first = plan.getSteps().get(0);
            first.setStepOrder(1);
            first.setTriggerTime(LocalDateTime.now().plusMinutes(Math.max(0, first.getDelayMinutes())));
            first.setStatus(StepStatus.PENDING);
        }
        planRepository.savePlan(plan);
        log.info("[create] plan {} created for case {} stage {} ({} steps)",
                plan.getId(), caseId, stage, plan.getTotalSteps());
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private com.collection.common.dto.ExecutionContext buildLiteContext(ContactPlan plan, ContactPlanStep step) {
        return com.collection.common.dto.ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(JsonUtil.fromJson(plan.getContextSnapshot(), ContextSnapshot.class))
                .recentTimeline(new ArrayList<>())
                .build();
    }

    private StepResult toStepResult(ContactPlanStep step) {
        ContactResult cr = step.getResult() != null ? step.getResult() : ContactResult.SENT_NO_RESPONSE;
        boolean success = cr != ContactResult.FAILED;
        return StepResult.builder().success(success).contactResult(cr).build();
    }

    private CollectionEvent stepCompletedEvent(ContactPlan plan, ContactPlanStep step) {
        return CollectionEvent.of(EventType.STEP_COMPLETED)
                .with(CollectionEvent.CASE_ID, plan.getCaseId())
                .with(CollectionEvent.USER_ID, plan.getUserId())
                .with(CollectionEvent.PLAN_ID, plan.getId())
                .with(CollectionEvent.STEP_ID, step.getId());
    }

    private CollectionEvent planExhaustedEvent(ContactPlan plan) {
        return CollectionEvent.of(EventType.PLAN_EXHAUSTED)
                .with(CollectionEvent.CASE_ID, plan.getCaseId())
                .with(CollectionEvent.PLAN_ID, plan.getId());
    }

    private ContactResult mapCallbackToResult(String raw) {
        if (raw == null) {
            return ContactResult.ANSWERED;
        }
        try {
            return ContactResult.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ContactResult.ANSWERED;
        }
    }

    private boolean isCeased(CaseInfo caseInfo) {
        return "CEASED".equalsIgnoreCase(caseInfo.getCaseStatus());
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

    private List<CollectionEvent> noEvents() {
        return new ArrayList<>();
    }

    private List<CollectionEvent> single(CollectionEvent e) {
        List<CollectionEvent> list = new ArrayList<>();
        list.add(e);
        return list;
    }
}
