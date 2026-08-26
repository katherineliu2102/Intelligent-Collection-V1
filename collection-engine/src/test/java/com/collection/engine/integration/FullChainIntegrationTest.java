package com.collection.engine.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.channel.ChannelGateway;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.ExhaustionResult;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.AdvancementDecision;
import com.collection.common.enums.CancelReason;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.EventType;
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
import com.collection.engine.lifecycle.StepOutcomeRecorder;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 全链路集成测试（无 DB，可在 CI 运行）。
 *
 * <p>用<b>同步内存事件总线 + 内存仓储</b>驱动<b>真实引擎组件</b> （{@link EventConsumerDispatcher} / {@link
 * PlanLifecycleManager} / {@link StepExecutionOrchestrator}）， 验证 {@code CASE_INGESTED → SMS → PUSH
 * → EMAIL → PLAN_COMPLETED} 端到端闭环， 以及三渠道从同一份 ContextSnapshot 按 channelType 取地址（SMS=手机号 /
 * PUSH=jpushToken / EMAIL=email）。
 *
 * <p>注：真·连库集成（MyBatis + ai_collection_db + TriggerScanner）需 MySQL（或 Testcontainers），
 * 不在本环境运行；本测试覆盖引擎装配与事件链语义，DB 持久化映射由服务层另行验证。
 */
class FullChainIntegrationTest {

    private static final long CASE_ID = 1002L;
    private static final long USER_ID = 9001L;
    private static final String PHONE = "+639170000001";
    private static final String EMAIL = "juan@example.com";
    private static final String FCM = "fcm-token-abc";

    private SyncEventBus bus;
    private InMemoryPlanRepository planRepo;
    private InMemoryTimelineRepository timelineRepo;
    private RecordingStepResolver stepResolver;
    private PlanLifecycleManager manager;

    @BeforeEach
    void wire() {
        bus = new SyncEventBus();
        planRepo = new InMemoryPlanRepository();
        timelineRepo = new InMemoryTimelineRepository();
        stepResolver = new RecordingStepResolver();

        EngineProperties props = new EngineProperties();
        CaseService caseService = new StubCaseService();

        ContextAssembler contextAssembler = new ContextAssembler();
        inject(contextAssembler, "timelineRepository", timelineRepo);
        inject(contextAssembler, "props", props);

        PreFlightChecker preFlight = new PreFlightChecker();
        inject(preFlight, "caseService", caseService);

        StepExecutionOrchestrator orchestrator = new StepExecutionOrchestrator();
        inject(orchestrator, "idempotencyService", new InMemoryIdempotencyService());
        inject(orchestrator, "preFlightChecker", preFlight);
        inject(orchestrator, "executionGuard", (ExecutionGuard) ctx -> GuardVerdict.allow());
        inject(orchestrator, "stepResolver", stepResolver);
        inject(orchestrator, "channelGateway", new StubChannelGateway());
        inject(orchestrator, "contextAssembler", contextAssembler);
        inject(orchestrator, "planRepository", planRepo);
        inject(orchestrator, "timelineRepository", timelineRepo);
        StepOutcomeRecorder outcomeRecorder = new StepOutcomeRecorder();
        inject(outcomeRecorder, "planRepository", planRepo);
        inject(outcomeRecorder, "timelineRepository", timelineRepo);
        inject(orchestrator, "stepOutcomeRecorder", outcomeRecorder);
        inject(
                orchestrator,
                "decisionLogRepository",
                (com.collection.common.repository.DecisionLogRepository) dl -> {});
        inject(orchestrator, "eventBus", bus);
        inject(orchestrator, "spiInvoker", com.collection.engine.spi.SpiInvoker.direct());
        inject(orchestrator, "props", props);

        manager = new PlanLifecycleManager();
        inject(manager, "planRepository", planRepo);
        inject(manager, "stepOutcomeRecorder", outcomeRecorder);
        inject(manager, "caseService", caseService);
        inject(manager, "planFactory", new ThreeChannelPlanFactory());
        inject(
                manager,
                "advancementPolicy",
                (AdvancementPolicy) (ctx, r) -> AdvancementDecision.ADVANCE_NEXT);
        inject(
                manager,
                "exhaustionPolicy",
                (ExhaustionPolicy) (plan, info, snap) -> ExhaustionResult.complete("done"));
        inject(
                manager,
                "predictiveDialerService",
                (PredictiveDialerService) (userId, caseId) -> {});
        inject(manager, "spiInvoker", com.collection.engine.spi.SpiInvoker.direct());
        inject(manager, "metrics", com.collection.engine.metrics.CollectionMetrics.local());

        EventConsumerDispatcher dispatcher = new EventConsumerDispatcher();
        inject(dispatcher, "eventBus", bus);
        inject(dispatcher, "manager", manager);
        inject(dispatcher, "orchestrator", orchestrator);
        dispatcher.registerHandlers();
    }

