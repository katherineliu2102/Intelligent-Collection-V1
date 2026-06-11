package com.collection.engine.lifecycle;

import com.collection.common.dto.ExhaustionResult;
import com.collection.common.enums.AdvancementDecision;
import com.collection.common.enums.CancelReason;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.EventType;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.service.CaseService;
import com.collection.common.service.PredictiveDialerService;
import com.collection.common.spi.AdvancementPolicy;
import com.collection.common.spi.ExhaustionPolicy;
import com.collection.common.spi.PlanFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlanLifecycleManager 状态机纯逻辑单测（核心引擎规格 §2）。全 mock，不连库。
 * 覆盖测试矩阵 #15-27。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanLifecycleManagerTest {

    private static final long PLAN_ID = 100L;
    private static final long STEP_ID = 200L;
    private static final long NEXT_STEP_ID = 201L;
    private static final long CASE_ID = 1002L;
    private static final long USER_ID = 9001L;

    @Mock private ContactPlanRepository planRepository;
    @Mock private CaseService caseService;
    @Mock private PlanFactory planFactory;
    @Mock private AdvancementPolicy advancementPolicy;
    @Mock private ExhaustionPolicy exhaustionPolicy;
    @Mock private PredictiveDialerService predictiveDialerService;

    @InjectMocks private PlanLifecycleManager manager;

    private ContactPlan plan;
    private ContactPlanStep step;

    @BeforeEach
    void setUp() {
        plan = newPlan(PLAN_ID, PlanStatus.STEP_EXECUTING, Stage.S2);
        step = newStep(STEP_ID, 1, ChannelType.SMS, StepStatus.EXECUTING);
    }

    private ContactPlan newPlan(long id, PlanStatus status, Stage stage) {
        ContactPlan p = new ContactPlan();
        p.setId(id);
        p.setCaseId(CASE_ID);
        p.setUserId(USER_ID);
        p.setStage(stage);
        p.setStatus(status);
        return p;
    }

    private ContactPlanStep newStep(long id, int order, ChannelType ch, StepStatus status) {
        ContactPlanStep s = new ContactPlanStep();
        s.setId(id);
        s.setPlanId(PLAN_ID);
        s.setStepOrder(order);
        s.setChannelType(ch);
        s.setStatus(status);
        return s;
    }

    private CollectionEvent stepEvent(EventType type) {
        return CollectionEvent.of(type)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.USER_ID, USER_ID)
                .with(CollectionEvent.PLAN_ID, PLAN_ID)
                .with(CollectionEvent.STEP_ID, STEP_ID);
    }

    // ───────────────────────── onStepCompleted（#15、#16） ─────────────────────────

    @Test
    @DisplayName("#15 推进=ADVANCE_NEXT 且有下一步 → 注册下一步 + STEP_SCHEDULED")
    void onStepCompleted_advanceNext_withNext() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(advancementPolicy.decide(any(), any())).thenReturn(AdvancementDecision.ADVANCE_NEXT);
        ContactPlanStep next = newStep(NEXT_STEP_ID, 2, ChannelType.PUSH, StepStatus.PENDING);
        next.setDelayMinutes(60);
        when(planRepository.getNextStep(PLAN_ID, 1)).thenReturn(next);

        List<CollectionEvent> out = manager.onStepCompleted(stepEvent(EventType.STEP_COMPLETED));

        verify(planRepository).updateStepTriggerTime(eq(NEXT_STEP_ID), any(), eq(StepStatus.PENDING));
        verify(planRepository).updateCurrentStep(PLAN_ID, 2);
        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.STEP_SCHEDULED, null);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("#16 推进=ADVANCE_NEXT 但无下一步 → 发布 PLAN_EXHAUSTED")
    void onStepCompleted_advanceNext_noNext_exhausted() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(advancementPolicy.decide(any(), any())).thenReturn(AdvancementDecision.ADVANCE_NEXT);
        when(planRepository.getNextStep(PLAN_ID, 1)).thenReturn(null);

        List<CollectionEvent> out = manager.onStepCompleted(stepEvent(EventType.STEP_COMPLETED));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEventType()).isEqualTo(EventType.PLAN_EXHAUSTED);
        verify(planRepository, never()).updatePlanStatus(eq(PLAN_ID), eq(PlanStatus.STEP_SCHEDULED), any());
    }

    // ───────────────────────── prepareStepDue（#17、#18、#19） ─────────────────────────

    @Test
    @DisplayName("#17 PENDING/SCHEDULED/EXECUTING 到期 → STEP_EXECUTING 且 toExecute")
    void prepareStepDue_toExecute() {
        plan.setStatus(PlanStatus.PENDING);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);

        StepDuePreparation prep = manager.prepareStepDue(stepEvent(EventType.PLAN_STEP_DUE));

        assertThat(prep.isExecute()).isTrue();
        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.STEP_EXECUTING, null);
        verify(planRepository).markStarted(PLAN_ID);
    }

    @Test
    @DisplayName("#18 STEP_WAITING 观察期到期 → 步骤 COMPLETED + 结转 STEP_COMPLETED 事件")
    void prepareStepDue_waitingCarryOver() {
        plan.setStatus(PlanStatus.STEP_WAITING);
        step.setResult(ContactResult.DELIVERED);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);

        StepDuePreparation prep = manager.prepareStepDue(stepEvent(EventType.PLAN_STEP_DUE));

        assertThat(prep.isExecute()).isFalse();
        verify(planRepository).updateStepStatus(STEP_ID, StepStatus.COMPLETED, ContactResult.DELIVERED);
        assertThat(prep.getEvents()).hasSize(1);
        assertThat(prep.getEvents().get(0).getEventType()).isEqualTo(EventType.STEP_COMPLETED);
    }

    @Test
    @DisplayName("#19 计划终态到期 → noop，不改状态")
    void prepareStepDue_terminalNoop() {
        plan.setStatus(PlanStatus.PLAN_CANCELLED);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);

        StepDuePreparation prep = manager.prepareStepDue(stepEvent(EventType.PLAN_STEP_DUE));

        assertThat(prep.isExecute()).isFalse();
        verify(planRepository, never()).updatePlanStatus(any(), any(), any());
    }

    // ───────────────────────── onCaseIngested（#20、#21） ─────────────────────────

    @Test
    @DisplayName("#20 案件接入 → 建计划落库 PENDING")
    void onCaseIngested_createsPlan() {
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S1)).thenReturn(null);
        CaseInfo info = new CaseInfo();
        info.setCaseId(CASE_ID);
        info.setUserId(USER_ID);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(info);
        when(caseService.getContextSnapshot(CASE_ID)).thenReturn(new ContextSnapshot());
        ContactPlan created = newPlan(0L, null, Stage.S1);
        created.getSteps().add(newStep(0L, 1, ChannelType.SMS, null));
        when(planFactory.create(any(), eq(Stage.S1), any())).thenReturn(created);

        CollectionEvent event = CollectionEvent.of(EventType.CASE_INGESTED)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.STAGE, "S1");

        manager.onCaseIngested(event);

        ArgumentCaptor<ContactPlan> captor = ArgumentCaptor.forClass(ContactPlan.class);
        verify(planRepository).savePlan(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PlanStatus.PENDING);
        assertThat(captor.getValue().getStage()).isEqualTo(Stage.S1);
    }

    @Test
    @DisplayName("#21 同 case+stage 已有活跃计划 → 幂等跳过，不建计划")
    void onCaseIngested_idempotentSkip() {
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S1)).thenReturn(plan);

        CollectionEvent event = CollectionEvent.of(EventType.CASE_INGESTED)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.STAGE, "S1");

        manager.onCaseIngested(event);

        verify(planFactory, never()).create(any(), any(), any());
        verify(planRepository, never()).savePlan(any());
    }

    // ───────────────────────── onChannelCallback / onCallbackTimeout（#22、#23） ─────────────────────────

    @Test
    @DisplayName("#22 异步回调 → 步骤 COMPLETED + 发布 STEP_COMPLETED")
    void onChannelCallback_completesStep() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan); // STEP_EXECUTING
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);

        CollectionEvent event = stepEvent(EventType.CHANNEL_CALLBACK).with("result", "ANSWERED");
        List<CollectionEvent> out = manager.onChannelCallback(event);

        verify(planRepository).updateStepStatus(STEP_ID, StepStatus.COMPLETED, ContactResult.ANSWERED);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEventType()).isEqualTo(EventType.STEP_COMPLETED);
    }

    @Test
    @DisplayName("#23 回调超时兜底 → 步骤 FAILED + 推进")
    void onCallbackTimeout_failsStep() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan); // STEP_EXECUTING
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);

        List<CollectionEvent> out = manager.onCallbackTimeout(stepEvent(EventType.CALLBACK_TIMEOUT));

        verify(planRepository).updateStepStatus(STEP_ID, StepStatus.FAILED, ContactResult.FAILED);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEventType()).isEqualTo(EventType.STEP_COMPLETED);
    }

    // ───────────────────────── onRepaymentReceived（#24） ─────────────────────────

    @Test
    @DisplayName("#24 还款到账 → 取消活跃计划(REPAID) + 过滤外呼名单")
    void onRepaymentReceived_cancelsAndFilters() {
        when(planRepository.findActivePlansByUser(USER_ID)).thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);

        CollectionEvent event = CollectionEvent.of(EventType.REPAYMENT_RECEIVED)
                .with(CollectionEvent.USER_ID, USER_ID);
        manager.onRepaymentReceived(event);

        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
        verify(predictiveDialerService).filterRepaidUser(USER_ID);
    }

    // ───────────────────────── onPlanExhausted（#25 三分支） ─────────────────────────

    @Test
    @DisplayName("#25-REBUILD 穷尽续建 → 旧计划完成 + 同阶段新建")
    void onPlanExhausted_rebuild() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(caseInfoWithUser());
        when(caseService.getContextSnapshot(CASE_ID)).thenReturn(new ContextSnapshot());
        when(exhaustionPolicy.handle(any(), any(), any()))
                .thenReturn(ExhaustionResult.rebuild("T_REBUILD", "retry"));
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S2)).thenReturn(null);
        ContactPlan created = newPlan(0L, null, Stage.S2);
        created.getSteps().add(newStep(0L, 1, ChannelType.SMS, null));
        when(planFactory.create(any(), eq(Stage.S2), any())).thenReturn(created);

        manager.onPlanExhausted(planExhaustedEvent());

        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.PLAN_COMPLETED, null);
        verify(planRepository).savePlan(any());
    }

    @Test
    @DisplayName("#25-ESCALATE 穷尽升档 → 旧完成 + 发布 STAGE_CHANGED")
    void onPlanExhausted_escalate() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(caseInfoWithUser());
        when(caseService.getContextSnapshot(CASE_ID)).thenReturn(new ContextSnapshot());
        when(exhaustionPolicy.handle(any(), any(), any()))
                .thenReturn(ExhaustionResult.escalate(Stage.S3, "upgrade"));

        List<CollectionEvent> out = manager.onPlanExhausted(planExhaustedEvent());

        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.PLAN_COMPLETED, null);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEventType()).isEqualTo(EventType.STAGE_CHANGED);
        assertThat(out.get(0).getString(CollectionEvent.STAGE)).isEqualTo("S3");
    }

    @Test
    @DisplayName("#25-COMPLETE 穷尽停止 → 计划完成，无后续事件")
    void onPlanExhausted_complete() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(caseInfoWithUser());
        when(caseService.getContextSnapshot(CASE_ID)).thenReturn(new ContextSnapshot());
        when(exhaustionPolicy.handle(any(), any(), any()))
                .thenReturn(ExhaustionResult.complete("stop"));

        List<CollectionEvent> out = manager.onPlanExhausted(planExhaustedEvent());

        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.PLAN_COMPLETED, null);
        assertThat(out).isEmpty();
    }

    // ───────────────────────── onStageChanged（#26） ─────────────────────────

    @Test
    @DisplayName("#26 阶段变更 → 取消旧阶段计划(STAGE_UPGRADE) + 新建")
    void onStageChanged_cancelsOldAndCreatesNew() {
        ContactPlan old = newPlan(PLAN_ID, PlanStatus.STEP_EXECUTING, Stage.S2);
        when(planRepository.findActivePlansByCase(CASE_ID)).thenReturn(new ArrayList<>(Arrays.asList(old)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(old);
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S3)).thenReturn(null);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(caseInfoWithUser());
        when(caseService.getContextSnapshot(CASE_ID)).thenReturn(new ContextSnapshot());
        ContactPlan created = newPlan(0L, null, Stage.S3);
        created.getSteps().add(newStep(0L, 1, ChannelType.SMS, null));
        when(planFactory.create(any(), eq(Stage.S3), any())).thenReturn(created);

        CollectionEvent event = CollectionEvent.of(EventType.STAGE_CHANGED)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.STAGE, "S3");
        manager.onStageChanged(event);

        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.STAGE_UPGRADE);
        verify(planRepository).savePlan(any());
    }

    // ───────────────────────── onPtpExpired（#27） ─────────────────────────

    @Test
    @DisplayName("#27-已还款 PTP 到期 → 补偿取消活跃计划")
    void onPtpExpired_repaidCancels() {
        when(caseService.isRepaid(CASE_ID)).thenReturn(true);
        when(planRepository.findActivePlansByCase(CASE_ID)).thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);

        CollectionEvent event = CollectionEvent.of(EventType.PTP_EXPIRED)
                .with(CollectionEvent.CASE_ID, CASE_ID);
        manager.onPtpExpired(event);

        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
    }

    @Test
    @DisplayName("#27-计划仍活跃 PTP 到期 → 不动（正常流程继续）")
    void onPtpExpired_activePlanNoop() {
        when(caseService.isRepaid(CASE_ID)).thenReturn(false);
        when(planRepository.findActivePlansByCase(CASE_ID)).thenReturn(new ArrayList<>(Arrays.asList(plan)));

        CollectionEvent event = CollectionEvent.of(EventType.PTP_EXPIRED)
                .with(CollectionEvent.CASE_ID, CASE_ID);
        manager.onPtpExpired(event);

        verify(planRepository, never()).updatePlanStatus(any(), any(), any());
        verify(planRepository, never()).savePlan(any());
    }

    private CaseInfo caseInfoWithUser() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(CASE_ID);
        info.setUserId(USER_ID);
        return info;
    }

    private CollectionEvent planExhaustedEvent() {
        return CollectionEvent.of(EventType.PLAN_EXHAUSTED)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.PLAN_ID, PLAN_ID);
    }
}
