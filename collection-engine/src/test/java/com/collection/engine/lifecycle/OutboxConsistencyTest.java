package com.collection.engine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.EventType;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import com.collection.engine.outbox.OutboxEventSink;
import java.util.Arrays;
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

/**
 * 状态迁移与派生事件的原子性（核心引擎规格 §7.2）。
 *
 * <p>被守护的失败场景：步骤已提交为终态，提交后的 STEP_COMPLETED 发布失败。此时原事件重投会因步骤已终态 而按 no-op
 * 返回，派生事件不会被重新推导——若事件没有随状态一起落盘，计划就此静默停摆。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxConsistencyTest {

    private static final long PLAN_ID = 100L;
    private static final long STEP_ID = 200L;

    @Mock private ContactPlanRepository planRepository;
    @Mock private TimelineRepository timelineRepository;
    @Mock private DeliveryAuditMetadata deliveryAuditMetadata;
    @Mock private OutboxEventSink outboxEventSink;

    @InjectMocks private StepOutcomeRecorder recorder;

    private ContactPlan plan;
    private ContactPlanStep step;

    @BeforeEach
    void setUp() {
        plan = new ContactPlan();
        plan.setId(PLAN_ID);
        plan.setCaseId(1002L);
        plan.setUserId(9001L);
        plan.setStatus(PlanStatus.STEP_EXECUTING);

        step = new ContactPlanStep();
        step.setId(STEP_ID);
        step.setPlanId(PLAN_ID);
        step.setStepOrder(2);
        step.setChannelType(ChannelType.SMS);
        step.setStatus(StepStatus.EXECUTING);
        step.setRetryCount(1);
    }

    @Test
    @DisplayName("步骤转终态与 STEP_COMPLETED 入箱在同一次调用内完成")
    void recordTerminalEnqueuesStepCompleted() {
        when(planRepository.transitionStepStatus(
                        eq(STEP_ID),
                        any(java.util.List.class),
                        any(StepStatus.class),
                        any(ContactResult.class)))
                .thenReturn(true);

        boolean recorded =
                recorder.recordTerminal(
                        plan,
                        step,
                        StepStatus.EXECUTING,
                        StepStatus.COMPLETED,
                        ContactResult.ANSWERED,
                        ChannelType.SMS,
                        "msg-1",
                        null);

        assertThat(recorded).isTrue();
        ArgumentCaptor<CollectionEvent> enqueued = ArgumentCaptor.forClass(CollectionEvent.class);
        verify(outboxEventSink).enqueue(enqueued.capture());
        assertThat(enqueued.getValue().getEventType()).isEqualTo(EventType.STEP_COMPLETED);
        assertThat(enqueued.getValue().getLong(CollectionEvent.PLAN_ID)).isEqualTo(PLAN_ID);
        assertThat(enqueued.getValue().getLong(CollectionEvent.STEP_ID)).isEqualTo(STEP_ID);
    }

    @Test
    @DisplayName("状态迁移被并发路径抢先（CAS 失败）→ 不入箱，避免凭空多出一个事件")
    void skippedTransitionDoesNotEnqueue() {
        when(planRepository.transitionStepStatus(
                        eq(STEP_ID),
                        any(java.util.List.class),
                        any(StepStatus.class),
                        any(ContactResult.class)))
                .thenReturn(false);

        boolean recorded =
                recorder.recordTerminal(
                        plan,
                        step,
                        Arrays.asList(StepStatus.EXECUTING),
                        StepStatus.COMPLETED,
                        ContactResult.ANSWERED,
                        ChannelType.SMS,
                        null,
                        null,
                        null);

        assertThat(recorded).isFalse();
        verify(outboxEventSink, never()).enqueue(any());
        verify(timelineRepository, never()).writeTimeline(any());
    }

    @Test
    @DisplayName("策略性跳过不写 timeline，但状态迁移与事件入箱仍同事务")
    void strategySkipEnqueuesWithoutTimeline() {
        when(planRepository.transitionStepStatus(
                        STEP_ID, StepStatus.EXECUTING, StepStatus.SKIPPED, ContactResult.SKIPPED))
                .thenReturn(true);

        assertThat(recorder.recordStrategySkipped(plan, step)).isTrue();

        verify(outboxEventSink).enqueue(any());
        verify(timelineRepository, never()).writeTimeline(any());
    }

    @Test
    @DisplayName("入箱记录与提交后即时发布共享确定性 eventId，否则轮询器会把正常事件全部重发一遍")
    void derivedEventIdIsDeterministic() {
        CollectionEvent enqueuedSide = EngineEvents.stepCompleted(plan, step);
        CollectionEvent publishedSide = EngineEvents.stepCompleted(plan, step);

        assertThat(publishedSide.getEventId()).isEqualTo(enqueuedSide.getEventId());
        assertThat(enqueuedSide.getEventId()).isEqualTo("STEP_COMPLETED:100:2:1");
    }

    @Test
    @DisplayName("同一步骤的不同重试次数是不同事件，不得被去重吞掉")
    void retriesProduceDistinctEventIds() {
        String first = EngineEvents.stepCompleted(plan, step).getEventId();
        step.setRetryCount(step.getRetryCount() + 1);
        String second = EngineEvents.stepCompleted(plan, step).getEventId();

        assertThat(second).isNotEqualTo(first);
    }
}
