package com.collection.engine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.event.EventHandler;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 建计划类事件对「单活跃计划」唯一键冲突的归一。
 *
 * <p>{@code createPlanForStage} 的预检查是非加锁读，同案件同阶段的两条事件并发到达时都能通过预检查， 后提交者在 INSERT 处撞 {@code
 * uk_active_stage_key}。唯一键已经保证了不会出现第二个活跃计划， 后到者该做的事就是幂等跳过。
 */
class EventConsumerDispatcherTest {

    /** 复刻 MySQL 经 MyBatis / Spring 包装后的报文形状：约束名只出现在链路深处的 cause 里。 */
    private static final DuplicateKeyException ACTIVE_PLAN_CONFLICT =
            new DuplicateKeyException(
                    "### Error updating database.  Cause: java.sql.SQLIntegrityConstraintViolationException: "
                            + "Duplicate entry '520049:S3' for key 't_contact_plan.uk_active_stage_key'",
                    new IllegalStateException(
                            "Duplicate entry '520049:S3' for key 't_contact_plan.uk_active_stage_key'"));

    private final Map<EventType, EventHandler> registered = new EnumMap<>(EventType.class);

    private PlanLifecycleManager manager;
    private EventConsumerDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        manager = mock(PlanLifecycleManager.class);
        CollectionEventBus bus =
                new CollectionEventBus() {
                    @Override
                    public void publish(CollectionEvent event) {}

                    @Override
                    public void subscribe(EventType eventType, EventHandler handler) {
                        registered.put(eventType, handler);
                    }
                };
        dispatcher = new EventConsumerDispatcher();
        ReflectionTestUtils.setField(dispatcher, "eventBus", bus);
        ReflectionTestUtils.setField(dispatcher, "manager", manager);
        ReflectionTestUtils.setField(
                dispatcher, "orchestrator", mock(StepExecutionOrchestrator.class));
        dispatcher.registerHandlers();
    }

    private CollectionEvent caseIngested() {
        return CollectionEvent.of(EventType.CASE_INGESTED).with(CollectionEvent.CASE_ID, 520049L);
    }

    @Test
    @DisplayName("并发重复 CASE_INGESTED 撞单活跃计划唯一键 → 幂等跳过，不上抛")
    void duplicateActivePlanIsSwallowed() {
        CollectionEvent event = caseIngested();
        when(manager.onCaseIngested(event)).thenThrow(ACTIVE_PLAN_CONFLICT);

        assertThatCode(() -> registered.get(EventType.CASE_INGESTED).handle(event))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("STAGE_CHANGED 与 PLAN_EXHAUSTED 同样会建计划，同样归一")
    void otherPlanCreatingEventsAreCoveredToo() {
        CollectionEvent staged = CollectionEvent.of(EventType.STAGE_CHANGED);
        CollectionEvent exhausted = CollectionEvent.of(EventType.PLAN_EXHAUSTED);
        when(manager.onStageChanged(staged)).thenThrow(ACTIVE_PLAN_CONFLICT);
        when(manager.onPlanExhausted(exhausted)).thenThrow(ACTIVE_PLAN_CONFLICT);

        assertThatCode(
                        () -> {
                            registered.get(EventType.STAGE_CHANGED).handle(staged);
                            registered.get(EventType.PLAN_EXHAUSTED).handle(exhausted);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("其它唯一键冲突必须上抛 —— 否则新增任何唯一键都会被静默跳过")
    void unrelatedDuplicateKeyStillPropagates() {
        CollectionEvent event = caseIngested();
        when(manager.onCaseIngested(event))
                .thenThrow(
                        new DuplicateKeyException(
                                "Duplicate entry 'abc' for key 't_event_outbox.uk_event_id'"));

        assertThatThrownBy(() -> registered.get(EventType.CASE_INGESTED).handle(event))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("不建计划的事件不套归一：还款路径的唯一键冲突必须照常上抛")
    void nonPlanCreatingEventsAreNotWrapped() {
        CollectionEvent event = CollectionEvent.of(EventType.REPAYMENT_RECEIVED);
        when(manager.onRepaymentReceived(event)).thenThrow(ACTIVE_PLAN_CONFLICT);

        assertThatThrownBy(() -> registered.get(EventType.REPAYMENT_RECEIVED).handle(event))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("自引用 cause 不得让约束名匹配陷入死循环")
    void selfReferencingCauseTerminates() {
        assertThat(
                        (Boolean)
                                ReflectionTestUtils.invokeMethod(
                                        EventConsumerDispatcher.class,
                                        "violatesSingleActivePlan",
                                        selfCausingException()))
                .isFalse();
    }

    private static Throwable selfCausingException() {
        return new Throwable("no constraint here") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
    }
}
