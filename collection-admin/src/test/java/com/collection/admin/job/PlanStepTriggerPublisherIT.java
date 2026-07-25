package com.collection.admin.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.admin.CollectionApplication;
import com.collection.common.enums.AdvancementDecision;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.EventType;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.spi.AdvancementPolicy;
import com.collection.engine.lifecycle.StepExecutionOrchestrator;
import com.collection.service.mapper.ContactPlanMapper;
import com.collection.service.mapper.ContactPlanStepMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L3-5：真实 MySQL 已提交步骤经 scan → event bus → dispatcher 的持久化验证。
 *
 * <p>测试只调用 publisher（与 TriggerScanner / XXL handler 相同的委托），不启动 @Scheduled 扫描。 数据以专用 caseId 提交后在每例
 * finally 删除，避免共享测试库残留。
 */
@EnabledIfEnvironmentVariable(named = "L3_IT_DB_URL", matches = ".+")
@Tag("integration")
@SpringBootTest(
        classes = CollectionApplication.class,
        properties = {
            "SPRING_PROFILES_ACTIVE=l3-it",
            "spring.config.import=optional:nacos:",
            "spring.cloud.nacos.config.enabled=false"
        })
@ActiveProfiles("l3-it")
class PlanStepTriggerPublisherIT {

    private static final long DUE_CASE_ID = 99_009_980L;
    private static final long TIMEOUT_CASE_ID = 99_009_981L;

    @Autowired private PlanStepTriggerPublisher publisher;
    @Autowired private CollectionEventBus eventBus;
    @Autowired private ContactPlanMapper planMapper;
    @Autowired private ContactPlanStepMapper stepMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockBean private StepExecutionOrchestrator orchestrator;
    @MockBean private AdvancementPolicy advancementPolicy;

    @AfterEach
    void cleanUp() {
        deleteCase(DUE_CASE_ID);
        deleteCase(TIMEOUT_CASE_ID);
    }

    @Test
    void publishDueSteps_dispatchesCommittedStepAndMarksItExecuting() {
        ContactPlanStep step = seedDueStep(DUE_CASE_ID);
        assertTrue(
                stepMapper.selectDueSteps(LocalDateTime.now(), 10).stream()
                        .anyMatch(candidate -> step.getId().equals(candidate.getId())),
                "已提交 due 步骤必须可被真实 MyBatis 查询到");
        AtomicReference<CollectionEvent> received = new AtomicReference<>();
        eventBus.subscribe(
                EventType.PLAN_STEP_DUE,
                event -> {
                    if (step.getId().equals(event.getLong(CollectionEvent.STEP_ID))) {
                        received.set(event);
                    }
                });

        publisher.publishDueSteps();

        await(
                () ->
                        received.get() != null
                                && stepMapper.selectById(step.getId()).getTriggerTime() == null,
                "due 步骤应被 dispatcher 接收并清空 trigger_time");
        ContactPlan plan = planMapper.selectById(step.getPlanId());
        ContactPlanStep persistedStep = stepMapper.selectById(step.getId());
        assertEquals(step.getPlanId(), received.get().getLong(CollectionEvent.PLAN_ID));
        assertEquals(step.getId(), received.get().getLong(CollectionEvent.STEP_ID));
        assertEquals(PlanStatus.STEP_EXECUTING, plan.getStatus());
        assertEquals(StepStatus.EXECUTING, persistedStep.getStatus());
        verify(orchestrator, timeout(5000))
                .executeStep(any(ContactPlan.class), any(ContactPlanStep.class));
        assertTrue(
                stepMapper.selectDueSteps(LocalDateTime.now(), 10).stream()
                        .noneMatch(candidate -> step.getId().equals(candidate.getId())),
                "清空 trigger_time 后，重复扫描不得再次发布同一步骤");
    }

