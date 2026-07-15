package com.collection.engine.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.dto.ExhaustionResult;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.enums.AdvancementDecision;
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
import com.collection.common.service.CaseService;
import com.collection.common.service.PredictiveDialerService;
import com.collection.common.spi.AdvancementPolicy;
import com.collection.common.spi.ExecutionGuard;
import com.collection.common.spi.ExhaustionPolicy;
import com.collection.common.spi.PlanFactory;
<<<<<<< HEAD
=======
import com.collection.common.spi.StepResolver;
>>>>>>> origin/ca_branch
import com.collection.engine.bus.InMemoryIdempotencyService;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.lifecycle.ContextAssembler;
import com.collection.engine.lifecycle.EventConsumerDispatcher;
import com.collection.engine.lifecycle.PlanLifecycleManager;
import com.collection.engine.lifecycle.PreFlightChecker;
import com.collection.engine.lifecycle.StepExecutionOrchestrator;
import com.collection.engine.spi.SpiInvoker;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 链路④ 异步回调链 L1 内存集成（差集 D3）。
 *
<<<<<<< HEAD
 * <p>用同步内存总线 + 内存仓储驱动真实引擎组件，覆盖<b>电话类（AI_CALL）渠道</b>的异步语义： 发送后保持 {@code STEP_EXECUTING}
 * 并注册超时哨兵，随后分别由 {@code CHANNEL_CALLBACK}（回调完成） 与 {@code CALLBACK_TIMEOUT}（超时兜底）驱动步骤结转 + 计划推进。复用
=======
 * <p>用同步内存总线 + 内存仓储驱动真实引擎组件，覆盖<b>电话类（AI_CALL）渠道</b>的异步语义：
 * 发送后保持 {@code STEP_EXECUTING} 并注册超时哨兵，随后分别由 {@code CHANNEL_CALLBACK}（回调完成）
 * 与 {@code CALLBACK_TIMEOUT}（超时兜底）驱动步骤结转 + 计划推进。复用
>>>>>>> origin/ca_branch
 * {@link FullChainIntegrationTest} 的内存仓储/总线/服务替身（同包可见），仅替换为单步 AI_CALL 计划工厂。
 */
class AsyncCallbackChainL1Test {

    private static final long CASE_ID = 1002L;
    private static final long USER_ID = 9001L;

    private FullChainIntegrationTest.SyncEventBus bus;
    private FullChainIntegrationTest.InMemoryPlanRepository planRepo;

    @BeforeEach
    void wire() {
        bus = new FullChainIntegrationTest.SyncEventBus();
        planRepo = new FullChainIntegrationTest.InMemoryPlanRepository();
        FullChainIntegrationTest.InMemoryTimelineRepository timelineRepo =
                new FullChainIntegrationTest.InMemoryTimelineRepository();

        EngineProperties props = new EngineProperties();
        CaseService caseService = new FullChainIntegrationTest.StubCaseService();

        ContextAssembler contextAssembler = new ContextAssembler();
        inject(contextAssembler, "timelineRepository", timelineRepo);
        inject(contextAssembler, "props", props);

        PreFlightChecker preFlight = new PreFlightChecker();
        inject(preFlight, "caseService", caseService);

        StepExecutionOrchestrator orchestrator = new StepExecutionOrchestrator();
        inject(orchestrator, "idempotencyService", new InMemoryIdempotencyService());
        inject(orchestrator, "preFlightChecker", preFlight);
        inject(orchestrator, "executionGuard", (ExecutionGuard) ctx -> GuardVerdict.allow());
        inject(orchestrator, "stepResolver", new FullChainIntegrationTest.RecordingStepResolver());
        inject(orchestrator, "channelGateway", new FullChainIntegrationTest.StubChannelGateway());
        inject(orchestrator, "contextAssembler", contextAssembler);
        inject(orchestrator, "planRepository", planRepo);
        inject(orchestrator, "timelineRepository", timelineRepo);
        inject(orchestrator, "eventBus", bus);
        inject(orchestrator, "spiInvoker", SpiInvoker.direct());
        inject(orchestrator, "props", props);

        PlanLifecycleManager manager = new PlanLifecycleManager();
        inject(manager, "planRepository", planRepo);
        inject(manager, "caseService", caseService);
        inject(manager, "planFactory", new AiCallPlanFactory());
        inject(
                manager,
                "advancementPolicy",
                (AdvancementPolicy) (ctx, r) -> AdvancementDecision.ADVANCE_NEXT);
        inject(
                manager,
                "exhaustionPolicy",
                (ExhaustionPolicy) (plan, info, snap) -> ExhaustionResult.complete("done"));
        inject(manager, "predictiveDialerService", (PredictiveDialerService) userId -> {});
        inject(manager, "spiInvoker", SpiInvoker.direct());

        EventConsumerDispatcher dispatcher = new EventConsumerDispatcher();
        inject(dispatcher, "eventBus", bus);
        inject(dispatcher, "manager", manager);
        inject(dispatcher, "orchestrator", orchestrator);
        dispatcher.registerHandlers();
    }

