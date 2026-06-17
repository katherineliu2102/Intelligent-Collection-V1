package com.collection.engine.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.channel.ChannelGateway;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.ExhaustionResult;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.AdvancementDecision;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.event.EventHandler;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContactRecord;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.service.CaseService;
import com.collection.common.service.PredictiveDialerService;
import com.collection.common.spi.AdvancementPolicy;
import com.collection.common.spi.ExecutionGuard;
import com.collection.common.spi.ExhaustionPolicy;
import com.collection.common.spi.PlanFactory;
import com.collection.common.spi.StepResolver;
import com.collection.engine.bus.InMemoryIdempotencyService;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.lifecycle.ContextAssembler;
import com.collection.engine.lifecycle.EventConsumerDispatcher;
import com.collection.engine.lifecycle.PlanLifecycleManager;
import com.collection.engine.lifecycle.PreFlightChecker;
import com.collection.engine.lifecycle.StepExecutionOrchestrator;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L2 引擎↔渠道执行契约测试骨架（C1–C7，对应 {@code docs/测试总览_Phase1.md} L2 表）。
 *
 * <p>定位：用<b>编码了 2026-06-11 定稿契约语义的可配置替身</b>（StepResolver / ExecutionGuard /
 * ChannelGateway）驱动<b>真实引擎组件</b>，断言引擎在各渠道返回情形下的行为。 编排同事真实化 Mock 后，契约语义一致即对接即绿；本类是双方对接的验收基线。
 *
 * <p>契约依据：{@code docs/contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md}
 *
 * <ul>
 *   <li>StepResult 3 情形：发送受理(success) / 网络超时(retryable) / 其他异常(不重试)
 *   <li>观察期：PUSH/EMAIL 无、SMS 等 DLR（默认 10min）
 *   <li>空地址：方案 A（Guard block NO_EMAIL/NO_PHONE/NO_TOKEN → SKIPPED）；PUSH 叠加 C（fallback SMS）
 *   <li>幂等 key：plan:stepOrder:retryCount
 * </ul>
 */
class ChannelContractL2Test {

    private static final long CASE_ID = 1002L;
    private static final long USER_ID = 9001L;
    private static final String PHONE = "+639170000001";
    private static final String EMAIL = "juan@example.com";
    private static final String JPUSH = "jpush-rid-abc";

    private SyncEventBus bus;
    private InMemoryPlanRepository planRepo;
    private InMemoryTimelineRepository timelineRepo;
    private ConfigurableGuard guard;
    private ConfigurableGateway gateway;
    private SnapshotAddressResolver resolver;
    private SingleStepPlanFactory planFactory;
    private MutableSnapshotCaseService caseService;

    @BeforeEach
    void wire() {
        bus = new SyncEventBus();
        planRepo = new InMemoryPlanRepository();
        timelineRepo = new InMemoryTimelineRepository();
        guard = new ConfigurableGuard();
        gateway = new ConfigurableGateway();
        resolver = new SnapshotAddressResolver();
        planFactory = new SingleStepPlanFactory();
        caseService = new MutableSnapshotCaseService();

        EngineProperties props = new EngineProperties();

        ContextAssembler contextAssembler = new ContextAssembler();
        inject(contextAssembler, "timelineRepository", timelineRepo);
        inject(contextAssembler, "props", props);

        PreFlightChecker preFlight = new PreFlightChecker();
        inject(preFlight, "caseService", caseService);

        StepExecutionOrchestrator orchestrator = new StepExecutionOrchestrator();
        inject(orchestrator, "idempotencyService", new InMemoryIdempotencyService());
        inject(orchestrator, "preFlightChecker", preFlight);
        inject(orchestrator, "executionGuard", guard);
        inject(orchestrator, "stepResolver", resolver);
        inject(orchestrator, "channelGateway", gateway);
        inject(orchestrator, "contextAssembler", contextAssembler);
        inject(orchestrator, "planRepository", planRepo);
        inject(orchestrator, "timelineRepository", timelineRepo);
        inject(orchestrator, "eventBus", bus);
        inject(orchestrator, "spiInvoker", com.collection.engine.spi.SpiInvoker.direct());
        inject(orchestrator, "props", props);

        PlanLifecycleManager manager = new PlanLifecycleManager();
        inject(manager, "planRepository", planRepo);
        inject(manager, "caseService", caseService);
        inject(manager, "planFactory", planFactory);
        inject(
                manager,
                "advancementPolicy",
                (AdvancementPolicy) (ctx, r) -> AdvancementDecision.ADVANCE_NEXT);
        inject(
                manager,
                "exhaustionPolicy",
                (ExhaustionPolicy) (plan, info, snap) -> ExhaustionResult.complete("done"));
        inject(manager, "predictiveDialerService", (PredictiveDialerService) userId -> {});
        inject(manager, "spiInvoker", com.collection.engine.spi.SpiInvoker.direct());

        EventConsumerDispatcher dispatcher = new EventConsumerDispatcher();
        inject(dispatcher, "eventBus", bus);
        inject(dispatcher, "manager", manager);
        inject(dispatcher, "orchestrator", orchestrator);
        dispatcher.registerHandlers();
    }