    @Test
    void publishTimeoutSteps_dispatchesCommittedStepAndMarksItFailed() {
        when(advancementPolicy.decide(any(), any())).thenReturn(AdvancementDecision.PLAN_COMPLETED);
        ContactPlanStep step = seedTimeoutStep(TIMEOUT_CASE_ID);
        assertTrue(
                stepMapper.selectTimeoutSteps(LocalDateTime.now(), 10).stream()
                        .anyMatch(candidate -> step.getId().equals(candidate.getId())),
                "已提交 timeout 步骤必须可被真实 MyBatis 查询到");
        AtomicReference<CollectionEvent> received = new AtomicReference<>();
        eventBus.subscribe(
                EventType.CALLBACK_TIMEOUT,
                event -> {
                    if (step.getId().equals(event.getLong(CollectionEvent.STEP_ID))) {
                        received.set(event);
                    }
                });

        publisher.publishTimeoutSteps();

        await(
                () ->
                        received.get() != null
                                && stepMapper.selectById(step.getId()).getStatus()
                                        == StepStatus.FAILED,
                "timeout 步骤应被 dispatcher 接收并标记 FAILED");
        assertNotNull(received.get());
        assertEquals(step.getPlanId(), received.get().getLong(CollectionEvent.PLAN_ID));
        assertEquals(step.getId(), received.get().getLong(CollectionEvent.STEP_ID));
    }

    private ContactPlanStep seedDueStep(long caseId) {
        return transactionTemplate.execute(
                status -> {
                    ContactPlan plan = newPlan(caseId, PlanStatus.PENDING);
                    planMapper.insert(plan);
                    ContactPlanStep step = newStep(plan.getId(), StepStatus.PENDING);
                    step.setTriggerTime(LocalDateTime.now().minusMinutes(1));
                    stepMapper.insert(step);
                    return step;
                });
    }

    private ContactPlanStep seedTimeoutStep(long caseId) {
        return transactionTemplate.execute(
                status -> {
                    ContactPlan plan = newPlan(caseId, PlanStatus.STEP_EXECUTING);
                    planMapper.insert(plan);
                    ContactPlanStep step = newStep(plan.getId(), StepStatus.EXECUTING);
                    step.setTimeoutTime(LocalDateTime.now().minusMinutes(1));
                    stepMapper.insert(step);
                    return step;
                });
    }

    private ContactPlan newPlan(long caseId, PlanStatus status) {
        ContactPlan plan = new ContactPlan();
        plan.setCaseId(caseId);
        plan.setUserId(caseId);
        plan.setStage(com.collection.common.enums.Stage.S1);
        plan.setStatus(status);
        plan.setCurrentStep(0);
        plan.setTotalSteps(1);
        plan.setContextSnapshot("{\"l3SchedulerIt\":true}");
        plan.setIdempotencyKey("l3-scan-it:" + caseId + ":" + System.nanoTime());
        plan.setRenewalPending(false);
        plan.setVersion(0);
        return plan;
    }

    private ContactPlanStep newStep(long planId, StepStatus status) {
        ContactPlanStep step = new ContactPlanStep();
        step.setPlanId(planId);
        step.setStepOrder(1);
        step.setChannelType(ChannelType.SMS);
        step.setTemplateId(101L);
        step.setDelayMinutes(0);
        step.setStatus(status);
        step.setObservationMinutes(0);
        step.setRetryCount(0);
        step.setIdempotencyKey(planId + ":1:0");
        return step;
    }

    private void deleteCase(long caseId) {
        jdbcTemplate.update(
                "DELETE s FROM t_contact_plan_step s "
                        + "JOIN t_contact_plan p ON p.id = s.plan_id WHERE p.case_id = ?",
                caseId);
        jdbcTemplate.update("DELETE FROM t_contact_timeline WHERE case_id = ?", caseId);
        jdbcTemplate.update("DELETE FROM t_contact_plan WHERE case_id = ?", caseId);
    }

    private void await(BooleanSupplier condition, String failureMessage) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failureMessage, interrupted);
            }
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }
}
