package com.collection.engine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.collection.common.enums.AdvancementDecision;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.service.IdempotencyService;
import com.collection.common.spi.AdvancementPolicy;
import com.collection.common.spi.ExecutionGuard;
import com.collection.common.spi.StepResolver;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.spi.SpiInvoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 消息渠道（SMS/PUSH）happy-path 纯逻辑单测——不连库，全 mock SPI / Repository / EventBus。
 *
 * <p>验证阶段 1 最小可验收切片：一条消息渠道从七步管线执行到状态机推进至 PLAN_COMPLETED。 覆盖三段：
 *
 * <ol>
 *   <li>{@link StepExecutionOrchestrator} 七步管线（无观察期 → STEP_COMPLETED）
 *   <li>消息渠道带观察期 → STEP_WAITING 分支
 *   <li>{@link PlanLifecycleManager#onStepCompleted} 推进至 PLAN_COMPLETED
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MessageChannelHappyPathTest {

    private static final long PLAN_ID = 100L;
    private static final long STEP_ID = 200L;
    private static final long CASE_ID = 1002L;
    private static final long USER_ID = 9001L;

    // ── StepExecutionOrchestrator 依赖 ──
    @Mock private IdempotencyService idempotencyService;
    @Mock private PreFlightChecker preFlightChecker;
    @Mock private ExecutionGuard executionGuard;
    @Mock private StepResolver stepResolver;
    @Mock private ChannelGateway channelGateway;
    @Mock private ContextAssembler contextAssembler;
    @Mock private ContactPlanRepository planRepository;
    @Mock private TimelineRepository timelineRepository;
    @Mock private CollectionEventBus eventBus;
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
        plan.setUserId(USER_ID);
        plan.setStatus(PlanStatus.STEP_EXECUTING);

        step = new ContactPlanStep();
        step.setId(STEP_ID);
        step.setPlanId(PLAN_ID);
        step.setStepOrder(1);
        step.setChannelType(ChannelType.SMS);
        step.setStatus(StepStatus.EXECUTING);
        step.setRetryCount(0);
    }

    /** mock 出"一切放行 + 渠道成功"的 happy path 前置条件。 */
    private void stubHappyPath() {
        when(idempotencyService.acquire(anyString(), anyInt())).thenReturn(true);
        when(preFlightChecker.check(CASE_ID)).thenReturn(true);
        when(contextAssembler.assemble(plan, step))
                .thenReturn(ExecutionContext.builder().plan(plan).currentStep(step).build());
        when(executionGuard.evaluate(any())).thenReturn(GuardVerdict.allow());
        when(stepResolver.resolve(any()))
                .thenReturn(
                        StepCommand.builder()
                                .channelType(ChannelType.SMS)
                                .targetAddress("+639170000000")
                                .templateId("T_SMS_01")
                                .idempotencyKey("k-1")
                                .build());
        when(channelGateway.dispatch(any()))
                .thenReturn(
                        StepResult.builder()
                                .success(true)
                                .contactResult(ContactResult.DELIVERED)
                                .providerMsgId("MSG-1")
                                .build());
        // ⑤½ 回写前取消复检：计划仍非终态
        when(planRepository.findById(PLAN_ID)).thenReturn(plan);
    }

    @Test
    @DisplayName("无观察期消息渠道：发送成功 → STEP_COMPLETED + 发布 STEP_COMPLETED 事件")
    void messageChannel_noObservation_completesAndPublishes() {
        stubHappyPath();
        step.setObservationMinutes(0);

        orchestrator.executeStep(plan, step);

        verify(channelGateway).dispatch(any(StepCommand.class));
        verify(timelineRepository).writeTimeline(any());
        verify(planRepository)
                .updateStepStatus(STEP_ID, StepStatus.COMPLETED, ContactResult.DELIVERED);

        ArgumentCaptor<CollectionEvent> captor = ArgumentCaptor.forClass(CollectionEvent.class);
        verify(eventBus).publish(captor.capture());
        assertThat(captor.getValue().getEventType().name()).isEqualTo("STEP_COMPLETED");
        assertThat(captor.getValue().getLong(CollectionEvent.PLAN_ID)).isEqualTo(PLAN_ID);

        verify(planRepository, never())
                .updatePlanStatus(eq(PLAN_ID), eq(PlanStatus.STEP_WAITING), any());
    }

    @Test
    @DisplayName("带观察期消息渠道：发送成功 → STEP_WAITING（不立即发布完成事件）")
    void messageChannel_withObservation_entersWaiting() {
        stubHappyPath();
        step.setObservationMinutes(30);

        orchestrator.executeStep(plan, step);

        verify(timelineRepository).writeTimeline(any());
        verify(planRepository).updateStepTriggerTime(eq(STEP_ID), any(), eq(StepStatus.EXECUTING));
        verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.STEP_WAITING, null);
        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("重复事件：幂等锁未获取 → 直接静默退出，不触达")
    void duplicateEvent_skips() {
        when(idempotencyService.acquire(anyString(), anyInt())).thenReturn(false);

        orchestrator.executeStep(plan, step);

        verify(preFlightChecker, never()).check(any());
        verify(channelGateway, never()).dispatch(any());
        verify(eventBus, never()).publish(any());
    }

    // ───────── 状态机推进：消息渠道完成后走到 PLAN_COMPLETED ─────────

    @Test
    @DisplayName("步骤完成推进：AdvancementPolicy=PLAN_COMPLETED → 计划进入终态 PLAN_COMPLETED")
    void onStepCompleted_planCompleted() {
        ContactPlanRepository repo = org.mockito.Mockito.mock(ContactPlanRepository.class);
        com.collection.common.service.CaseService caseService =
                org.mockito.Mockito.mock(com.collection.common.service.CaseService.class);
        com.collection.common.spi.PlanFactory planFactory =
                org.mockito.Mockito.mock(com.collection.common.spi.PlanFactory.class);
        AdvancementPolicy advancementPolicy = org.mockito.Mockito.mock(AdvancementPolicy.class);
        com.collection.common.spi.ExhaustionPolicy exhaustionPolicy =
                org.mockito.Mockito.mock(com.collection.common.spi.ExhaustionPolicy.class);
        com.collection.common.service.PredictiveDialerService dialer =
                org.mockito.Mockito.mock(
                        com.collection.common.service.PredictiveDialerService.class);

        PlanLifecycleManager manager = new PlanLifecycleManager();
        inject(manager, "planRepository", repo);
        inject(manager, "caseService", caseService);
        inject(manager, "planFactory", planFactory);
        inject(manager, "advancementPolicy", advancementPolicy);
        inject(manager, "exhaustionPolicy", exhaustionPolicy);
        inject(manager, "predictiveDialerService", dialer);
        inject(manager, "spiInvoker", SpiInvoker.direct());

        when(repo.findPlanWithLock(PLAN_ID)).thenReturn(plan);
        when(repo.findStepById(STEP_ID)).thenReturn(step);
        step.setResult(ContactResult.DELIVERED);
        when(advancementPolicy.decide(any(), any())).thenReturn(AdvancementDecision.PLAN_COMPLETED);

        CollectionEvent event =
                CollectionEvent.of(com.collection.common.enums.EventType.STEP_COMPLETED)
                        .with(CollectionEvent.PLAN_ID, PLAN_ID)
                        .with(CollectionEvent.STEP_ID, STEP_ID);

        manager.onStepCompleted(event);

        verify(repo).updatePlanStatus(PLAN_ID, PlanStatus.PLAN_COMPLETED, null);
    }

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
