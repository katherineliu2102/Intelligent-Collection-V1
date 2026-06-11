package com.collection.engine.lifecycle;

import com.collection.common.channel.ChannelGateway;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.service.IdempotencyService;
import com.collection.common.spi.ExecutionGuard;
import com.collection.common.spi.StepResolver;
import com.collection.engine.config.EngineProperties;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StepExecutionOrchestrator 七步管线分支单测（核心引擎规格 §3.1）。全 mock，不连库。
 * 覆盖测试矩阵 #4-13（happy path #1-3 见 MessageChannelHappyPathTest）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepExecutionOrchestratorTest {

    private static final long PLAN_ID = 100L;
    private static final long STEP_ID = 200L;
    private static final long CASE_ID = 1002L;

    @Mock private IdempotencyService idempotencyService;
    @Mock private PreFlightChecker preFlightChecker;
    @Mock private ExecutionGuard executionGuard;
    @Mock private StepResolver stepResolver;
    @Mock private ChannelGateway channelGateway;
    @Mock private ContextAssembler contextAssembler;
    @Mock private ContactPlanRepository planRepository;
    @Mock private TimelineRepository timelineRepository;
    @Mock private CollectionEventBus eventBus;
    @Spy  private EngineProperties props = new EngineProperties();

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
        when(idempotencyService.acquire(anyString(), anyInt())).thenReturn(true);
        when(preFlightChecker.check(CASE_ID)).thenReturn(true);
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(ExecutionContext.builder().plan(plan).currentStep(step).build());
        when(executionGuard.evaluate(any())).thenReturn(GuardVerdict.allow());
        when(planRepository.findById(PLAN_ID)).thenReturn(plan); // ⑤½ 复检默认非终态
    }

    private void stubResolver(ChannelType ch) {
        when(stepResolver.resolve(any())).thenReturn(StepCommand.builder()
                .channelType(ch).targetAddress("addr").templateId("T").idempotencyKey("k").build());
    }

    private void stubDispatch(StepResult result) {
        when(channelGateway.dispatch(any())).thenReturn(result);
    }

    private StepResult ok(ContactResult cr) {
        return StepResult.builder().success(true).contactResult(cr).providerMsgId("M").build();
    }

    private StepResult fail(boolean retryable) {
        return StepResult.builder().success(false).contactResult(ContactResult.FAILED)
                .errorCode("E").retryable(retryable).build();
    }

    @Test
    @DisplayName("#4 PUSH 无观察期成功 → STEP_COMPLETED + 发布")
    void push_noObservation_completes() {
        stubResolver(ChannelType.PUSH);
        stubDispatch(ok(ContactResult.DELIVERED));
        step.setChannelType(ChannelType.PUSH);
        step.setObservationMinutes(0);

        orchestrator.executeStep(plan, step);

        verify(planRepository).updateStepStatus(STEP_ID, StepStatus.COMPLETED, ContactResult.DELIVERED);
        verify(eventBus).publish(any());
    }

    @Test
    @DisplayName("#5 系统守卫不通过（已还款/冻结/读失败 fail-close）→ 静默退出")
    void preflightFail_silentExit() {
        when(preFlightChecker.check(CASE_ID)).thenReturn(false);

        orchestrator.executeStep(plan, step);

        verify(channelGateway, never()).dispatch(any());
        verify(eventBus, never()).publish(any());
        verify(planRepository, never()).updateStepStatus(eq(STEP_ID), eq(StepStatus.EXECUTING), any());
    }

    @Test
    @DisplayName("#6 业务守卫拦截 → SKIPPED(COMPLIANCE_BLOCKED) + 推进")
    void guardBlocked_skipped() {
        when(executionGuard.evaluate(any())).thenReturn(GuardVerdict.block("freq", "FREQUENCY_LIMIT"));

        orchestrator.executeStep(plan, step);

        verify(planRepository).updateStepStatus(STEP_ID, StepStatus.SKIPPED, ContactResult.COMPLIANCE_BLOCKED);
        verify(eventBus).publish(any());
        verify(channelGateway, never()).dispatch(any());
    }

    @Test
    @DisplayName("#7 业务守卫抛异常（fail-close）→ SKIPPED + 推进")
    void guardException_failCloseSkipped() {
        when(executionGuard.evaluate(any())).thenThrow(new RuntimeException("guard down"));

        orchestrator.executeStep(plan, step);

        verify(planRepository).updateStepStatus(STEP_ID, StepStatus.SKIPPED, ContactResult.COMPLIANCE_BLOCKED);
        verify(eventBus).publish(any());
        verify(channelGateway, never()).dispatch(any());
    }

    @Test
    @DisplayName("#8a StepResolver 抛异常 → FAILED + 推进")
    void resolverException_failed() {
        when(stepResolver.resolve(any())).thenThrow(new RuntimeException("resolve error"));

        orchestrator.executeStep(plan, step);

        verify(planRepository).updateStepStatus(STEP_ID, StepStatus.FAILED, ContactResult.FAILED);
        verify(eventBus).publish(any());
        verify(channelGateway, never()).dispatch(any());
    }

    @Test
    @DisplayName("#8b StepResolver 返回 null → FAILED + 推进")
    void resolverNull_failed() {
        when(stepResolver.resolve(any())).thenReturn(null);

        orchestrator.executeStep(plan, step);

        verify(planRepository).updateStepStatus(STEP_ID, StepStatus.FAILED, ContactResult.FAILED);
        verify(eventBus).publish(any());
    }

    @Test
    @DisplayName("#9 ChannelGateway 抛异常 → 视为 retryable → 退避重试")
    void channelException_retryable() {
        stubResolver(ChannelType.SMS);
        when(channelGateway.dispatch(any())).thenThrow(new RuntimeException("gateway down"));

        orchestrator.executeStep(plan, step);

        verify(planRepository).incrementRetryCount(STEP_ID);
        verify(planRepository).updateStepTriggerTime(eq(STEP_ID), any(), eq(StepStatus.PENDING));
        verify(planRepository, never()).updateStepStatus(eq(STEP_ID), eq(StepStatus.FAILED), any());
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

        verify(planRepository).updateStepStatus(STEP_ID, StepStatus.FAILED, ContactResult.FAILED);
        verify(eventBus).publish(any());
        verify(planRepository, never()).incrementRetryCount(STEP_ID);
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
        verify(planRepository, never()).updateStepStatus(eq(STEP_ID), eq(StepStatus.COMPLETED), any());
        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("#13 异步渠道(AI_CALL) 成功 → 保持 STEP_EXECUTING + 注册回调超时")
    void asyncChannel_registersTimeout() {
        stubResolver(ChannelType.AI_CALL);
        stubDispatch(ok(ContactResult.ANSWERED));
        step.setChannelType(ChannelType.AI_CALL);

        orchestrator.executeStep(plan, step);

        verify(planRepository).updateStepTimeoutTime(eq(STEP_ID), any());
        verify(eventBus, never()).publish(any());
        verify(planRepository, never()).updatePlanStatus(eq(PLAN_ID), eq(PlanStatus.STEP_WAITING), any());
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
        verify(planRepository).updateStepTriggerTime(eq(STEP_ID), at.capture(), eq(StepStatus.PENDING));
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
        verify(planRepository).updateStepTriggerTime(eq(STEP_ID), at.capture(), eq(StepStatus.PENDING));
        long delaySec = Duration.between(before, at.getValue()).getSeconds();
        assertThat(delaySec).isBetween(100L, 110L);
    }

    @Test
    @DisplayName("#30 异步渠道回调超时：metadata.timeoutMinutes 覆盖默认值(60)")
    void asyncTimeout_metadataOverridesDefault() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_TIMEOUT_MINUTES, 15);
        when(stepResolver.resolve(any())).thenReturn(StepCommand.builder()
                .channelType(ChannelType.AI_CALL).targetAddress("addr").templateId("T")
                .idempotencyKey("k").metadata(meta).build());
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
    @DisplayName("#31 幂等 key 含 retryCount：key = planId:stepOrder:retryCount（保证重试不被自身幂等拦截）")
    void idempotencyKey_includesRetryCount() {
        stubResolver(ChannelType.SMS);
        stubDispatch(ok(ContactResult.DELIVERED));
        step.setStepOrder(1);
        step.setRetryCount(2);
        step.setObservationMinutes(0);

        orchestrator.executeStep(plan, step);

        verify(idempotencyService).acquire(eq(PLAN_ID + ":1:2"), anyInt());
    }

    @Test
    @DisplayName("#32 ⑤½ 复检：回写前计划已不存在(null) → 仅记录 timeline，不推进")
    void reloadNull_recordOnly() {
        stubResolver(ChannelType.SMS);
        stubDispatch(ok(ContactResult.DELIVERED));
        when(planRepository.findById(PLAN_ID)).thenReturn(null);

        orchestrator.executeStep(plan, step);

        verify(timelineRepository).writeTimeline(any());
        verify(planRepository, never()).updateStepStatus(eq(STEP_ID), eq(StepStatus.COMPLETED), any());
        verify(eventBus, never()).publish(any());
    }
}
