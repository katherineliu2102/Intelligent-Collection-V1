package com.collection.engine.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.collection.engine.config.EngineProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SpiInvoker 硬超时机制单测（核心引擎规格 §4.1）。
 *
 * <p>验证：超时 → SpiTimeoutException；运行时异常原样上抛；正常返回；直连模式不强制超时。 失败语义（fail-close /
 * NACK）由调用方决定，本类只验证调用器本身的"截断 + 异常透传"。
 */
class SpiInvokerTest {

    /** 构造一个开启硬超时、阈值放大到稳定可测的调用器（避免 10ms 量级的 CI 抖动）。 */
    private SpiInvoker timeoutInvoker() {
        EngineProperties props = new EngineProperties();
        EngineProperties.Spi spi = props.getSpi();
        spi.setTimeoutEnabled(true);
        spi.setExecutionGuardTimeoutMs(100);
        spi.setStepResolverTimeoutMs(100);
        spi.setPlanFactoryTimeoutMs(100);
        spi.setAdvancementPolicyTimeoutMs(100);
        spi.setExhaustionPolicyTimeoutMs(100);
        return new SpiInvoker(props);
    }

    @Test
    @DisplayName("正常返回：body 在阈值内完成 → 原值返回")
    void returnsResultWithinTimeout() {
        SpiInvoker invoker = timeoutInvoker();
        String result = invoker.call(SpiType.STEP_RESOLVER, () -> "ok");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("超时：body 超过阈值 → 抛 SpiTimeoutException（携带 spiName/timeoutMs）")
    void throwsOnTimeout() {
        SpiInvoker invoker = timeoutInvoker();
        Throwable t =
                catchThrowable(
                        () ->
                                invoker.call(
                                        SpiType.EXECUTION_GUARD,
                                        () -> {
                                            sleep(800);
                                            return "late";
                                        }));
        assertThat(t).isInstanceOf(SpiTimeoutException.class);
        SpiTimeoutException ex = (SpiTimeoutException) t;
        assertThat(ex.getSpiName()).isEqualTo(SpiType.EXECUTION_GUARD.name());
        assertThat(ex.getTimeoutMs()).isEqualTo(100L);
    }

    @Test
    @DisplayName("异常透传：body 抛运行时异常 → 原样上抛（不包装成超时）")
    void propagatesRuntimeException() {
        SpiInvoker invoker = timeoutInvoker();
        assertThatThrownBy(
                        () ->
                                invoker.call(
                                        SpiType.STEP_RESOLVER,
                                        () -> {
                                            throw new IllegalStateException("resolver boom");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("resolver boom");
    }

    @Test
    @DisplayName("MDC 跨线程传递：父线程上下文在 body 内可见")
    void propagatesMdcToWorker() {
        SpiInvoker invoker = timeoutInvoker();
        org.slf4j.MDC.put("caseId", "1002");
        try {
            String seen = invoker.call(SpiType.STEP_RESOLVER, () -> org.slf4j.MDC.get("caseId"));
            assertThat(seen).isEqualTo("1002");
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    @DisplayName("过载即拒：SPI 池占满时溢出调用被 shed 为 SpiTimeoutException（旧 CallerRuns 会内联跑、零异常）")
    void shedsWhenPoolSaturated() throws Exception {
        EngineProperties props = new EngineProperties();
        props.getConsumer().setThreadPoolSize(1); // 尽量小的池，容量 = 1 + poolSize*2
        EngineProperties.Spi spi = props.getSpi();
        spi.setTimeoutEnabled(true);
        spi.setExecutionGuardTimeoutMs(5000); // 放大阈值：占位任务撑住、不因超时提前触发
        SpiInvoker invoker = new SpiInvoker(props);

        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger shed = new AtomicInteger();
        List<Thread> holders = new ArrayList<>();
        // 大量阻塞占位调用，远超任何合理容量 → 溢出者必被拒。占位任务阻塞到 release。
        for (int i = 0; i < 64; i++) {
            Thread h =
                    new Thread(
                            () -> {
                                try {
                                    invoker.call(
                                            SpiType.EXECUTION_GUARD,
                                            () -> {
                                                try {
                                                    release.await();
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return "held";
                                            });
                                } catch (SpiTimeoutException e) {
                                    shed.incrementAndGet(); // 被拒（或超时）即计入 shed
                                }
                            });
            h.setDaemon(true);
            h.start();
            holders.add(h);
        }
        Thread.sleep(300); // 让线程完成提交，池饱和后溢出者立即被拒

        // AbortPolicy：溢出者被拒 → 至少一例 SpiTimeoutException；旧 CallerRunsPolicy 会内联跑 → 此处应为 0
        assertThat(shed.get()).isGreaterThanOrEqualTo(1);

        release.countDown();
        for (Thread h : holders) {
            h.join(6000);
        }
    }

    @Test
    @DisplayName("直连模式：不强制超时，慢 body 也照常返回（单测/本地用）")
    void directModeDoesNotEnforceTimeout() {
        SpiInvoker invoker = SpiInvoker.direct();
        String result =
                invoker.call(
                        SpiType.PLAN_FACTORY,
                        () -> {
                            sleep(50);
                            return "direct-ok";
                        });
        assertThat(result).isEqualTo("direct-ok");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
