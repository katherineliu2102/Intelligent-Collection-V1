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
}
