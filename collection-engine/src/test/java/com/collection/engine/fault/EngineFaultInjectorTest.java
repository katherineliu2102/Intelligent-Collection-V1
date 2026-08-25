package com.collection.engine.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 故障注入的靶向、计次与安全约束。误伤真实事件的代价是把无关案件推进 DLQ，故约束逐条锁住。 */
class EngineFaultInjectorTest {

    private static CollectionEvent event(EventType type, String eventId) {
        CollectionEvent e = CollectionEvent.of(type);
        e.setEventId(eventId);
        return e;
    }

    private static CollectionEvent planStepDue() {
        return event(EventType.PLAN_STEP_DUE, "evt-1");
    }

    @Test
    void disabledInjectorNeverFires() {
        EngineFaultInjector injector = EngineFaultInjector.disabled();

        assertThatCode(
                        () ->
                                injector.failIfArmed(
                                        EngineFaultInjector.Position.BEFORE_HANDLER, planStepDue()))
                .doesNotThrowAnyException();
        assertThat(injector.status()).containsEntry("enabled", false).containsEntry("armed", false);
    }

    /** 开关是配置项且需重启，登录态不足以打开——否则演练开关会变成随手可开的生产风险。 */
    @Test
    void armIsRejectedWhenSwitchIsOff() {
        EngineFaultInjector injector = EngineFaultInjector.disabled();

        assertThatThrownBy(
                        () ->
                                injector.arm(
                                        EngineFaultInjector.Position.BEFORE_HANDLER,
                                        "PLAN_STEP_DUE",
                                        null,
                                        1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("engine.fault-injection.enabled=false");
    }

    /** Pilot 上真实事件与演练事件共用一条流，无靶向注入会波及无关案件且事后分不清来源。 */
    @Test
    void armRequiresATarget() {
        EngineFaultInjector injector = EngineFaultInjector.enabledForTest();

        assertThatThrownBy(
                        () ->
                                injector.arm(
                                        EngineFaultInjector.Position.BEFORE_HANDLER, null, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少给一个");
        assertThatThrownBy(
                        () ->
                                injector.arm(
                                        EngineFaultInjector.Position.BEFORE_HANDLER, "  ", "  ", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firesOnlyForMatchingEventType() {
        EngineFaultInjector injector = EngineFaultInjector.enabledForTest();
        injector.arm(
                EngineFaultInjector.Position.BEFORE_HANDLER, "PLAN_STEP_DUE", null, Long.MAX_VALUE);

        assertThatCode(
                        () ->
                                injector.failIfArmed(
                                        EngineFaultInjector.Position.BEFORE_HANDLER,
                                        event(EventType.CASE_INGESTED, "evt-other")))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                injector.failIfArmed(
                                        EngineFaultInjector.Position.BEFORE_HANDLER, planStepDue()))
                .isInstanceOf(InjectedFaultException.class);
    }

    @Test
    void firesOnlyForMatchingEventId() {
        EngineFaultInjector injector = EngineFaultInjector.enabledForTest();
        injector.arm(EngineFaultInjector.Position.BEFORE_HANDLER, null, "evt-1", Long.MAX_VALUE);

        assertThatCode(
                        () ->
                                injector.failIfArmed(
                                        EngineFaultInjector.Position.BEFORE_HANDLER,
                                        event(EventType.PLAN_STEP_DUE, "evt-2")))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                injector.failIfArmed(
                                        EngineFaultInjector.Position.BEFORE_HANDLER, planStepDue()))
                .isInstanceOf(InjectedFaultException.class);
    }

    /** 两个注入位对应不同重投语义（PEL 滞留 vs 去重跳过），不能互相触发。 */
    @Test
    void positionsAreIndependent() {
        EngineFaultInjector injector = EngineFaultInjector.enabledForTest();
        injector.arm(
                EngineFaultInjector.Position.AFTER_HANDLER, "PLAN_STEP_DUE", null, Long.MAX_VALUE);

        assertThatCode(
                        () ->
                                injector.failIfArmed(
                                        EngineFaultInjector.Position.BEFORE_HANDLER, planStepDue()))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                injector.failIfArmed(
                                        EngineFaultInjector.Position.AFTER_HANDLER, planStepDue()))
                .isInstanceOf(InjectedFaultException.class);
    }

    @Test
    void limitedArmStopsAfterBudgetIsSpent() {
        EngineFaultInjector injector = EngineFaultInjector.enabledForTest();
        injector.arm(EngineFaultInjector.Position.BEFORE_HANDLER, "PLAN_STEP_DUE", null, 2);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(
                            () ->
                                    injector.failIfArmed(
                                            EngineFaultInjector.Position.BEFORE_HANDLER,
                                            planStepDue()))
                    .isInstanceOf(InjectedFaultException.class);
        }
        assertThatCode(
                        () ->
                                injector.failIfArmed(
                                        EngineFaultInjector.Position.BEFORE_HANDLER, planStepDue()))
                .doesNotThrowAnyException();
        assertThat(injector.status()).containsEntry("firedCount", 2L);
    }

    /** 造 MAX_DELIVERY_EXCEEDED 要求同一条消息连续失败到投递次数耗尽，故必须支持不限次。 */
    @Test
    void unlimitedArmKeepsFiring() {
        EngineFaultInjector injector = EngineFaultInjector.enabledForTest();
        injector.arm(
                EngineFaultInjector.Position.BEFORE_HANDLER,
                null,
                "evt-1",
                EngineFaultInjector.UNLIMITED);

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(
                            () ->
                                    injector.failIfArmed(
                                            EngineFaultInjector.Position.BEFORE_HANDLER,
                                            planStepDue()))
                    .isInstanceOf(InjectedFaultException.class);
        }
        Map<String, Object> status = injector.status();
        assertThat(status).containsEntry("remaining", "UNLIMITED").containsEntry("firedCount", 10L);
    }

    @Test
    void disarmStopsFiring() {
        EngineFaultInjector injector = EngineFaultInjector.enabledForTest();
        injector.arm(
                EngineFaultInjector.Position.BEFORE_HANDLER,
                "PLAN_STEP_DUE",
                null,
                EngineFaultInjector.UNLIMITED);
        injector.disarm();

        assertThatCode(
                        () ->
                                injector.failIfArmed(
                                        EngineFaultInjector.Position.BEFORE_HANDLER, planStepDue()))
                .doesNotThrowAnyException();
        assertThat(injector.status()).containsEntry("armed", false);
    }

    /** 消费池是多线程的：有限次预算若不用 CAS 扣减，会超额注入并打到计划外的事件上。 */
    @Test
    void limitedBudgetIsNotExceededUnderConcurrency() throws Exception {
        EngineFaultInjector injector = EngineFaultInjector.enabledForTest();
        injector.arm(EngineFaultInjector.Position.BEFORE_HANDLER, "PLAN_STEP_DUE", null, 5);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger fired = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            injector.failIfArmed(
                                    EngineFaultInjector.Position.BEFORE_HANDLER, planStepDue());
                        } catch (InjectedFaultException e) {
                            fired.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(fired.get()).isEqualTo(5);
    }
}