    @Test
    @DisplayName("D3 AI_CALL 发送 → 保持 STEP_EXECUTING + 注册超时；回调 ANSWERED → 步骤完成 + 计划推进完成")
    void asyncCallback_keepsExecuting_thenCompletes() {
        ContactPlanStep async = ingestAndDriveAsyncStep();

        // 发送后异步态：保持 STEP_EXECUTING，注册了超时哨兵，未结转
        assertThat(planOf().getStatus()).isEqualTo(PlanStatus.STEP_EXECUTING);
        assertThat(async.getStatus()).isEqualTo(StepStatus.EXECUTING);
        assertThat(async.getTimeoutTime()).isNotNull();

        // 供应商回调 ANSWERED → 步骤完成 → 推进 → 穷尽 COMPLETE → 计划完成
<<<<<<< HEAD
        bus.publish(callbackEvent(EventType.CHANNEL_CALLBACK, async).with("result", "ANSWERED"));
=======
        bus.publish(
                callbackEvent(EventType.CHANNEL_CALLBACK, async).with("result", "ANSWERED"));
>>>>>>> origin/ca_branch
        bus.drainAll();

        assertThat(async.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(async.getResult()).isEqualTo(ContactResult.ANSWERED);
        assertThat(planOf().getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED);
    }

    @Test
    @DisplayName("D3 AI_CALL 发送后超时 → CALLBACK_TIMEOUT → 步骤 FAILED + 计划推进完成")
    void asyncTimeout_failsAndAdvances() {
        ContactPlanStep async = ingestAndDriveAsyncStep();
        assertThat(planOf().getStatus()).isEqualTo(PlanStatus.STEP_EXECUTING);

        bus.publish(callbackEvent(EventType.CALLBACK_TIMEOUT, async));
        bus.drainAll();

        assertThat(async.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(async.getResult()).isEqualTo(ContactResult.FAILED);
        assertThat(planOf().getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED);
    }

    // ───────────────────────── 辅助 ─────────────────────────

    /** 入案建单步 AI_CALL 计划，并驱动其到期执行（停在异步等待态）。返回该步骤。 */
    private ContactPlanStep ingestAndDriveAsyncStep() {
        bus.publish(
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.STAGE, Stage.S1.name()));
        bus.drainAll();

        List<ContactPlanStep> due = planRepo.findDueSteps(LocalDateTime.now(), 100);
        for (ContactPlanStep s : due) {
            bus.publish(
                    CollectionEvent.of(EventType.PLAN_STEP_DUE)
                            .with(CollectionEvent.PLAN_ID, s.getPlanId())
                            .with(CollectionEvent.STEP_ID, s.getId()));
        }
        bus.drainAll();

        return planRepo.stepsOf(planOf().getId()).get(0);
    }

    private ContactPlan planOf() {
        return planRepo.plans.values().iterator().next();
    }

    private CollectionEvent callbackEvent(EventType type, ContactPlanStep step) {
        return CollectionEvent.of(type)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.USER_ID, USER_ID)
                .with(CollectionEvent.PLAN_ID, step.getPlanId())
                .with(CollectionEvent.STEP_ID, step.getId());
    }

    /** 单步 AI_CALL（电话类）计划工厂：delay/observation=0，发送后进入异步等待态。 */
    static class AiCallPlanFactory implements PlanFactory {
        @Override
        public ContactPlan create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot) {
            ContactPlan plan = new ContactPlan();
            plan.setCaseId(caseInfo.getCaseId());
            plan.setUserId(caseInfo.getUserId());
            plan.setStage(stage);
            List<ContactPlanStep> steps = new ArrayList<>();
            ContactPlanStep s = new ContactPlanStep();
            s.setStepOrder(1);
            s.setChannelType(ChannelType.AI_CALL);
            s.setDelayMinutes(0);
            s.setObservationMinutes(0);
            s.setStatus(StepStatus.PENDING);
            steps.add(s);
            plan.setSteps(steps);
            return plan;
        }
    }

    private static void inject(Object target, String field, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("no field " + field + " on " + target.getClass());
    }
}
