package com.collection.engine.spi;

/**
 * SPI 调用超过硬超时阈值（核心引擎规格 §4.1：超时等同运行时异常）。
 *
 * <p>是否致命由调用方决定：Guard 捕获 → fail-close SKIPPED；Resolver 捕获 → FAILED；
 * PlanFactory / AdvancementPolicy / ExhaustionPolicy 不捕获 → 上抛触发 NACK 重消费。
 */
public class SpiTimeoutException extends RuntimeException {

    private final String spiName;
    private final long timeoutMs;

    public SpiTimeoutException(String spiName, long timeoutMs) {
        super("SPI [" + spiName + "] timed out after " + timeoutMs + "ms");
        this.spiName = spiName;
        this.timeoutMs = timeoutMs;
    }

    public String getSpiName() {
        return spiName;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
