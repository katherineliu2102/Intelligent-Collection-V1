package com.collection.engine.spi;

import com.collection.engine.config.EngineProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SpiInvoker 硬超时机制单测（核心引擎规格 §4.1）。
 *
 * <p>验证：超时 → SpiTimeoutException；运行时异常原样上抛；正常返回；直连模式不强制超时。
 * 失败语义（fail-close / NACK）由调用方决定，本类只验证调用器本身的"截断 + 异常透传"。
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
        Throwable t = catchThrowable(() -> invoker.call(SpiType.EXECUTION_GUARD, () -> {
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
        assertThatThrownBy(() -> invoker.call(SpiType.STEP_RESOLVER, () -> {
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
    @DisplayName("直连模式：不强制超时，慢 body 也照常返回（单测/本地用）")
    void directModeDoesNotEnforceTimeout() {
        SpiInvoker invoker = SpiInvoker.direct();
        String result = invoker.call(SpiType.PLAN_FACTORY, () -> {
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
