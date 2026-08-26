package com.collection.engine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.service.CaseService;
import com.collection.common.service.PredictiveDialerService;
import com.collection.common.spi.AdvancementPolicy;
import com.collection.common.spi.ExhaustionPolicy;
import com.collection.common.spi.PlanFactory;
import com.collection.common.util.JsonUtil;
import com.collection.engine.metrics.CollectionMetrics;
import com.collection.engine.spi.SpiInvoker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** PlanLifecycleManager 状态机纯逻辑单测（核心引擎规格 §2）。全 mock，不连库。 覆盖测试矩阵 #15-27。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanLifecycleManagerTest {

    private static final long PLAN_ID = 100L;
    private static final long STEP_ID = 200L;
    private static final long NEXT_STEP_ID = 201L;
    private static final long CASE_ID = 1002L;
    private static final long USER_ID = 9001L;

    @Mock private ContactPlanRepository planRepository;
    @Mock private TimelineRepository timelineRepository;
    @Mock private StepOutcomeRecorder stepOutcomeRecorder;
    @Mock private CaseService caseService;
    @Mock private PlanFactory planFactory;
    @Mock private AdvancementPolicy advancementPolicy;
    @Mock private ExhaustionPolicy exhaustionPolicy;
    @Mock private PredictiveDialerService predictiveDialerService;
    @Spy private SpiInvoker spiInvoker = SpiInvoker.direct();
    @Spy private CollectionMetrics metrics = CollectionMetrics.local();

    @InjectMocks private PlanLifecycleManager manager;

    private ContactPlan plan;
    private ContactPlanStep step;

    @BeforeEach
    void setUp() {
        plan = newPlan(PLAN_ID, PlanStatus.STEP_EXECUTING, Stage.S2);
        step = newStep(STEP_ID, 1, ChannelType.SMS, StepStatus.EXECUTING);
        // 默认「抢到执行权」；抢占失败的分支由专门用例覆盖
        lenient().when(planRepository.markStepExecuting(any())).thenReturn(true);
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

        verify(planRepository)
                .updateStepTriggerTime(eq(NEXT_STEP_ID), any(), eq(StepStatus.PENDING));
        verify(planRepository).updateCurrentStep(PLAN_ID, 2);
        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.STEP_SCHEDULED, null);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("预排绝对槽位推进时保留下一步 trigger_time")
    void onStepCompleted_advanceNext_preservesPreScheduledTriggerTime() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(advancementPolicy.decide(any(), any())).thenReturn(AdvancementDecision.ADVANCE_NEXT);
        ContactPlanStep next = newStep(NEXT_STEP_ID, 2, ChannelType.PUSH, StepStatus.PENDING);
        next.setTriggerTime(LocalDateTime.of(2026, 8, 8, 12, 0));
        when(planRepository.getNextStep(PLAN_ID, 1)).thenReturn(next);

        manager.onStepCompleted(stepEvent(EventType.STEP_COMPLETED));

        verify(planRepository, never())
                .updateStepTriggerTime(eq(NEXT_STEP_ID), any(), eq(StepStatus.PENDING));
        verify(planRepository).updateCurrentStep(PLAN_ID, 2);
        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.STEP_SCHEDULED, null);
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
        verify(planRepository, never())
                .updatePlanStatus(eq(PLAN_ID), eq(PlanStatus.STEP_SCHEDULED), any());
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
    @DisplayName("#17b 抢占失败（步骤已被并发投递终结）→ noop 且计划状态一律不动")
    void prepareStepDue_claimLost_leavesPlanUntouched() {
        // 生产 Pub/Sub 至少一次：重复的 PLAN_STEP_DUE 在读到非终态后、写入前被另一路终结。
        // 此时若仍把计划按回 STEP_EXECUTING，已被上一次投递推进或终结的计划会停摆。
        plan.setStatus(PlanStatus.PENDING);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(planRepository.markStepExecuting(STEP_ID)).thenReturn(false);

        StepDuePreparation prep = manager.prepareStepDue(stepEvent(EventType.PLAN_STEP_DUE));

        assertThat(prep.isExecute()).isFalse();
        assertThat(prep.getEvents()).isEmpty();
        verify(planRepository, never()).updatePlanStatus(eq(PLAN_ID), any(), any());
        verify(planRepository, never()).markStarted(PLAN_ID);
    }

    @Test
    @DisplayName("#17c 抢占先于计划写入：抢到才允许改计划状态（顺序不可颠倒）")
    void prepareStepDue_claimsStepBeforeTouchingPlan() {
        plan.setStatus(PlanStatus.PENDING);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);

        manager.prepareStepDue(stepEvent(EventType.PLAN_STEP_DUE));

        InOrder order = org.mockito.Mockito.inOrder(planRepository);
        order.verify(planRepository).markStepExecuting(STEP_ID);
        order.verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.STEP_EXECUTING, null);
        order.verify(planRepository).markStarted(PLAN_ID);
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
        verify(planRepository)
                .updateStepStatus(STEP_ID, StepStatus.COMPLETED, ContactResult.DELIVERED);
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

        CollectionEvent event =
                CollectionEvent.of(EventType.CASE_INGESTED)
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

        CollectionEvent event =
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.STAGE, "S1");

        manager.onCaseIngested(event);

        verify(planFactory, never()).create(any(), any(), any());
        verify(planRepository, never()).savePlan(any());
    }

    // ───────────────────────── onChannelCallback / onCallbackTimeout（#22、#23）
    // ─────────────────────────

    @Test
    @DisplayName("#22 异步回调 → 步骤 COMPLETED + 发布 STEP_COMPLETED")
    void onChannelCallback_completesStep() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan); // STEP_EXECUTING
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(stepOutcomeRecorder.recordTerminal(
                        any(),
                        any(),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        eq(ContactResult.ANSWERED),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);

        CollectionEvent event = stepEvent(EventType.CHANNEL_CALLBACK).with("result", "ANSWERED");
        List<CollectionEvent> out = manager.onChannelCallback(event);

        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(plan),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        eq(ContactResult.ANSWERED),
                        eq(step.getChannelType()),
                        any(),
                        any());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEventType()).isEqualTo(EventType.STEP_COMPLETED);
    }

    @Test
    @DisplayName("#23 回调超时兜底 → 步骤 FAILED + 推进")
    void onCallbackTimeout_failsStep() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan); // STEP_EXECUTING
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(stepOutcomeRecorder.recordTerminal(
                        any(),
                        any(),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.FAILED),
                        eq(ContactResult.FAILED),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);

        List<CollectionEvent> out =
                manager.onCallbackTimeout(stepEvent(EventType.CALLBACK_TIMEOUT));

        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(plan),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.FAILED),
                        eq(ContactResult.FAILED),
                        eq(step.getChannelType()),
                        any(),
                        org.mockito.ArgumentMatchers.contains("CALLBACK_TIMEOUT"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEventType()).isEqualTo(EventType.STEP_COMPLETED);
    }

    // ───────────────────────── onRepaymentReceived（#24） ─────────────────────────

    @Test
    @DisplayName("#24 整笔 loan 结清 → 取消该案活跃计划(REPAID) + 过滤该案外呼名单")
    void onRepaymentReceived_cancelsAndFilters() {
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);

        CollectionEvent event =
                CollectionEvent.of(EventType.REPAYMENT_RECEIVED)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.CASE_ID, CASE_ID);
        manager.onRepaymentReceived(event);

        verify(planRepository, never()).findActivePlansByUser(USER_ID);
        verify(planRepository)
                .updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
        verify(predictiveDialerService).filterRepaidCase(USER_ID, CASE_ID);
    }

    @Test
    @DisplayName("#24 并发终态先写：还款取锁后发现已完成 → 不覆写计划状态")
    void onRepaymentReceived_lockedPlanAlreadyTerminal_doesNotOverwrite() {
        ContactPlan stale = newPlan(PLAN_ID, PlanStatus.STEP_EXECUTING, Stage.S2);
        ContactPlan completed = newPlan(PLAN_ID, PlanStatus.PLAN_COMPLETED, Stage.S2);
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(stale)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(completed);

        manager.onRepaymentReceived(
                CollectionEvent.of(EventType.REPAYMENT_RECEIVED)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.CASE_ID, CASE_ID));

        verify(planRepository, never())
                .updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
        verify(predictiveDialerService).filterRepaidCase(USER_ID, CASE_ID);
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
        // renewal_pending 置位后，旧计划不再参与活跃计划检查。
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S2)).thenReturn(null);
        ContactPlan created = newPlan(0L, null, Stage.S2);
        created.getSteps().add(newStep(0L, 1, ChannelType.SMS, null));
        when(planFactory.create(any(), eq(Stage.S2), any())).thenReturn(created);

        manager.onPlanExhausted(planExhaustedEvent());

        InOrder inOrder = org.mockito.Mockito.inOrder(planRepository);
        inOrder.verify(planRepository).markRenewalPending(PLAN_ID);
        inOrder.verify(planRepository).savePlan(any());
        inOrder.verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.PLAN_COMPLETED, null);
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
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(old)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(old);
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S3)).thenReturn(null);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(caseInfoWithUser());
        when(caseService.getContextSnapshot(CASE_ID)).thenReturn(new ContextSnapshot());
        ContactPlan created = newPlan(0L, null, Stage.S3);
        created.getSteps().add(newStep(0L, 1, ChannelType.SMS, null));
        when(planFactory.create(any(), eq(Stage.S3), any())).thenReturn(created);

        CollectionEvent event =
                CollectionEvent.of(EventType.STAGE_CHANGED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.STAGE, "S3");
        manager.onStageChanged(event);

        verify(planRepository)
                .updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.STAGE_UPGRADE);
        verify(planRepository).savePlan(any());
    }

    @Test
    @DisplayName("#26a 阶段变更携带日切日变字段 → carry-forward 快照同时刷新 dpd / 余额")
    void onStageChanged_refreshesVolatileSnapshotFields() {
        CaseContext stale = new CaseContext();
        stale.setCaseId(CASE_ID);
        stale.setUserId(USER_ID);
        stale.setDpd(16);
        stale.setStage(Stage.S3);
        stale.setTotalOutstanding(new BigDecimal("1000.00"));
        ContextSnapshot carried = new ContextSnapshot();
        carried.setCaseContext(stale);

        ContactPlan old = newPlan(PLAN_ID, PlanStatus.STEP_EXECUTING, Stage.S3);
        old.setContextSnapshot(JsonUtil.toJson(carried));
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(old)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(old);
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S4)).thenReturn(null);
        ContactPlan created = newPlan(0L, null, Stage.S4);
        created.getSteps().add(newStep(0L, 1, ChannelType.SMS, null));
        when(planFactory.create(any(), eq(Stage.S4), any())).thenReturn(created);

        manager.onStageChanged(
                CollectionEvent.of(EventType.STAGE_CHANGED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.STAGE, "S4")
                        .with(CollectionEvent.DPD, 31)
                        .with(CollectionEvent.TOTAL_OUTSTANDING, new BigDecimal("1800.00")));

        ArgumentCaptor<ContextSnapshot> captor = ArgumentCaptor.forClass(ContextSnapshot.class);
        verify(planFactory).create(any(), eq(Stage.S4), captor.capture());
        CaseContext refreshed = captor.getValue().getCaseContext();
        assertThat(refreshed.getStage()).isEqualTo(Stage.S4);
        assertThat(refreshed.getDpd()).isEqualTo(31);
        assertThat(refreshed.getTotalOutstanding()).isEqualByComparingTo(new BigDecimal("1800.00"));
    }

    // ─────────── onPtpExpired（#27，Phase 2 预留：Phase 1 Dispatcher 不订阅，仅直测处理器逻辑） ───────────

    @Test
    @DisplayName("#27-[Phase2]已还款 PTP 到期 → 补偿取消活跃计划")
    void onPtpExpired_repaidCancels() {
        when(caseService.isRepaid(CASE_ID)).thenReturn(true);
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);

        CollectionEvent event =
                CollectionEvent.of(EventType.PTP_EXPIRED).with(CollectionEvent.CASE_ID, CASE_ID);
        manager.onPtpExpired(event);

        verify(planRepository)
                .updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
    }

    @Test
    @DisplayName("#27-[Phase2]计划仍活跃 PTP 到期 → 不动（正常流程继续）")
    void onPtpExpired_activePlanNoop() {
        when(caseService.isRepaid(CASE_ID)).thenReturn(false);
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(plan)));

        CollectionEvent event =
                CollectionEvent.of(EventType.PTP_EXPIRED).with(CollectionEvent.CASE_ID, CASE_ID);
        manager.onPtpExpired(event);

        verify(planRepository, never()).updatePlanStatus(any(), any(), any());
        verify(planRepository, never()).savePlan(any());
    }

    // ───────────────────────── 差集补全：链路① 建计划守卫（D21/D22/D23/D25） ─────────────────────────

    @Test
    @DisplayName("①-D21 PlanFactory 返回 null → 不建计划（savePlan 不调）")
    void onCaseIngested_factoryNull_noPlan() {
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S1)).thenReturn(null);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(caseInfoWithUser());
        when(caseService.getContextSnapshot(CASE_ID)).thenReturn(new ContextSnapshot());
        when(planFactory.create(any(), eq(Stage.S1), any())).thenReturn(null);

        manager.onCaseIngested(ingestEvent("S1"));

        verify(planRepository, never()).savePlan(any());
    }

    @Test
    @DisplayName("①-D22 caseInfo.caseStatus=CEASED → 跳过工厂，不建计划")
    void onCaseIngested_ceasedCaseStatus_skip() {
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S1)).thenReturn(null);
        CaseInfo ceased = caseInfoWithUser();
        ceased.setCaseStatus("CEASED");
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(ceased);

        manager.onCaseIngested(ingestEvent("S1"));

        verify(planFactory, never()).create(any(), any(), any());
        verify(planRepository, never()).savePlan(any());
    }

    @Test
    @DisplayName("①-D22 snapshot.caseContext.collectionStatus=CEASED → 跳过工厂，不建计划")
    void onCaseIngested_ceasedSnapshot_skip() {
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S1)).thenReturn(null);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(caseInfoWithUser());
        ContextSnapshot snap = new ContextSnapshot();
        CaseContext cc = new CaseContext();
        cc.setCollectionStatus("CEASED");
        snap.setCaseContext(cc);
        when(caseService.getContextSnapshot(CASE_ID)).thenReturn(snap);

        manager.onCaseIngested(ingestEvent("S1"));

        verify(planFactory, never()).create(any(), any(), any());
        verify(planRepository, never()).savePlan(any());
    }

    @Test
    @DisplayName("①-D23 建计划首步 trigger≈now(delay=0) + snapshot 冻结写入")
    void onCaseIngested_firstStepTriggerTime_andSnapshotFrozen() {
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S1)).thenReturn(null);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(caseInfoWithUser());
        when(caseService.getContextSnapshot(CASE_ID)).thenReturn(new ContextSnapshot());
        ContactPlan created = newPlan(0L, null, Stage.S1);
        created.getSteps().add(newStep(0L, 1, ChannelType.SMS, null)); // delayMinutes 默认 0
        when(planFactory.create(any(), eq(Stage.S1), any())).thenReturn(created);

        LocalDateTime before = LocalDateTime.now();
        manager.onCaseIngested(ingestEvent("S1"));

        ArgumentCaptor<ContactPlan> captor = ArgumentCaptor.forClass(ContactPlan.class);
        verify(planRepository).savePlan(captor.capture());
        ContactPlan saved = captor.getValue();
        assertThat(saved.getContextSnapshot()).isNotNull(); // 快照冻结为 JSON
        ContactPlanStep first = saved.getSteps().get(0);
        assertThat(first.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(first.getTriggerTime())
                .isBetween(before.minusMinutes(1), LocalDateTime.now().plusMinutes(1));
    }

    @Test
    @DisplayName("①-D25 建计划阶段 CaseService 读失败 → 异常上抛(NACK)，不建计划")
    void onCaseIngested_caseServiceReadFailure_propagates() {
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S1)).thenReturn(null);
        when(caseService.getCaseInfo(CASE_ID)).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> manager.onCaseIngested(ingestEvent("S1")))
                .isInstanceOf(RuntimeException.class);
        verify(planRepository, never()).savePlan(any());
    }

    // ───────────────────────── 差集补全：链路② 推进 delay=0（D1） ─────────────────────────

    @Test
    @DisplayName("②-D1 推进 delay=0 → 下一步 trigger≈now + STEP_SCHEDULED，无后续事件")
    void onStepCompleted_advanceNext_delayZero_schedulesWithNowTrigger() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(advancementPolicy.decide(any(), any())).thenReturn(AdvancementDecision.ADVANCE_NEXT);
        ContactPlanStep next = newStep(NEXT_STEP_ID, 2, ChannelType.PUSH, StepStatus.PENDING);
        next.setDelayMinutes(0);
        when(planRepository.getNextStep(PLAN_ID, 1)).thenReturn(next);

        LocalDateTime before = LocalDateTime.now();
        List<CollectionEvent> out = manager.onStepCompleted(stepEvent(EventType.STEP_COMPLETED));

        ArgumentCaptor<LocalDateTime> trigger = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(planRepository)
                .updateStepTriggerTime(eq(NEXT_STEP_ID), trigger.capture(), eq(StepStatus.PENDING));
        assertThat(trigger.getValue())
                .isBetween(before.minusSeconds(5), LocalDateTime.now().plusSeconds(5));
        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.STEP_SCHEDULED, null);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("CONNECT_AND_STOP：AI_CALL 接通后跳过同日 PENDING 补呼，保留短信与次日外呼")
    void onStepCompleted_answeredAiCall_skipsSameDayPendingRetry() {
        ContactPlanStep answered = newStep(STEP_ID, 1, ChannelType.AI_CALL, StepStatus.COMPLETED);
        answered.setResult(ContactResult.ANSWERED);
        answered.setOriginalTriggerTime(LocalDateTime.of(2026, 8, 26, 9, 15));

        ContactPlanStep sameDayRetry = newStep(202L, 2, ChannelType.AI_CALL, StepStatus.PENDING);
        sameDayRetry.setOriginalTriggerTime(LocalDateTime.of(2026, 8, 26, 14, 30));
        ContactPlanStep sameDaySms = newStep(203L, 3, ChannelType.SMS, StepStatus.PENDING);
        sameDaySms.setOriginalTriggerTime(LocalDateTime.of(2026, 8, 26, 12, 0));
        ContactPlanStep nextDayCall = newStep(204L, 4, ChannelType.AI_CALL, StepStatus.PENDING);
        nextDayCall.setOriginalTriggerTime(LocalDateTime.of(2026, 8, 27, 9, 15));
        ContactPlanStep executingCall = newStep(205L, 5, ChannelType.AI_CALL, StepStatus.EXECUTING);
        executingCall.setOriginalTriggerTime(LocalDateTime.of(2026, 8, 26, 16, 0));

        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(answered);
        when(advancementPolicy.decide(any(), any())).thenReturn(AdvancementDecision.ADVANCE_NEXT);
        when(planRepository.findStepsByPlan(PLAN_ID))
                .thenReturn(
                        Arrays.asList(
                                answered, sameDayRetry, sameDaySms, nextDayCall, executingCall));
        ContactPlanStep next = newStep(NEXT_STEP_ID, 3, ChannelType.SMS, StepStatus.PENDING);
        next.setTriggerTime(LocalDateTime.of(2026, 8, 26, 12, 0));
        when(planRepository.getNextStep(PLAN_ID, 1)).thenReturn(next);

        manager.onStepCompleted(stepEvent(EventType.STEP_COMPLETED));

        verify(planRepository)
                .updateStepStatus(202L, StepStatus.SKIPPED, ContactResult.SKIPPED);
        verify(planRepository, never())
                .updateStepStatus(eq(203L), any(), any());
        verify(planRepository, never())
                .updateStepStatus(eq(204L), any(), any());
        verify(planRepository, never())
                .updateStepStatus(eq(205L), any(), any());
    }

    @Test
    @DisplayName("CONNECT_AND_STOP：未接通不跳过同日补呼")
    void onStepCompleted_noAnswer_doesNotSkipRetry() {
        ContactPlanStep completed = newStep(STEP_ID, 1, ChannelType.AI_CALL, StepStatus.COMPLETED);
        completed.setResult(ContactResult.NO_ANSWER);
        completed.setOriginalTriggerTime(LocalDateTime.of(2026, 8, 26, 9, 15));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(completed);
        when(advancementPolicy.decide(any(), any())).thenReturn(AdvancementDecision.ADVANCE_NEXT);
        ContactPlanStep next = newStep(NEXT_STEP_ID, 2, ChannelType.AI_CALL, StepStatus.PENDING);
        next.setTriggerTime(LocalDateTime.of(2026, 8, 26, 14, 30));
        when(planRepository.getNextStep(PLAN_ID, 1)).thenReturn(next);

        manager.onStepCompleted(stepEvent(EventType.STEP_COMPLETED));

        verify(planRepository, never()).findStepsByPlan(anyLong());
        verify(planRepository, never())
                .updateStepStatus(eq(NEXT_STEP_ID), eq(StepStatus.SKIPPED), any());
    }

    // ───────────────────────── 差集补全：链路③ 观察期缺省结转（D26） ─────────────────────────

    @Test
    @DisplayName("③-D26 观察期到期且 best_result 缺省 → SENT_NO_RESPONSE 结转")
    void prepareStepDue_waitingCarryOver_defaultSentNoResponse() {
        plan.setStatus(PlanStatus.STEP_WAITING);
        step.setResult(null); // 观察期内无结果回填
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);

        StepDuePreparation prep = manager.prepareStepDue(stepEvent(EventType.PLAN_STEP_DUE));

        verify(planRepository)
                .updateStepStatus(STEP_ID, StepStatus.COMPLETED, ContactResult.SENT_NO_RESPONSE);
        assertThat(prep.getEvents()).hasSize(1);
        assertThat(prep.getEvents().get(0).getEventType()).isEqualTo(EventType.STEP_COMPLETED);
    }

    // ───────────────────────── 差集补全：链路④ 异步回调态拦截/映射（D16/D17/D18） ─────────────────────────

    @Test
    @DisplayName("④-D16 回调时计划已终态但步骤仍 EXECUTING → 关步骤、不推进")
    void onChannelCallback_cancelledPlan_closesExecutingStepWithoutAdvance() {
        ContactPlan cancelled = newPlan(PLAN_ID, PlanStatus.PLAN_CANCELLED, Stage.S2);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(cancelled);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(stepOutcomeRecorder.recordTerminal(
                        any(),
                        any(),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);

        CollectionEvent event = stepEvent(EventType.CHANNEL_CALLBACK).with("result", "ANSWERED");
        List<CollectionEvent> out = manager.onChannelCallback(event);

        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(cancelled),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        eq(ContactResult.ANSWERED),
                        eq(step.getChannelType()),
                        any(),
                        any());
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("④ Phase 2 预留：STEP_WAITING 收到回调仍可短路结转（Phase 1 SMS 不进 WAITING）")
    void onChannelCallback_waiting_shortCircuits() {
        ContactPlan waiting = newPlan(PLAN_ID, PlanStatus.STEP_WAITING, Stage.S2);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(waiting);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(stepOutcomeRecorder.recordTerminal(
                        any(),
                        any(),
                        any(StepStatus.class),
                        any(StepStatus.class),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);

        CollectionEvent event = stepEvent(EventType.CHANNEL_CALLBACK).with("result", "DELIVERED");
        List<CollectionEvent> out = manager.onChannelCallback(event);

        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(waiting),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        eq(ContactResult.DELIVERED),
                        any(),
                        any(),
                        any());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEventType()).isEqualTo(EventType.STEP_COMPLETED);
    }

    @Test
    @DisplayName("④-D17 超时兜底时计划已终态但步骤仍 EXECUTING → 关步骤、不推进")
    void onCallbackTimeout_cancelledPlan_closesExecutingStepWithoutAdvance() {
        ContactPlan completed = newPlan(PLAN_ID, PlanStatus.PLAN_COMPLETED, Stage.S2);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(completed);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(stepOutcomeRecorder.recordTerminal(
                        any(),
                        any(),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.FAILED),
                        eq(ContactResult.FAILED),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);

        List<CollectionEvent> out =
                manager.onCallbackTimeout(stepEvent(EventType.CALLBACK_TIMEOUT));

        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(completed),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.FAILED),
                        eq(ContactResult.FAILED),
                        eq(step.getChannelType()),
                        any(),
                        org.mockito.ArgumentMatchers.contains("CALLBACK_TIMEOUT"));
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("计划已 STEP_SCHEDULED 时回调仍收口步骤，不发布 STEP_COMPLETED")
    void onChannelCallback_scheduledPlan_closesStepWithoutAdvance() {
        ContactPlan scheduled = newPlan(PLAN_ID, PlanStatus.STEP_SCHEDULED, Stage.S2);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(scheduled);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(stepOutcomeRecorder.recordTerminal(
                        any(),
                        any(),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);

        List<CollectionEvent> out =
                manager.onChannelCallback(
                        stepEvent(EventType.CHANNEL_CALLBACK).with("result", "FAILED"));

        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(scheduled),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        eq(ContactResult.FAILED),
                        eq(step.getChannelType()),
                        any(),
                        any());
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("计划已 STEP_SCHEDULED 时超时仍收口步骤，不发布 STEP_COMPLETED")
    void onCallbackTimeout_scheduledPlan_closesStepWithoutAdvance() {
        ContactPlan scheduled = newPlan(PLAN_ID, PlanStatus.STEP_SCHEDULED, Stage.S2);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(scheduled);
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(stepOutcomeRecorder.recordTerminal(
                        any(),
                        any(),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.FAILED),
                        eq(ContactResult.FAILED),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);

        List<CollectionEvent> out =
                manager.onCallbackTimeout(stepEvent(EventType.CALLBACK_TIMEOUT));

        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(scheduled),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.FAILED),
                        eq(ContactResult.FAILED),
                        eq(step.getChannelType()),
                        any(),
                        org.mockito.ArgumentMatchers.contains("CALLBACK_TIMEOUT"));
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("计划已 STEP_SCHEDULED 时迟到的 STEP_COMPLETED 不再 ADVANCE_NEXT")
    void onStepCompleted_scheduledPlan_doesNotRewind() {
        ContactPlan scheduled = newPlan(PLAN_ID, PlanStatus.STEP_SCHEDULED, Stage.S2);
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(scheduled);

        List<CollectionEvent> out = manager.onStepCompleted(stepEvent(EventType.STEP_COMPLETED));

        verify(advancementPolicy, never()).decide(any(), any());
        verify(planRepository, never()).getNextStep(anyLong(), anyInt());
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("④-D18 回调 result 映射：NO_ANSWER/BUSY 透传，非法值 fail-close")
    void onChannelCallback_mapsResultVariants() {
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan); // STEP_EXECUTING
        when(planRepository.findStepById(STEP_ID)).thenReturn(step);
        when(stepOutcomeRecorder.recordTerminal(
                        any(),
                        any(),
                        any(StepStatus.class),
                        any(StepStatus.class),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(true);

        manager.onChannelCallback(
                stepEvent(EventType.CHANNEL_CALLBACK).with("result", "NO_ANSWER"));
        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(plan),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        eq(ContactResult.NO_ANSWER),
                        any(),
                        any(),
                        any());

        manager.onChannelCallback(stepEvent(EventType.CHANNEL_CALLBACK).with("result", "BUSY"));
        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(plan),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        eq(ContactResult.BUSY),
                        any(),
                        any(),
                        any());

        manager.onChannelCallback(
                stepEvent(EventType.CHANNEL_CALLBACK).with("result", "NOT_A_RESULT"));
        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(plan),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(StepStatus.COMPLETED),
                        eq(ContactResult.FAILED),
                        any(),
                        any(),
                        any());
    }

    // ───────────────────────── onCaseBalanceUpdated（部分还款） ─────────────────────────

    @Test
    @DisplayName("部分还款 → 只刷新快照余额，计划状态与步骤不变")
    void onCaseBalanceUpdated_refreshesOutstandingOnly() {
        plan.setContextSnapshot(JsonUtil.toJson(snapshotWithOutstanding(new BigDecimal("5000"))));
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.updateActivePlanContextSnapshot(eq(PLAN_ID), anyString()))
                .thenReturn(true);

        manager.onCaseBalanceUpdated(balanceEvent(new BigDecimal("3200.50")));

        ArgumentCaptor<String> snapshotJson = ArgumentCaptor.forClass(String.class);
        verify(planRepository).updateActivePlanContextSnapshot(eq(PLAN_ID), snapshotJson.capture());
        ContextSnapshot updated = JsonUtil.fromJson(snapshotJson.getValue(), ContextSnapshot.class);
        assertThat(updated.getCaseContext().getTotalOutstanding())
                .isEqualByComparingTo(new BigDecimal("3200.50"));
        verify(planRepository, never()).updatePlanStatus(eq(PLAN_ID), any(), any());
    }

    @Test
    @DisplayName("部分还款：终态计划不刷新余额")
    void onCaseBalanceUpdated_skipsTerminalPlan() {
        plan.setStatus(PlanStatus.PLAN_CANCELLED);
        plan.setContextSnapshot(JsonUtil.toJson(snapshotWithOutstanding(new BigDecimal("5000"))));
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);

        manager.onCaseBalanceUpdated(balanceEvent(new BigDecimal("100")));

        verify(planRepository, never()).updateActivePlanContextSnapshot(anyLong(), anyString());
    }

    @Test
    @DisplayName("部分还款：金额缺失或为负直接忽略")
    void onCaseBalanceUpdated_ignoresInvalidAmount() {
        manager.onCaseBalanceUpdated(balanceEvent(new BigDecimal("-1")));
        manager.onCaseBalanceUpdated(
                CollectionEvent.of(EventType.CASE_BALANCE_UPDATED)
                        .with(CollectionEvent.CASE_ID, CASE_ID));

        verify(planRepository, never()).findActivePlansByCase(CASE_ID);
    }

    @Test
    @DisplayName("部分还款：携带的可选字段才覆盖，未携带的保留原值不写 null")
    void onCaseBalanceUpdated_onlyOverwritesFieldsPresentInEvent() {
        ContextSnapshot before = snapshotWithOutstanding(new BigDecimal("5000"));
        CaseContext context = before.getCaseContext();
        context.setDpd(7);
        context.setOverdueAmount(new BigDecimal("800"));
        context.setPenaltyAmount(new BigDecimal("30"));
        context.setUpcomingAmount(new BigDecimal("1200"));
        context.setNextDueDate(LocalDate.of(2026, 9, 1));
        context.setCollectionStatus("IN_COLLECTION");
        plan.setContextSnapshot(JsonUtil.toJson(before));
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.updateActivePlanContextSnapshot(eq(PLAN_ID), anyString()))
                .thenReturn(true);

        // 只携带 totalOutstanding 与 upcomingAmount：其余可选字段必须保持原值
        manager.onCaseBalanceUpdated(
                balanceEvent(new BigDecimal("3200.50"))
                        .with(CollectionEvent.UPCOMING_AMOUNT, new BigDecimal("900")));

        ArgumentCaptor<String> snapshotJson = ArgumentCaptor.forClass(String.class);
        verify(planRepository).updateActivePlanContextSnapshot(eq(PLAN_ID), snapshotJson.capture());
        CaseContext updated =
                JsonUtil.fromJson(snapshotJson.getValue(), ContextSnapshot.class).getCaseContext();
        assertThat(updated.getTotalOutstanding()).isEqualByComparingTo(new BigDecimal("3200.50"));
        assertThat(updated.getUpcomingAmount()).isEqualByComparingTo(new BigDecimal("900"));
        assertThat(updated.getDpd()).isEqualTo(7);
        assertThat(updated.getOverdueAmount()).isEqualByComparingTo(new BigDecimal("800"));
        assertThat(updated.getPenaltyAmount()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(updated.getNextDueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(updated.getCollectionStatus()).isEqualTo("IN_COLLECTION");
    }

    @Test
    @DisplayName("部分还款：stage 不被改写，催收强度不因 DPD 下降而回退")
    void onCaseBalanceUpdated_neverRewritesStage() {
        ContextSnapshot before = snapshotWithOutstanding(new BigDecimal("5000"));
        before.getCaseContext().setStage(Stage.S3);
        plan.setContextSnapshot(JsonUtil.toJson(before));
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(planRepository.updateActivePlanContextSnapshot(eq(PLAN_ID), anyString()))
                .thenReturn(true);

        manager.onCaseBalanceUpdated(
                balanceEvent(new BigDecimal("100"))
                        .with(CollectionEvent.STAGE, Stage.S1.name())
                        .with(CollectionEvent.DPD, 1));

        ArgumentCaptor<String> snapshotJson = ArgumentCaptor.forClass(String.class);
        verify(planRepository).updateActivePlanContextSnapshot(eq(PLAN_ID), snapshotJson.capture());
        CaseContext updated =
                JsonUtil.fromJson(snapshotJson.getValue(), ContextSnapshot.class).getCaseContext();
        assertThat(updated.getStage()).isEqualTo(Stage.S3);
        assertThat(updated.getDpd()).isEqualTo(1);
        verify(planRepository, never()).updatePlanStatus(eq(PLAN_ID), any(), any());
    }

    private CollectionEvent balanceEvent(BigDecimal amount) {
        return CollectionEvent.of(EventType.CASE_BALANCE_UPDATED)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.USER_ID, USER_ID)
                .with(CollectionEvent.TOTAL_OUTSTANDING, amount);
    }

    private ContextSnapshot snapshotWithOutstanding(BigDecimal amount) {
        CaseContext caseContext = new CaseContext();
        caseContext.setCaseId(CASE_ID);
        caseContext.setUserId(USER_ID);
        caseContext.setTotalOutstanding(amount);
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(caseContext);
        return snapshot;
    }

    // ───────────────────────── 差集补全：链路⑤ 还款过滤失败/CASE_CEASED（D28/D29-L0） ─────────────────────────

    @Test
    @DisplayName("⑤-D28 filterRepaidCase 抛异常 → 计划仍取消(REPAID)")
    void onRepaymentReceived_filterFails_stillCancels() {
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        doThrow(new RuntimeException("dialer down"))
                .when(predictiveDialerService)
                .filterRepaidCase(USER_ID, CASE_ID);

        CollectionEvent event =
                CollectionEvent.of(EventType.REPAYMENT_RECEIVED)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.CASE_ID, CASE_ID);
        manager.onRepaymentReceived(event);

        verify(planRepository)
                .updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
    }

    @Test
    @DisplayName("⑤-D29 CASE_CEASED → 取消活跃计划(CEASED)，不再建计划")
    void onCaseCeased_cancelsActivePlanAndNoRebuild() {
        when(planRepository.findActivePlansByCase(CASE_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(plan)));
        when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);

        CollectionEvent event =
                CollectionEvent.of(EventType.CASE_CEASED).with(CollectionEvent.CASE_ID, CASE_ID);
        manager.onCaseCeased(event);

        verify(planRepository)
                .updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.CEASED);
        verify(planFactory, never()).create(any(), any(), any());
        verify(planRepository, never()).savePlan(any());
    }

    // ───────────────────────── 决策 B：payload 自带快照（不读旧库） ─────────────────────────

    @Test
    @DisplayName("①-B 决策B：CASE_INGESTED 带快照字段 → 据 payload 组装，主链路不读旧库(CaseService 不被调用)")
    void onCaseIngested_payloadSnapshot_noCaseServiceRead() {
        when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S2)).thenReturn(null);
        ContactPlan created = newPlan(0L, null, Stage.S2);
        created.getSteps().add(newStep(0L, 1, ChannelType.SMS, null));
        when(planFactory.create(any(), eq(Stage.S2), any())).thenReturn(created);

        CollectionEvent event =
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.STAGE, "S2")
                        .with(CollectionEvent.DPD, 10)
                        .with(CollectionEvent.PHONE, "+639171234567")
                        .with(CollectionEvent.EMAIL, "a@b.com")
                        .with(
                                CollectionEvent.TOTAL_OUTSTANDING,
                                new java.math.BigDecimal("1500.00"));

        manager.onCaseIngested(event);

        ArgumentCaptor<ContactPlan> captor = ArgumentCaptor.forClass(ContactPlan.class);
        verify(planRepository).savePlan(captor.capture());
        assertThat(captor.getValue().getContextSnapshot()).contains("+639171234567");
        // 决策 B：快照来自 payload，运行时不读旧库
        verify(caseService, never()).getContextSnapshot(any());
        verify(caseService, never()).getCaseInfo(any());
    }

    @Test
    @DisplayName("①-B 决策B：payload dpd>=91 → collectionStatus=CEASED → 跳过工厂，不读旧库")
    void onCaseIngested_payloadCeased_skip() {
        when(planRepository.findActivePlanByCaseAndStage(any(), any())).thenReturn(null);

        CollectionEvent event =
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.DPD, 95)
                        .with(CollectionEvent.PHONE, "+639171234567");

        manager.onCaseIngested(event);

        verify(planFactory, never()).create(any(), any(), any());
        verify(planRepository, never()).savePlan(any());
        verify(caseService, never()).getCaseInfo(any());
    }

    @Test
    @DisplayName("①-B 决策B：CASE_INGESTED 缺 caseId → 异常上抛(NACK 重消费)")
    void onCaseIngested_missingCaseId_propagates() {
        CollectionEvent event =
                CollectionEvent.of(EventType.CASE_INGESTED).with(CollectionEvent.STAGE, "S1");

        assertThatThrownBy(() -> manager.onCaseIngested(event))
                .isInstanceOf(IllegalArgumentException.class);
        verify(planRepository, never()).savePlan(any());
    }

    private CollectionEvent ingestEvent(String stage) {
        return CollectionEvent.of(EventType.CASE_INGESTED)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.STAGE, stage);
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
