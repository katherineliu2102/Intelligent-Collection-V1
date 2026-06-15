package com.collection.engine.spi;

/**
 * 引擎调用的 5 个策略 SPI（核心引擎规格 §4.1）。
 *
 * <p>{@link SpiInvoker} 据此解析各 SPI 的硬超时阈值；失败语义（fail-close / NACK）
 * 仍由调用方（Orchestrator / LifecycleManager）的 try-catch 决定，本枚举只标识身份。
 */
public enum SpiType {
    PLAN_FACTORY,
    EXECUTION_GUARD,
    STEP_RESOLVER,
    ADVANCEMENT_POLICY,
    EXHAUSTION_POLICY
}
