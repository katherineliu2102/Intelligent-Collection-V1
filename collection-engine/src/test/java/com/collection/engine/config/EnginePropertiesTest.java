package com.collection.engine.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 步骤幂等锁与回调窗口的时长约束（核心引擎规格 §执行锁）。 */
class EnginePropertiesTest {

    @Test
    @DisplayName("幂等锁 TTL 恒不短于回调等待窗口 —— 否则异步渠道等回调期间会二次外呼")
    void idempotencyTtlNeverShorterThanCallbackWindow() {
        EngineProperties.Step step = new EngineProperties.Step();
        assertThat(step.effectiveIdempotencyTtlMinutes())
                .isGreaterThanOrEqualTo(step.getCallbackTimeoutMinutes());

        // 回调窗口调大到超过 TTL 配置值时，实际 TTL 必须跟着抬升而不是停在配置值
        step.setCallbackTimeoutMinutes(120);
        assertThat(step.effectiveIdempotencyTtlMinutes()).isEqualTo(120);

        // 窗口小于 TTL 配置值时沿用配置值，不得反向缩短
        step.setCallbackTimeoutMinutes(3);
        assertThat(step.effectiveIdempotencyTtlMinutes())
                .isEqualTo(step.getIdempotencyTtlMinutes());
    }

    @Test
    @DisplayName("回调窗口默认 10 分钟：显著大于单通外呼时长，又不至于把失败步骤压一小时")
    void callbackWindowDefaultsToTenMinutes() {
        assertThat(new EngineProperties.Step().getCallbackTimeoutMinutes()).isEqualTo(10);
    }
}
