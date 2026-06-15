package com.collection.engine.config;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 引擎配置参数。对应基础设施规范 附录·配置参数汇总。
 *
 * <p>Phase 1 骨架从 application.properties（前缀 engine）读取；
 * 生产应改为从 t_system_property 加载 + 定时轮询热更（基础设施规范 §6.1）。
 */
@Getter
@Component
@ConfigurationProperties(prefix = "engine")
public class EngineProperties {

    private final Step step = new Step();
    private final Plan plan = new Plan();
    private final Consumer consumer = new Consumer();
    private final Context context = new Context();
    private final Spi spi = new Spi();

    @Data
    public static class Step {
        private int idempotencyTtlMinutes = 15;
        private int maxRetryCount = 3;
        private int retryBaseIntervalSeconds = 30;
        private int retryMaxIntervalSeconds = 300;
        private int retryBackoffFactor = 2;
        private int callbackTimeoutMinutes = 60;
    }

    @Data
    public static class Plan {
        private int maxRebuildCount = 2;
    }

    @Data
    public static class Consumer {
        private int threadPoolSize = 8;
        private int queueCapacity = 256;
        /** Cron 扫描单批上限（基础设施规范 §4）。 */
        private int scanLimit = 1000;
    }

    @Data
    public static class Context {
        private int historyMaxRecords = 50;
    }

    /**
     * SPI 硬超时配置（核心引擎规格 §4.1 / 基础设施规范 附录 engine.spi.*）。
     *
     * <p>引擎调用 5 个 SPI 时用 {@code Future.get(timeoutMs)} 强制截断，
     * 超时按对应失败语义处理（Guard fail-close→SKIPPED、Resolver→FAILED、其余 NACK）。
     * {@code timeoutEnabled=false} 时退化为直连调用（不引第二个线程池），便于本地/单测。
     */
    @Data
    public static class Spi {
        private boolean timeoutEnabled = true;
        private long planFactoryTimeoutMs = 50;
        private long executionGuardTimeoutMs = 20;
        private long stepResolverTimeoutMs = 50;
        private long advancementPolicyTimeoutMs = 10;
        private long exhaustionPolicyTimeoutMs = 50;
    }
}