    // ───────────────────────── C1 ─────────────────────────

    @Test
    @DisplayName("C1 EMAIL dispatch 成功 → 步骤 COMPLETED，timeline 落 providerMsgId")
    void c1_dispatchSuccess_completesAndRecordsProviderMsgId() {
        planFactory.channel = ChannelType.EMAIL;
        planFactory.observationMinutes = 0;
        gateway.behavior =
                cmd ->
                        StepResult.builder()
                                .success(true)
                                .contactResult(ContactResult.DELIVERED)
                                .retryable(false)
                                .providerMsgId("prov-123")
                                .build();

        ingestAndRunDue();

        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED);
        assertThat(timelineRepo.records).hasSize(1);
        assertThat(timelineRepo.records.get(0).getProviderMsgId()).isEqualTo("prov-123");
        assertThat(gateway.count.get()).isEqualTo(1);
    }

    // ───────────────────────── C2 ─────────────────────────

    @Test
    @DisplayName("C2 PUSH jpushToken 为空 → Gateway 同槽 fallback SMS（对引擎透明，一次 dispatch 即完成）")
    void c2_pushEmptyToken_gatewayFallbackSms_oneDispatch() {
        caseService.jpushToken = null; // PUSH token 缺失
        caseService.phone = PHONE; // 但 SMS 号在 → 可 fallback
        planFactory.channel = ChannelType.PUSH;
        planFactory.observationMinutes = 0;
        // 模拟渠道内部 fallback：PUSH 无 token 但带 fallback_sms → 改走 SMS，对引擎返回成功
        gateway.behavior =
                cmd -> {
                    boolean canFallback =
                            cmd.getMetadata().get(StepCommand.META_FALLBACK_SMS) != null;
                    return StepResult.builder()
                            .success(canFallback)
                            .contactResult(
                                    canFallback ? ContactResult.DELIVERED : ContactResult.FAILED)
                            .retryable(false)
                            .providerMsgId(canFallback ? "sms-fallback-1" : null)
                            .build();
                };

        ingestAndRunDue();

        assertThat(gateway.count.get()).isEqualTo(1); // 仅一次 dispatch
        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    // ───────────────────────── C3 ─────────────────────────

    @Test
    @DisplayName("C3 EMAIL email 为空 → Guard block NO_EMAIL → 步骤 SKIPPED + 推进，不 dispatch")
    void c3_emptyEmail_guardSkips_noDispatch() {
        caseService.email = null;
        planFactory.channel = ChannelType.EMAIL;
        guard.behavior =
                ctx -> {
                    UserProfile.BasicInfo b = ctx.getContextSnapshot().getUserProfile().getBasic();
                    if (b == null || b.getEmail() == null || b.getEmail().isEmpty()) {
                        return GuardVerdict.block("NO_EMAIL", "ADDRESS_MISSING");
                    }
                    return GuardVerdict.allow();
                };

        ingestAndRunDue();

        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(gateway.count.get()).isZero(); // 未触达
        assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED); // 推进至穷尽 COMPLETE
    }

    // ───────────────────────── C4 ─────────────────────────

    @Test
    @DisplayName("C4 dispatch 网络超时 retryable=true → 退避重试（保持 EXECUTING，retryCount 增加）")
    void c4_networkTimeout_retryable_backoffRetry() {
        planFactory.channel = ChannelType.EMAIL;
        gateway.behavior =
                cmd ->
                        StepResult.builder()
                                .success(false)
                                .contactResult(ContactResult.FAILED)
                                .retryable(true)
                                .errorCode("EMAIL_TIMEOUT")
                                .build();

        ingestAndRunDue();

        ContactPlanStep step = onlyStep();
        // 退避重试：计划保持 STEP_EXECUTING；步骤重新入队（PENDING）+ 退避到将来 + retryCount 增加
        assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.STEP_EXECUTING);
        assertThat(step.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(step.getRetryCount()).isGreaterThanOrEqualTo(1);
        assertThat(step.getTriggerTime()).isAfter(LocalDateTime.now());
    }

    // ───────────────────────── C5 ─────────────────────────

    @Test
    @DisplayName("C5 dispatch 地址无效 retryable=false → 步骤 FAILED + 推进")
    void c5_invalidAddress_notRetryable_failedAndAdvance() {
        planFactory.channel = ChannelType.EMAIL;
        gateway.behavior =
                cmd ->
                        StepResult.builder()
                                .success(false)
                                .contactResult(ContactResult.FAILED)
                                .retryable(false)
                                .errorCode("EMAIL_INVALID_ADDR")
                                .build();

        ingestAndRunDue();

        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED); // 推进至穷尽 COMPLETE
    }

    // ───────────────────────── C6 ─────────────────────────

    @Test
    @DisplayName("C6 SMS 有观察期 → 发送成功进 STEP_WAITING；到期 prepareStepDue → 结转 COMPLETED")
    void c6_smsObservation_waitsThenSettles() {
        planFactory.channel = ChannelType.SMS;
        planFactory.observationMinutes = 10;
        gateway.behavior =
                cmd ->
                        StepResult.builder()
                                .success(true)
                                .contactResult(ContactResult.DELIVERED)
                                .retryable(false)
                                .providerMsgId("sms-1")
                                .build();

        ingestAndRunDue();

        // 受理成功但进观察期：计划 STEP_WAITING，步骤尚未 COMPLETED
        assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.STEP_WAITING);
        assertThat(onlyStep().getStatus()).isNotEqualTo(StepStatus.COMPLETED);

        // 观察期到期：把触发时间拨到过去，再投 PLAN_STEP_DUE → 结转
        ContactPlanStep s = onlyStep();
        planRepo.updateStepTriggerTime(
                s.getId(), LocalDateTime.now().minusMinutes(1), s.getStatus());
        bus.publish(stepDue(s.getPlanId(), s.getId()));
        bus.drainAll();

        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    // ───────────────────────── C7 ─────────────────────────

    @Test
    @DisplayName("C7 幂等 key(plan:step:retryCount) → 重复 PLAN_STEP_DUE 不二次 dispatch")
    void c7_idempotency_duplicateEventNoDoubleDispatch() {
        planFactory.channel = ChannelType.EMAIL;
        planFactory.observationMinutes = 0;
        gateway.behavior =
                cmd ->
                        StepResult.builder()
                                .success(true)
                                .contactResult(ContactResult.DELIVERED)
                                .retryable(false)
                                .providerMsgId("prov-x")
                                .build();

        // 入案 + 首步到期
        bus.publish(stepIngest());
        bus.drainAll();
        List<ContactPlanStep> due = planRepo.findDueSteps(LocalDateTime.now(), 100);
        assertThat(due).hasSize(1);
        Long planId = due.get(0).getPlanId();
        Long stepId = due.get(0).getId();

        // 同一 (plan, step, retryCount=0) 连发两次 PLAN_STEP_DUE
        bus.publish(stepDue(planId, stepId));
        bus.publish(stepDue(planId, stepId));
        bus.drainAll();

        assertThat(gateway.count.get()).isEqualTo(1); // 幂等：仅一次实际 dispatch
    }

    // ───────────────────────── 驱动辅助 ─────────────────────────

    /** 入案 → 循环扫描到期步骤投 PLAN_STEP_DUE，直到无到期步骤（不含观察期 WAITING 拨期）。 */
    private void ingestAndRunDue() {
        bus.publish(stepIngest());
        bus.drainAll();
        for (int i = 0; i < 10; i++) {
            List<ContactPlanStep> due = planRepo.findDueSteps(LocalDateTime.now(), 100);
            if (due.isEmpty()) {
                break;
            }
            for (ContactPlanStep s : due) {
                bus.publish(stepDue(s.getPlanId(), s.getId()));
            }
            bus.drainAll();
        }
    }

    private CollectionEvent stepIngest() {
        return CollectionEvent.of(com.collection.common.enums.EventType.CASE_INGESTED)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.USER_ID, USER_ID)
                .with(CollectionEvent.STAGE, Stage.S1.name());
    }

    private CollectionEvent stepDue(Long planId, Long stepId) {
        return CollectionEvent.of(com.collection.common.enums.EventType.PLAN_STEP_DUE)
                .with(CollectionEvent.PLAN_ID, planId)
                .with(CollectionEvent.STEP_ID, stepId);
    }

    private ContactPlan onlyPlan() {
        return planRepo.plans.values().iterator().next();
    }

    private ContactPlanStep onlyStep() {
        return planRepo.stepsOf(onlyPlan().getId()).get(0);
    }

    // ───────────────────────── 可配置替身 ─────────────────────────

    /** 单步计划工厂：渠道与观察期可配置。 */
    static class SingleStepPlanFactory implements PlanFactory {
        ChannelType channel = ChannelType.EMAIL;
        int observationMinutes = 0;

        @Override
        public ContactPlan create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot) {
            ContactPlan plan = new ContactPlan();
            plan.setCaseId(caseInfo.getCaseId());
            plan.setUserId(caseInfo.getUserId());
            plan.setStage(stage);
            ContactPlanStep s = new ContactPlanStep();
            s.setStepOrder(1);
            s.setChannelType(channel);
            s.setDelayMinutes(0);
            s.setObservationMinutes(observationMinutes);
            s.setStatus(StepStatus.PENDING);
            List<ContactPlanStep> steps = new ArrayList<>();
            steps.add(s);
            plan.setSteps(steps);
            return plan;
        }
    }

    /** 按 channelType 从快照取地址；PUSH 写 fallback_sms（模拟编排真实 Resolver 取号口径）。 */
    static class SnapshotAddressResolver implements StepResolver {
        @Override
        public StepCommand resolve(ExecutionContext context) {
            ChannelType ch = context.getCurrentStep().getChannelType();
            UserProfile.BasicInfo basic = context.getContextSnapshot().getUserProfile().getBasic();
            UserProfile.DeviceInfo device =
                    context.getContextSnapshot().getUserProfile().getDevice();
            String target;
            Map<String, Object> meta = new java.util.HashMap<>();
            switch (ch) {
                case PUSH:
                    target = device == null ? null : device.getJpushToken();
                    if (basic != null && basic.getPrimaryPhone() != null) {
                        meta.put(StepCommand.META_FALLBACK_SMS, basic.getPrimaryPhone());
                    }
                    break;
                case EMAIL:
                    target = basic == null ? null : basic.getEmail();
                    break;
                default:
                    target = basic == null ? null : basic.getPrimaryPhone();
            }
            return StepCommand.builder()
                    .channelType(ch)
                    .targetAddress(target)
                    .templateId("T")
                    .idempotencyKey("k-" + ch)
                    .metadata(meta)
                    .build();
        }
    }

    /** 守卫：默认放行；可注入空地址 block 逻辑（方案 A）。 */
    static class ConfigurableGuard implements ExecutionGuard {
        Function<ExecutionContext, GuardVerdict> behavior = ctx -> GuardVerdict.allow();

        @Override
        public GuardVerdict evaluate(ExecutionContext context) {
            return behavior.apply(context);
        }
    }

    /** 渠道网关：行为可配置 + dispatch 计数（验证幂等/fallback 单次）。 */
    static class ConfigurableGateway implements ChannelGateway {
        Function<StepCommand, StepResult> behavior =
                cmd ->
                        StepResult.builder()
                                .success(true)
                                .contactResult(ContactResult.DELIVERED)
                                .retryable(false)
                                .build();
        final AtomicInteger count = new AtomicInteger();

        @Override
        public StepResult dispatch(StepCommand command) {
            count.incrementAndGet();
            return behavior.apply(command);
        }
    }

    /** CaseService：快照地址字段可变（构造空地址/缺 token 场景）。 */
    class MutableSnapshotCaseService implements CaseService {
        String phone = PHONE;
        String email = EMAIL;
        String jpushToken = JPUSH;

        @Override
        public CaseInfo getCaseInfo(Long caseId) {
            CaseInfo info = new CaseInfo();
            info.setCaseId(caseId);
            info.setUserId(USER_ID);
            info.setStage(Stage.S1);
            info.setRepaid(false);
            info.setFrozen(false);
            return info;
        }

        @Override
        public CaseContext buildContext(Long caseId) {
            return null;
        }

        @Override
        public com.collection.common.model.ContactHistory buildContactHistory(
                Long userId, Long caseId) {
            return null;
        }

        @Override
        public ContextSnapshot getContextSnapshot(Long caseId) {
            UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
            basic.setPrimaryPhone(phone);
            basic.setEmail(email);
            UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();
            device.setJpushToken(jpushToken);
            UserProfile profile = new UserProfile();
            profile.setUserId(USER_ID);
            profile.setBasic(basic);
            profile.setDevice(device);
            ContextSnapshot snap = new ContextSnapshot();
            snap.setUserProfile(profile);
            snap.setSnapshotVersion("test-v1");
            return snap;
        }

        @Override
        public boolean isRepaid(Long caseId) {
            return false;
        }
    }

    // ───────────────────────── 同步事件总线 + 内存仓储（与 FullChainIntegrationTest 同构）
    // ─────────────────────────

    static class SyncEventBus implements CollectionEventBus {
        private final Map<com.collection.common.enums.EventType, List<EventHandler>> handlers =
                new EnumMap<>(com.collection.common.enums.EventType.class);
        private final Deque<CollectionEvent> queue = new ArrayDeque<>();

        @Override
        public void publish(CollectionEvent event) {
            queue.addLast(event);
        }

        @Override
        public void subscribe(com.collection.common.enums.EventType type, EventHandler handler) {
            handlers.computeIfAbsent(type, k -> new ArrayList<>()).add(handler);
        }

        void drainAll() {
            while (!queue.isEmpty()) {
                CollectionEvent e = queue.pollFirst();
                for (EventHandler h :
                        handlers.getOrDefault(e.getEventType(), Collections.emptyList())) {
                    try {
                        h.handle(e);
                    } catch (Exception ex) {
                        throw new IllegalStateException(
                                "handler failed for " + e.getEventType(), ex);
                    }
                }
            }
        }
    }

    static class InMemoryPlanRepository implements ContactPlanRepository {
        final Map<Long, ContactPlan> plans = new ConcurrentHashMap<>();
        final Map<Long, ContactPlanStep> steps = new ConcurrentHashMap<>();
        private final AtomicLong planSeq = new AtomicLong(1000);
        private final AtomicLong stepSeq = new AtomicLong(2000);

        List<ContactPlanStep> stepsOf(Long planId) {
            List<ContactPlanStep> list = new ArrayList<>();
            for (ContactPlanStep s : steps.values()) {
                if (planId.equals(s.getPlanId())) {
                    list.add(s);
                }
            }
            list.sort((a, b) -> Integer.compare(a.getStepOrder(), b.getStepOrder()));
            return list;
        }

        private ContactPlan attach(ContactPlan p) {
            if (p != null) {
                p.setSteps(stepsOf(p.getId()));
            }
            return p;
        }

        @Override
        public ContactPlan findById(Long planId) {
            return attach(plans.get(planId));
        }

        @Override
        public ContactPlan findPlanWithLock(Long planId) {
            return attach(plans.get(planId));
        }

        @Override
        public List<ContactPlan> findActivePlansByUser(Long userId) {
            List<ContactPlan> list = new ArrayList<>();
            for (ContactPlan p : plans.values()) {
                if (userId.equals(p.getUserId()) && !p.isTerminal()) {
                    list.add(attach(p));
                }
            }
            return list;
        }

        @Override
        public List<ContactPlan> findActivePlansByCase(Long caseId) {
            List<ContactPlan> list = new ArrayList<>();
            for (ContactPlan p : plans.values()) {
                if (caseId.equals(p.getCaseId()) && !p.isTerminal()) {
                    list.add(attach(p));
                }
            }
            return list;
        }

        @Override
        public ContactPlan findActivePlanByCaseAndStage(Long caseId, Stage stage) {
            for (ContactPlan p : plans.values()) {
                if (caseId.equals(p.getCaseId()) && stage == p.getStage() && !p.isTerminal()) {
                    return attach(p);
                }
            }
            return null;
        }

        @Override
        public ContactPlan getLastCompletedPlan(Long caseId) {
            return null;
        }

        @Override
        public void savePlan(ContactPlan plan) {
            plan.setId(planSeq.incrementAndGet());
            plans.put(plan.getId(), plan);
            for (ContactPlanStep s : plan.getSteps()) {
                s.setId(stepSeq.incrementAndGet());
                s.setPlanId(plan.getId());
                if (s.getStatus() == null) {
                    s.setStatus(StepStatus.PENDING);
                }
                steps.put(s.getId(), s);
            }
        }

        @Override
        public void updatePlanStatus(
                Long planId, PlanStatus status, com.collection.common.enums.CancelReason reason) {
            ContactPlan p = plans.get(planId);
            if (p != null) {
                p.setStatus(status);
                if (reason != null) {
                    p.setCancelReason(reason);
                }
            }
        }

        @Override
        public void markStarted(Long planId) {
            ContactPlan p = plans.get(planId);
            if (p != null && p.getStartedAt() == null) {
                p.setStartedAt(LocalDateTime.now());
            }
        }

        @Override
        public void markCompleted(Long planId) {
            ContactPlan p = plans.get(planId);
            if (p != null) {
                p.setCompletedAt(LocalDateTime.now());
            }
        }

        @Override
        public void updateCurrentStep(Long planId, int currentStep) {
            ContactPlan p = plans.get(planId);
            if (p != null) {
                p.setCurrentStep(currentStep);
            }
        }

        @Override
        public ContactPlanStep findStepById(Long stepId) {
            return steps.get(stepId);
        }

        @Override
        public List<ContactPlanStep> findStepsByPlan(Long planId) {
            return stepsOf(planId);
        }

        @Override
        public ContactPlanStep getNextStep(Long planId, int currentStepOrder) {
            for (ContactPlanStep s : steps.values()) {
                if (planId.equals(s.getPlanId()) && s.getStepOrder() == currentStepOrder + 1) {
                    return s;
                }
            }
            return null;
        }

        @Override
        public void updateStepStatus(Long stepId, StepStatus status, ContactResult result) {
            ContactPlanStep s = steps.get(stepId);
            if (s != null) {
                s.setStatus(status);
                if (result != null) {
                    s.setResult(result);
                }
            }
        }

        @Override
        public void updateStepTriggerTime(
                Long stepId, LocalDateTime triggerTime, StepStatus status) {
            ContactPlanStep s = steps.get(stepId);
            if (s != null) {
                s.setTriggerTime(triggerTime);
                if (status != null) {
                    s.setStatus(status);
                }
            }
        }

        @Override
        public void updateStepTimeoutTime(Long stepId, LocalDateTime timeoutTime) {
            ContactPlanStep s = steps.get(stepId);
            if (s != null) {
                s.setTimeoutTime(timeoutTime);
            }
        }

        @Override
        public void incrementRetryCount(Long stepId) {
            ContactPlanStep s = steps.get(stepId);
            if (s != null) {
                s.setRetryCount(s.getRetryCount() + 1);
            }
        }

        @Override
        public List<ContactPlanStep> findDueSteps(LocalDateTime now, int limit) {
            List<ContactPlanStep> list = new ArrayList<>();
            for (ContactPlanStep s : steps.values()) {
                ContactPlan p = plans.get(s.getPlanId());
                if (s.getStatus() == StepStatus.PENDING
                        && s.getTriggerTime() != null
                        && !s.getTriggerTime().isAfter(now)
                        && p != null
                        && !p.isTerminal()) {
                    list.add(s);
                }
            }
            return list;
        }

        @Override
        public List<ContactPlanStep> findTimeoutSteps(LocalDateTime now, int limit) {
            return Collections.emptyList();
        }
    }

    static class InMemoryTimelineRepository implements TimelineRepository {
        final List<ContactRecord> records = new ArrayList<>();

        @Override
        public void writeTimeline(ContactRecord record) {
            records.add(record);
        }

        @Override
        public List<ContactRecord> getContactHistory(Long userId, int limit) {
            return Collections.emptyList();
        }
    }

    // ───────────────────────── 反射注入 ─────────────────────────

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
