package com.collection.engine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.channel.ChannelGateway;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.CancelReason;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.DecisionLogRepository;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.service.IdempotencyService;
import com.collection.common.spi.ExecutionGuard;
import com.collection.common.spi.StepResolver;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.spi.SpiInvoker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * StepExecutionOrchestrator 七步管线分支单测（核心引擎规格 §3.1）。全 mock，不连库。 覆盖测试矩阵 #4-13（happy path #1-3 见
 * MessageChannelHappyPathTest）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepExecutionOrchestratorTest {

    private static final long PLAN_ID = 100L;
    private static final long STEP_ID = 200L;
    private static final long CASE_ID = 1002L;
    private static final java.time.LocalDate QUOTA_DATE = java.time.LocalDate.of(2026, 8, 24);

    @Mock private IdempotencyService idempotencyService;
    @Mock private PreFlightChecker preFlightChecker;
    @Mock private ExecutionGuard executionGuard;
    @Mock private StepResolver stepResolver;
    @Mock private ChannelGateway channelGateway;
    @Mock private ContextAssembler contextAssembler;
    @Mock private ContactPlanRepository planRepository;
    @Mock private TimelineRepository timelineRepository;
    @Mock private StepOutcomeRecorder stepOutcomeRecorder;
    @Mock private DecisionLogRepository decisionLogRepository;
    @Mock private CollectionEventBus eventBus;
    @Mock private com.collection.common.service.ComplianceCounterService complianceCounterService;
    @Spy private EngineProperties props = new EngineProperties();
    @Spy private SpiInvoker spiInvoker = SpiInvoker.direct();

    @InjectMocks private StepExecutionOrchestrator orchestrator;

    private ContactPlan plan;
    private ContactPlanStep step;

    @BeforeEach
    void setUp() {
        plan = new ContactPlan();
        plan.setId(PLAN_ID);
        plan.setCaseId(CASE_ID);
        plan.setUserId(9001L);
        plan.setStatus(PlanStatus.STEP_EXECUTING);

        step = new ContactPlanStep();
        step.setId(STEP_ID);
        step.setPlanId(PLAN_ID);
        step.setStepOrder(1);
        step.setChannelType(ChannelType.SMS);
        step.setStatus(StepStatus.EXECUTING);
        step.setRetryCount(0);

        // 默认放行到渠道调度前的各步骤（具体测试按需覆盖）
        when(planRepository.markStepExecuting(any())).thenReturn(true);
        when(idempotencyService.acquire(anyString(), anyInt())).thenReturn(true);
        when(preFlightChecker.inspect(CASE_ID)).thenReturn(PreFlightResult.passed(liveCaseInfo()));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(ExecutionContext.builder().plan(plan).currentStep(step).build());
        when(executionGuard.evaluate(any())).thenReturn(GuardVerdict.allow());
        when(planRepository.findById(PLAN_ID)).thenReturn(plan); // ⑤½ 复检默认非终态
        when(planRepository.transitionStepStatus(
                        any(),
                        any(StepStatus.class),
                        any(StepStatus.class),
                        any(ContactResult.class)))
                .thenReturn(true);
        when(stepOutcomeRecorder.recordTerminal(
                        any(),
                        any(),
                        any(StepStatus.class),
                        any(StepStatus.class),
                        any(ContactResult.class),
                        any(ChannelType.class),
                        any(),
                        any()))
                .thenReturn(true);
        when(stepOutcomeRecorder.recordWaiting(
                        any(),
                        any(),
                        any(ChannelType.class),
                        any(ContactResult.class),
                        any(),
                        any()))
                .thenReturn(true);
        when(stepOutcomeRecorder.recordStrategySkipped(any(), any())).thenReturn(true);
    }

    /** 步骤② 实时读到的案件数据：dpd/余额比快照新，stage 故意与计划不同以验证不被覆盖。 */
    private CaseInfo liveCaseInfo() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(CASE_ID);
        info.setDpd(58);
        info.setStage(Stage.S3);
        info.setTotalOutstanding(new BigDecimal("1500.00"));
        return info;
    }

    private void stubResolver(ChannelType ch) {
        when(stepResolver.resolve(any()))
                .thenReturn(
                        StepCommand.builder()
                                .channelType(ch)
                                .targetAddress("addr")
                                .templateId("T")
                                .idempotencyKey("k")
                                .build());
    }

    private void stubDispatch(StepResult result) {
        when(channelGateway.dispatch(any())).thenReturn(result);
    }

    private StepResult ok(ContactResult cr) {
        return StepResult.builder().success(true).contactResult(cr).providerMsgId("M").build();
    }

    private StepResult fail(boolean retryable) {
        return StepResult.builder()
                .success(false)
                .contactResult(ContactResult.FAILED)
                .errorCode("E")
                .retryable(retryable)
                .build();
    }

    private void verifyTerminalRecorded(StepStatus status, ContactResult result) {
        verify(stepOutcomeRecorder)
                .recordTerminal(
                        eq(plan),
                        eq(step),
                        eq(StepStatus.EXECUTING),
                        eq(status),
                        eq(result),
                        any(ChannelType.class),
                        any(),
                        any());
    }

    @Test
    @DisplayName("#4 PUSH 无观察期成功 → STEP_COMPLETED + 发布")
    void push_noObservation_completes() {
        stubResolver(ChannelType.PUSH);
        stubDispatch(ok(ContactResult.DELIVERED));
        step.setChannelType(ChannelType.PUSH);
        step.setObservationMinutes(0);

        orchestrator.executeStep(plan, step);

        verifyTerminalRecorded(StepStatus.COMPLETED, ContactResult.DELIVERED);
        verify(eventBus).publish(any());
    }

    @Test
    @DisplayName("#5 系统守卫发现已还款 → 取消计划，不触达也不推进")
    void preflightRepaid_cancelsPlan() {
        when(preFlightChecker.inspect(CASE_ID))
                .thenReturn(PreFlightResult.blocked(CancelReason.REPAID, liveCaseInfo()));

        orchestrator.executeStep(plan, step);

        verify(channelGateway, never()).dispatch(any());
        verify(eventBus, never()).publish(any());
        verify(planRepository)
                .updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
    }

    @Test
    @DisplayName("#5g 渲染前刷新日变字段 → Resolver 拿到实时 dpd / 余额，stage 仍随计划")
    void refreshesVolatileFieldsBeforeResolve() {
        CaseContext stale = new CaseContext();
        stale.setCaseId(CASE_ID);
        stale.setDpd(31);
        stale.setStage(Stage.S4);
        stale.setTotalOutstanding(new BigDecimal("1000.00"));
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(stale);
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(
                        ExecutionContext.builder()
                                .plan(plan)
                                .currentStep(step)
                                .contextSnapshot(snapshot)
                                .build());
        stubResolver(ChannelType.SMS);
        stubDispatch(ok(ContactResult.DELIVERED));

        orchestrator.executeStep(plan, step);

        ArgumentCaptor<ExecutionContext> captor = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(stepResolver).resolve(captor.capture());
        CaseContext used = captor.getValue().getContextSnapshot().getCaseContext();
        assertThat(used.getDpd()).isEqualTo(58);
        assertThat(used.getTotalOutstanding()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(used.getStage()).isEqualTo(Stage.S4);
    }

    @Test
    @DisplayName("#6 业务守卫拦截 → SKIPPED(COMPLIANCE_BLOCKED) + 推进")
    void guardBlocked_skipped() {
        when(executionGuard.evaluate(any()))
                .thenReturn(GuardVerdict.block("freq", "FREQUENCY_LIMIT"));

        orchestrator.executeStep(plan, step);

        verifyTerminalRecorded(StepStatus.SKIPPED, ContactResult.COMPLIANCE_BLOCKED);
        verify(eventBus).publish(any());
        verify(channelGateway, never()).dispatch(any());
    }

    @Test
    @DisplayName("#6a 静默时段 → 延后执行，不跳过也不推进")
    void quietHours_deferred() {
        LocalDateTime resumeAt = LocalDateTime.of(2026, 7, 17, 8, 0);
        when(executionGuard.evaluate(any()))
                .thenReturn(GuardVerdict.defer("quiet", "TIME_WINDOW", resumeAt));

        orchestrator.executeStep(plan, step);

        verify(planRepository).updateStepTriggerTime(STEP_ID, resumeAt, StepStatus.PENDING);
        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.STEP_SCHEDULED, null);
        verify(planRepository, never())
                .updateStepStatus(eq(STEP_ID), eq(StepStatus.SKIPPED), any());
        verify(eventBus, never()).publish(any());
        verify(channelGateway, never()).dispatch(any());
        verify(idempotencyService).release(eq("lock:plan:" + PLAN_ID + ":1:0"));
    }

    @Test
    @DisplayName("#6b dispatch 前 PreFlight 读取异常 → 释放执行锁并上抛，供 NACK 重投")
    void preFlightFailure_releasesExecutionLockBeforeRethrow() {
        when(preFlightChecker.inspect(CASE_ID)).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> orchestrator.executeStep(plan, step))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(idempotencyService).release(eq("lock:plan:" + PLAN_ID + ":1:0"));
        verify(channelGateway, never()).dispatch(any());
    }

    @Test
    @DisplayName("#6d 调用渠道前步骤已被终结 → 放弃执行，不得触达")
    void claimLostBeforeDispatch_doesNotTouch() {
        // prepareStepDue 提交后到此处之间，回调或超时路径可能已收敛该步骤；
        // 继续执行就是一次重复触达，这是催收场景最不可接受的失效方式。
        when(planRepository.markStepExecuting(STEP_ID)).thenReturn(false);

        orchestrator.executeStep(plan, step);

        verify(channelGateway, never()).dispatch(any());
        verify(contextAssembler, never()).assemble(any(), any());
        verify(timelineRepository, never()).writeTimeline(any());
    }

    @Test
    @DisplayName("#6c dispatch 后写库异常 → 保留执行锁，避免不确定结果重复触达")
    void postDispatchFailure_keepsExecutionLock() {
        stubResolver(ChannelType.SMS);
        stubDispatch(ok(ContactResult.DELIVERED));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(planRepository)
                .markStepDispatched(STEP_ID);

        assertThatThrownBy(() -> orchestrator.executeStep(plan, step))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(channelGateway).dispatch(any());
        verify(idempotencyService, never()).release(anyString());
    }

    @Test
    @DisplayName("#7 业务守卫抛异常（fail-close）→ SKIPPED + 推进")
    void guardException_failCloseSkipped() {
        when(executionGuard.evaluate(any())).thenThrow(new RuntimeException("guard down"));

        orchestrator.executeStep(plan, step);

        verifyTerminalRecorded(StepStatus.SKIPPED, ContactResult.COMPLIANCE_BLOCKED);
        verify(eventBus).publish(any());
        verify(channelGateway, never()).dispatch(any());
    }

    @Test
    @DisplayName("#7a 业务守卫返回非法 null（fail-close）→ SKIPPED + 推进，不得 NPE 上抛")
    void guardNullVerdict_failCloseSkipped() {
        when(executionGuard.evaluate(any())).thenReturn(null);

        orchestrator.executeStep(plan, step);

        verifyTerminalRecorded(StepStatus.SKIPPED, ContactResult.COMPLIANCE_BLOCKED);
        verify(eventBus).publish(any());
        verify(channelGateway, never()).dispatch(any());
    }

    @Test
    @DisplayName("#8a StepResolver 抛异常 → FAILED + 推进")
    void resolverException_failed() {
        when(stepResolver.resolve(any())).thenThrow(new RuntimeException("resolve error"));

        orchestrator.executeStep(plan, step);

        verifyTerminalRecorded(StepStatus.FAILED, ContactResult.FAILED);
        verify(eventBus).publish(any());
        verify(channelGateway, never()).dispatch(any());
    }

    @Test
    @DisplayName("#8b StepResolver 返回 null → SKIPPED + 推进但不写 timeline（策略性跳过）")
    void resolverNull_skipped() {
        when(stepResolver.resolve(any())).thenReturn(null);

        orchestrator.executeStep(plan, step);

        // 状态迁移与 STEP_COMPLETED 入箱必须同事务，故走 recordStrategySkipped 而非裸 transitionStepStatus
        verify(stepOutcomeRecorder).recordStrategySkipped(plan, step);
        verify(eventBus).publish(any());
        verify(timelineRepository, never()).writeTimeline(any());
        verify(channelGateway, never()).dispatch(any());
    }

    @Test
    @DisplayName("#9 ChannelGateway 抛异常 → 结果未知，FAILED 推进")
    void channelException_unknownOutcomeFailed() {
        stubResolver(ChannelType.SMS);
        when(channelGateway.dispatch(any())).thenThrow(new RuntimeException("gateway down"));

        orchestrator.executeStep(plan, step);

        verifyTerminalRecorded(StepStatus.FAILED, ContactResult.FAILED);
        verify(planRepository, never()).incrementRetryCount(STEP_ID);
        verify(planRepository, never())
                .updateStepTriggerTime(eq(STEP_ID), any(), eq(StepStatus.PENDING));
    }

    @Test
    @DisplayName("#10 发送失败 retryable 且未超上限 → 退避重试，保持 STEP_EXECUTING")
    void retryableUnderLimit_reschedule() {
        stubResolver(ChannelType.SMS);
        stubDispatch(fail(true));
        step.setRetryCount(0); // < maxRetryCount(3)

        orchestrator.executeStep(plan, step);

        verify(planRepository).incrementRetryCount(STEP_ID);
        verify(planRepository).updateStepTriggerTime(eq(STEP_ID), any(), eq(StepStatus.PENDING));
        verify(planRepository, never()).updateStepStatus(eq(STEP_ID), eq(StepStatus.FAILED), any());
    }

    @Test
    @DisplayName("#11 发送失败超过 maxRetry → FAILED + 推进")
    void retryExceeded_failed() {
        stubResolver(ChannelType.SMS);
        stubDispatch(fail(true));
        step.setRetryCount(3); // == maxRetryCount(3)，3<3 为假

        orchestrator.executeStep(plan, step);

        verifyTerminalRecorded(StepStatus.FAILED, ContactResult.FAILED);
        verify(eventBus).publish(any());
        verify(planRepository, never()).incrementRetryCount(STEP_ID);
    }

    @Test
    @DisplayName("②-D20 发送失败不可重试(retryable=false) → FAILED + 推进，不退避")
    void notRetryable_failedNoBackoff() {
        stubResolver(ChannelType.SMS);
        stubDispatch(fail(false));
        step.setRetryCount(0); // 即便未超上限，retryable=false 也不重试

        orchestrator.executeStep(plan, step);

        verifyTerminalRecorded(StepStatus.FAILED, ContactResult.FAILED);
        verify(eventBus).publish(any());
        verify(planRepository, never()).incrementRetryCount(STEP_ID);
        verify(planRepository, never())
                .updateStepTriggerTime(eq(STEP_ID), any(), eq(StepStatus.PENDING));
    }

    // ── F2：Guard 预占的日频配额，在确认未发出的终态必须归还 ──

    /** 生产单渠道日限为 1；不归还就等于一次瞬态故障吃掉该客户当天该渠道的唯一名额。 */
    @Test
    @DisplayName("F2 retryable 终态（证明未写给供应商）→ 归还配额")
    void retryableTerminalFailure_releasesQuota() {
        guardAllowsConsumingQuota();
        stubResolver(ChannelType.SMS);
        stubDispatch(fail(true));
        step.setRetryCount(3); // 已达 maxRetryCount，本次即终态

        orchestrator.executeStep(plan, step);

        verify(complianceCounterService).release(eq(9001L), eq("SMS"), eq(QUOTA_DATE));
    }

    /** 读超时 / 写后中断 / 5xx 属结果未知，可能已送达：重复骚扰是合规事故，宁可少发一次。 */
    @Test
    @DisplayName("F2 结果未知的终态 → 不归还配额")
    void unknownOutcomeTerminalFailure_keepsQuota() {
        guardAllowsConsumingQuota();
        stubResolver(ChannelType.SMS);
        stubDispatch(fail(false));

        orchestrator.executeStep(plan, step);

        verify(complianceCounterService, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName("F2 退避重试尚未终结 → 配额继续为这次触达持有，不归还")
    void retryScheduled_keepsQuotaHeld() {
        guardAllowsConsumingQuota();
        stubResolver(ChannelType.SMS);
        stubDispatch(fail(true));
        step.setRetryCount(0); // 未超上限，走退避

        orchestrator.executeStep(plan, step);

        verify(planRepository).incrementRetryCount(STEP_ID);
        verify(complianceCounterService, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName("F2 供应商已受理 → 配额如实消耗，不归还")
    void successfulDispatch_keepsQuota() {
        guardAllowsConsumingQuota();
        stubResolver(ChannelType.SMS);
        stubDispatch(ok(ContactResult.DELIVERED));

        orchestrator.executeStep(plan, step);

        verify(complianceCounterService, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName("F2 解析失败 → 根本没走到渠道，归还配额")
    void resolverException_releasesQuota() {
        guardAllowsConsumingQuota();
        when(stepResolver.resolve(any())).thenThrow(new RuntimeException("resolve error"));

        orchestrator.executeStep(plan, step);

        verify(complianceCounterService).release(eq(9001L), eq("SMS"), eq(QUOTA_DATE));
    }

    @Test
    @DisplayName("F2 策略性跳过 → 未触达，归还配额")
    void resolverNoOp_releasesQuota() {
        guardAllowsConsumingQuota();
        when(stepResolver.resolve(any())).thenReturn(null);

        orchestrator.executeStep(plan, step);

        verify(complianceCounterService).release(eq(9001L), eq("SMS"), eq(QUOTA_DATE));
    }

    /** Guard 未声明预占（自定义实现或未配日限）时引擎不得擅自归还，否则会把计数扣成负数。 */
    @Test
    @DisplayName("F2 Guard 未预占配额 → 引擎不归还")
    void noReservation_neverReleases() {
        stubResolver(ChannelType.SMS);
        stubDispatch(fail(true));
        step.setRetryCount(3);

        orchestrator.executeStep(plan, step);

        verify(complianceCounterService, never()).release(any(), any(), any());
    }

    private void guardAllowsConsumingQuota() {
        when(executionGuard.evaluate(any()))
                .thenReturn(
                        GuardVerdict.allowAfterConsumingQuota(
                                new GuardVerdict.QuotaReservation(9001L, "SMS", QUOTA_DATE)));
    }

    @Test
    @DisplayName("#12 回写前计划已取消（⑤½ 复检）→ 仅记录 timeline，不推进")
    void cancelledDuringDispatch_recordOnly() {
        stubResolver(ChannelType.SMS);
        stubDispatch(ok(ContactResult.DELIVERED));
        ContactPlan cancelled = new ContactPlan();
        cancelled.setId(PLAN_ID);
        cancelled.setStatus(PlanStatus.PLAN_CANCELLED);
        when(planRepository.findById(PLAN_ID)).thenReturn(cancelled);

        orchestrator.executeStep(plan, step);

        verify(timelineRepository).writeTimeline(any());
        verify(planRepository, never())
                .updateStepStatus(eq(STEP_ID), eq(StepStatus.COMPLETED), any());
        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("#13 异步渠道(AI_CALL) 成功 → 保持 STEP_EXECUTING + 注册回调超时")
    void asyncChannel_registersTimeout() {
        stubResolver(ChannelType.AI_CALL);
        stubDispatch(ok(ContactResult.ANSWERED));
        step.setChannelType(ChannelType.AI_CALL);

        LocalDateTime before = LocalDateTime.now();
        orchestrator.executeStep(plan, step);

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(planRepository).updateStepTimeoutTime(eq(STEP_ID), at.capture());
        long minutes = Duration.between(before, at.getValue()).toMinutes();
        assertThat(minutes).isBetween(29L, 31L); // 默认 engine.step.callbackTimeoutMinutes=30
        verify(timelineRepository).writeTimeline(any());
        verify(eventBus, never()).publish(any());
        verify(planRepository, never())
                .updatePlanStatus(eq(PLAN_ID), eq(PlanStatus.STEP_WAITING), any());
    }

    @Test
    @DisplayName("#28 退避算法：第 3 次重试延迟 = base(30)*factor(2)^3 = 240s")
    void backoff_growsByAttempt() {
        stubResolver(ChannelType.SMS);
        stubDispatch(fail(true));
        step.setRetryCount(2); // newCount=3 → 30*2^3=240s，仍 < maxRetryCount(3)

        LocalDateTime before = LocalDateTime.now();
        orchestrator.executeStep(plan, step);

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(planRepository)
                .updateStepTriggerTime(eq(STEP_ID), at.capture(), eq(StepStatus.PENDING));
        long delaySec = Duration.between(before, at.getValue()).getSeconds();
        assertThat(delaySec).isBetween(240L, 250L);
    }

    @Test
    @DisplayName("#29 退避封顶：超过 retryMaxIntervalSeconds 取上限值")
    void backoff_cappedAtMax() {
        props.getStep().setRetryMaxIntervalSeconds(100); // 240 → 封顶 100
        stubResolver(ChannelType.SMS);
        stubDispatch(fail(true));
        step.setRetryCount(2); // newCount=3 → 240s，被封顶到 100s

        LocalDateTime before = LocalDateTime.now();
        orchestrator.executeStep(plan, step);

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(planRepository)
                .updateStepTriggerTime(eq(STEP_ID), at.capture(), eq(StepStatus.PENDING));
        long delaySec = Duration.between(before, at.getValue()).getSeconds();
        assertThat(delaySec).isBetween(100L, 110L);
    }

    @Test
    @DisplayName("#30 异步渠道回调超时：metadata.timeoutMinutes 覆盖默认值(30)")
    void asyncTimeout_metadataOverridesDefault() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_TIMEOUT_MINUTES, 15);
        when(stepResolver.resolve(any()))
                .thenReturn(
                        StepCommand.builder()
                                .channelType(ChannelType.AI_CALL)
                                .targetAddress("addr")
                                .templateId("T")
                                .idempotencyKey("k")
                                .metadata(meta)
                                .build());
        stubDispatch(ok(ContactResult.ANSWERED));
        step.setChannelType(ChannelType.AI_CALL);

        LocalDateTime before = LocalDateTime.now();
        orchestrator.executeStep(plan, step);

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(planRepository).updateStepTimeoutTime(eq(STEP_ID), at.capture());
        long minutes = Duration.between(before, at.getValue()).toMinutes();
        assertThat(minutes).isBetween(14L, 16L); // 覆盖为 15min，明显区别于默认 60min
    }

    @Test
    @DisplayName(
            "#31 幂等 key 含 retryCount：key = lock:plan:planId:stepOrder:retryCount（保证重试不被自身幂等拦截）")
    void idempotencyKey_includesRetryCount() {
        stubResolver(ChannelType.SMS);
        stubDispatch(ok(ContactResult.DELIVERED));
        step.setStepOrder(1);
        step.setRetryCount(2);
        step.setObservationMinutes(0);

        orchestrator.executeStep(plan, step);

        verify(idempotencyService).acquire(eq("lock:plan:" + PLAN_ID + ":1:2"), anyInt());
    }

    @Test
    @DisplayName("#32 ⑤½ 复检：回写前计划已不存在(null) → 仅记录 timeline，不推进")
    void reloadNull_recordOnly() {
        stubResolver(ChannelType.SMS);
        stubDispatch(ok(ContactResult.DELIVERED));
        when(planRepository.findById(PLAN_ID)).thenReturn(null);

        orchestrator.executeStep(plan, step);

        verify(timelineRepository).writeTimeline(any());
        verify(planRepository, never())
                .updateStepStatus(eq(STEP_ID), eq(StepStatus.COMPLETED), any());
        verify(eventBus, never()).publish(any());
    }
}