    @Test
    @DisplayName("入案 → SMS→PUSH→EMAIL 三步全部完成 → PLAN_COMPLETED；地址按渠道分流")
    void caseIngested_runsThreeChannels_toCompleted() {
        // 入案（模拟 IngestionService.ingestCase）
        bus.publish(
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.STAGE, Stage.S1.name()));
        bus.drainAll();

        // 模拟 TriggerScanner：循环扫描到期步骤 → 发 PLAN_STEP_DUE，直到无到期步骤
        for (int i = 0; i < 10; i++) {
            List<ContactPlanStep> due = planRepo.findDueSteps(LocalDateTime.now(), 100, null);
            if (due.isEmpty()) {
                break;
            }
            for (ContactPlanStep s : due) {
                bus.publish(
                        CollectionEvent.of(EventType.PLAN_STEP_DUE)
                                .with(CollectionEvent.PLAN_ID, s.getPlanId())
                                .with(CollectionEvent.STEP_ID, s.getId()));
            }
            bus.drainAll();
        }

        ContactPlan plan = planRepo.plans.values().iterator().next();

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED);
        assertThat(planRepo.stepsOf(plan.getId()))
                .allMatch(s -> s.getStatus() == StepStatus.COMPLETED);
        assertThat(timelineRepo.records).hasSize(3);

        // 同一份快照按 channelType 取地址
        assertThat(stepResolver.targetByChannel.get(ChannelType.SMS)).isEqualTo(PHONE);
        assertThat(stepResolver.targetByChannel.get(ChannelType.PUSH)).isEqualTo(FCM);
        assertThat(stepResolver.targetByChannel.get(ChannelType.EMAIL)).isEqualTo(EMAIL);
    }

    // ───────── 至少一次投递：重复 / 并发的 PLAN_STEP_DUE（生产 Pub/Sub 语义） ─────────

    @Test
    @DisplayName("L1-DUP1 步骤已完成后重投 PLAN_STEP_DUE → 不复活步骤、不二次触达")
    void duplicateStepDue_afterCompletion_doesNotResurrect() {
        ContactPlanStep first = ingestAndRunFirstStep();
        int touchesAfterFirstRun = timelineRepo.records.size();

        // ack 超时导致的重投：同一条 STEP_DUE 再来一次
        bus.publish(
                CollectionEvent.of(EventType.PLAN_STEP_DUE)
                        .with(CollectionEvent.PLAN_ID, first.getPlanId())
                        .with(CollectionEvent.STEP_ID, first.getId()));
        bus.drainAll();

        ContactPlanStep reloaded = planRepo.findStepById(first.getId());
        assertThat(reloaded.getStatus().isTerminal()).as("已终结的步骤不得被重投改回 EXECUTING").isTrue();
        assertThat(timelineRepo.records).as("重投不得产生第二次触达").hasSize(touchesAfterFirstRun);
    }

    @Test
    @DisplayName("L1-DUP2 步骤已终结时并发重投 → 全部抢占失败，行不被改回 EXECUTING")
    void concurrentStepDue_onTerminalStep_neverResurrects() throws Exception {
        // CAS 的职责边界：只排除终态。非终态的重复抢占是合法的（退避重试要复用同一行），
        // 那一侧由幂等锁防重复触达，见 DUP3。
        ContactPlanStep done = ingestAndRunFirstStep();
        assertThat(planRepo.findStepById(done.getId()).getStatus().isTerminal()).isTrue();

        List<Boolean> claims = runConcurrently(4, () -> planRepo.markStepExecuting(done.getId()));

        assertThat(claims).as("终态步骤的任何抢占都必须失败").containsOnly(false);
        ContactPlanStep reloaded = planRepo.findStepById(done.getId());
        assertThat(reloaded.getStatus().isTerminal()).isTrue();
        assertThat(reloaded.getTriggerTime())
                .as("抢占失败不得清空 trigger_time —— 那正是悬挂的成因")
                .isEqualTo(done.getTriggerTime());
    }

    @Test
    @DisplayName("L1-DUP3 未执行步骤并发重投 → 只产生一次触达（幂等锁）")
    void concurrentStepDue_onFreshStep_touchesOnce() throws Exception {
        ContactPlanStep pending = ingestAndGetFirstPendingStep();
        CollectionEvent due =
                CollectionEvent.of(EventType.PLAN_STEP_DUE)
                        .with(CollectionEvent.PLAN_ID, pending.getPlanId())
                        .with(CollectionEvent.STEP_ID, pending.getId());

        runConcurrently(
                2,
                () -> {
                    bus.publish(due);
                    bus.drainAll();
                    return true;
                });

        assertThat(timelineRepo.records).as("同一步骤并发重复投递只允许一次真实触达").hasSize(1);
    }

    /** 并发跑同一段逻辑，返回各线程的结果。用栅栏让线程尽量同时进入临界区。 */
    private <T> List<T> runConcurrently(int threads, java.util.concurrent.Callable<T> task)
            throws Exception {
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return task.call();
                                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (java.util.concurrent.Future<T> f : futures) {
                out.add(f.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 跑完第一个步骤（含渠道调度与终态回写），返回该步骤。 */
    private ContactPlanStep ingestAndRunFirstStep() {
        ContactPlanStep pending = ingestAndGetFirstPendingStep();
        bus.publish(
                CollectionEvent.of(EventType.PLAN_STEP_DUE)
                        .with(CollectionEvent.PLAN_ID, pending.getPlanId())
                        .with(CollectionEvent.STEP_ID, pending.getId()));
        bus.drainAll();
        return pending;
    }

    private ContactPlanStep ingestAndGetFirstPendingStep() {
        bus.publish(
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.STAGE, Stage.S1.name()));
        bus.drainAll();
        List<ContactPlanStep> due = planRepo.findDueSteps(LocalDateTime.now(), 100, null);
        assertThat(due).as("入案后应有到期步骤").isNotEmpty();
        return due.get(0);
    }

    @Test
    @DisplayName("入案后还款到账 → 取消活跃计划(REPAID)")
    void repaymentCancelsActivePlan() {
        bus.publish(
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.STAGE, Stage.S1.name()));
        bus.drainAll();

        bus.publish(
                CollectionEvent.of(EventType.REPAYMENT_RECEIVED)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.CASE_ID, CASE_ID));
        bus.drainAll();

        ContactPlan plan = planRepo.plans.values().iterator().next();
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.PLAN_CANCELLED);
        assertThat(plan.getCancelReason()).isEqualTo(CancelReason.REPAID);
    }

    @Test
    @DisplayName("D24 入案建 S1 → STAGE_CHANGED(S2) → 旧 S1 取消(STAGE_UPGRADE) + 新建 S2(PENDING)")
    void stageChanged_cancelsOldAndBuildsNewStagePlan() {
        bus.publish(
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.STAGE, Stage.S1.name()));
        bus.drainAll();

        bus.publish(
                CollectionEvent.of(EventType.STAGE_CHANGED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.STAGE, Stage.S2.name()));
        bus.drainAll();

        ContactPlan oldPlan = findPlanByStage(Stage.S1);
        ContactPlan newPlan = findPlanByStage(Stage.S2);
        assertThat(oldPlan.getStatus()).isEqualTo(PlanStatus.PLAN_CANCELLED);
        assertThat(oldPlan.getCancelReason()).isEqualTo(CancelReason.STAGE_UPGRADE);
        assertThat(newPlan.getStatus()).isEqualTo(PlanStatus.PENDING);
    }

    @Test
    @DisplayName("D29-L1 入案建 S1 → CASE_CEASED → 活跃计划取消(CEASED)，不 rebuild")
    void caseCeasedCancelsActivePlan() {
        bus.publish(
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, CASE_ID)
                        .with(CollectionEvent.USER_ID, USER_ID)
                        .with(CollectionEvent.STAGE, Stage.S1.name()));
        bus.drainAll();

        bus.publish(
                CollectionEvent.of(EventType.CASE_CEASED).with(CollectionEvent.CASE_ID, CASE_ID));
        bus.drainAll();

        assertThat(planRepo.plans).hasSize(1); // 不 rebuild
        ContactPlan plan = planRepo.plans.values().iterator().next();
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.PLAN_CANCELLED);
        assertThat(plan.getCancelReason()).isEqualTo(CancelReason.CEASED);
    }

    private ContactPlan findPlanByStage(Stage stage) {
        for (ContactPlan p : planRepo.plans.values()) {
            if (p.getStage() == stage) {
                return p;
            }
        }
        throw new IllegalStateException("no plan for stage " + stage);
    }

    // ───────────────────────── 同步事件总线（drain 串行处理，避免线程时序） ─────────────────────────

    static class SyncEventBus implements CollectionEventBus {
        private final Map<EventType, List<EventHandler>> handlers = new EnumMap<>(EventType.class);
        private final Deque<CollectionEvent> queue = new ArrayDeque<>();

        @Override
        public void publish(CollectionEvent event) {
            queue.addLast(event);
        }

        @Override
        public void subscribe(EventType type, EventHandler handler) {
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

    // ───────────────────────── 内存仓储 ─────────────────────────

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
        public List<ContactPlan> findRecentPlansByCase(Long caseId, int limit) {
            return plans.values().stream()
                    .filter(p -> caseId.equals(p.getCaseId()))
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .limit(limit)
                    .map(this::attach)
                    .collect(java.util.stream.Collectors.toList());
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
        public void updatePlanStatus(Long planId, PlanStatus status, CancelReason reason) {
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
            // 与持久化实现同语义：序号更大且未终结的第一条，跳过乱序先完成的后继（台账 F12）。
            ContactPlanStep best = null;
            for (ContactPlanStep s : steps.values()) {
                if (!planId.equals(s.getPlanId()) || s.getStepOrder() <= currentStepOrder) {
                    continue;
                }
                if (s.getStatus() != null && s.getStatus().isTerminal()) {
                    continue;
                }
                if (best == null || s.getStepOrder() < best.getStepOrder()) {
                    best = s;
                }
            }
            return best;
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

        /**
         * 复刻持久化实现的条件更新语义：{@code WHERE id = ? AND status NOT IN (终态)}。 synchronized 对应 UPDATE
         * 语句在行锁下的原子判定，缺了它就无法在内存里重现并发投递的竞态。 同时必须清空 triggerTime —— 悬挂的成因正是「状态被改回 EXECUTING 且两个时间列都空」。
         */
        @Override
        public synchronized boolean markStepExecuting(Long stepId) {
            ContactPlanStep s = steps.get(stepId);
            if (s == null || (s.getStatus() != null && s.getStatus().isTerminal())) {
                return false;
            }
            s.setStatus(StepStatus.EXECUTING);
            s.setTriggerTime(null);
            if (s.getExecutedAt() == null) {
                s.setExecutedAt(LocalDateTime.now());
            }
            return true;
        }

        @Override
        public void updateStepTriggerTime(
                Long stepId, LocalDateTime triggerTime, StepStatus status) {
            ContactPlanStep s = steps.get(stepId);
            // 终态守卫与持久化实现的 SQL 谓词一致，不得复活已终结步骤（台账 F12）。
            if (s == null || (s.getStatus() != null && s.getStatus().isTerminal())) {
                return;
            }
            s.setTriggerTime(triggerTime);
            if (status != null) {
                s.setStatus(status);
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
        public List<ContactPlanStep> findDueSteps(
                LocalDateTime now, int limit, List<Long> caseIdFilter) {
            List<ContactPlanStep> list = new ArrayList<>();
            for (ContactPlanStep s : steps.values()) {
                ContactPlan p = plans.get(s.getPlanId());
                if (s.getStatus() == StepStatus.PENDING
                        && s.getTriggerTime() != null
                        && !s.getTriggerTime().isAfter(now)
                        && p != null
                        && !p.isTerminal()
                        && (caseIdFilter == null
                                || caseIdFilter.isEmpty()
                                || caseIdFilter.contains(p.getCaseId()))) {
                    list.add(s);
                }
            }
            return list;
        }

        @Override
        public List<ContactPlanStep> findTimeoutSteps(
                LocalDateTime now, int limit, List<Long> caseIdFilter) {
            return Collections.emptyList();
        }
    }

    static class InMemoryTimelineRepository implements TimelineRepository {
        // 并发重复投递用例会从多个线程写入，需线程安全容器
        final List<ContactRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void writeTimeline(ContactRecord record) {
            records.add(record);
        }

        @Override
        public List<ContactRecord> getContactHistory(Long userId, int limit) {
            return Collections.emptyList();
        }
    }

    // ───────────────────────── SPI / Service 测试替身 ─────────────────────────

    /** 固定 SMS → PUSH → EMAIL 三步消息类计划（delay/observation=0，全程同步完成）。 */
    static class ThreeChannelPlanFactory implements PlanFactory {
        @Override
        public ContactPlan create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot) {
            ContactPlan plan = new ContactPlan();
            plan.setCaseId(caseInfo.getCaseId());
            plan.setUserId(caseInfo.getUserId());
            plan.setStage(stage);
            List<ContactPlanStep> steps = new ArrayList<>();
            steps.add(step(1, ChannelType.SMS));
            steps.add(step(2, ChannelType.PUSH));
            steps.add(step(3, ChannelType.EMAIL));
            plan.setSteps(steps);
            return plan;
        }

        private ContactPlanStep step(int order, ChannelType ch) {
            ContactPlanStep s = new ContactPlanStep();
            s.setStepOrder(order);
            s.setChannelType(ch);
            s.setDelayMinutes(0);
            s.setObservationMinutes(0);
            s.setStatus(StepStatus.PENDING);
            return s;
        }
    }

    /** 按 channelType 从同一份快照取 targetAddress，并记录用于断言。 */
    static class RecordingStepResolver implements StepResolver {
        final Map<ChannelType, String> targetByChannel = new EnumMap<>(ChannelType.class);

        @Override
        public StepCommand resolve(ExecutionContext context) {
            ChannelType ch = context.getCurrentStep().getChannelType();
            String target = address(ch, context.getContextSnapshot());
            targetByChannel.put(ch, target);
            return StepCommand.builder()
                    .channelType(ch)
                    .targetAddress(target)
                    .templateId("T")
                    .idempotencyKey("k-" + ch)
                    .build();
        }

        private String address(ChannelType ch, ContextSnapshot snap) {
            UserProfile.BasicInfo basic = snap.getUserProfile().getBasic();
            switch (ch) {
                case PUSH:
                    return snap.getUserProfile().getDevice().getJpushToken();
                case EMAIL:
                    return basic.getEmail();
                default:
                    return basic.getPrimaryPhone();
            }
        }
    }

    static class StubChannelGateway implements ChannelGateway {
        @Override
        public StepResult dispatch(StepCommand command) {
            return StepResult.builder()
                    .success(true)
                    .contactResult(ContactResult.DELIVERED)
                    .retryable(false)
                    .providerMsgId("mock-" + command.getChannelType())
                    .build();
        }
    }

    static class StubCaseService implements CaseService {
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
            basic.setPrimaryPhone(PHONE);
            basic.setEmail(EMAIL);
            UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();
            device.setJpushToken(FCM);
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
