package com.collection.engine.config;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 引擎配置参数。对应基础设施规范 附录·配置参数汇总。
 *
 * <p>Phase 1 骨架从 application.properties（前缀 engine）读取； 生产应改为从 t_system_property 加载 + 定时轮询热更（基础设施规范
 * §6.1）。
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
    private final DecisionLog decisionLog = new DecisionLog();
    private final DeliveryAudit deliveryAudit = new DeliveryAudit();
    private final Outbox outbox = new Outbox();
    private final Reaper reaper = new Reaper();

    @Data
    public static class Step {
        private int idempotencyTtlMinutes = 15;
        private int maxRetryCount = 3;
        private int retryBaseIntervalSeconds = 30;
        private int retryMaxIntervalSeconds = 300;
        private int retryBackoffFactor = 2;
        /**
         * 异步渠道回调等待窗口。AI_CALL 一通外呼含排队通常几分钟内出结果；30 分钟覆盖排队+通话， 又不会把失败步骤压到一小时才收敛。Resolver 写入的 {@code
         * metadata.timeoutMinutes} 覆盖本默认值。
         *
         * <p>调小的代价是迟到的回调会落在已终态的步骤上被忽略，结果记为 FAILED 而非真实结局。
         */
        private int callbackTimeoutMinutes = 30;

        /**
         * 幂等锁实际 TTL：不得短于回调等待窗口，否则异步渠道等回调期间收到重复 due 会重新拿锁并二次外呼。 退避重试不受影响——幂等 key 含 retryCount，重试后
         * key 已变。
         */
        public int effectiveIdempotencyTtlMinutes() {
            return Math.max(idempotencyTtlMinutes, callbackTimeoutMinutes);
        }
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
     * <p>引擎调用 5 个 SPI 时用 {@code Future.get(timeoutMs)} 强制截断， 超时按对应失败语义处理（Guard
     * fail-close→SKIPPED、Resolver→FAILED、其余 NACK）。 {@code timeoutEnabled=false}
     * 时退化为直连调用（不引第二个线程池），便于本地/单测。
     */
    @Data
    public static class Spi {
        private boolean timeoutEnabled = true;
        private long planFactoryTimeoutMs = 50;
        private long executionGuardTimeoutMs = 50;
        private long stepResolverTimeoutMs = 50;
        private long advancementPolicyTimeoutMs = 10;
        private long exhaustionPolicyTimeoutMs = 50;
    }

    /**
     * 事件发件箱（Transactional Outbox）。派生事件与状态迁移同事务落盘，提交后仍由原路径即时发布；
     * 发件箱只在即时发布没能确认时兜底重发，因此正常链路的延迟与投递量都不受影响。
     */
    @Data
    public static class Outbox {
        /** 关闭时引擎不落发件箱、不轮询（退回"提交后发布"语义，仅供本地调试）。 */
        private boolean enabled = true;

        /** 轮询间隔。@Scheduled 需要字面量占位符，实际读取见 OutboxPublisher。 */
        private long pollIntervalMs = 2000;

        /** 宽限期：入库时 next_retry_at = now + 该值。即时发布通常在毫秒级确认， 宽限期过短会让轮询器把正常事件重发一遍，过长则拉长故障恢复时间。 */
        private int graceSeconds = 30;

        /** 单轮兜底重发上限。 */
        private int batchSize = 200;

        /** 多实例发布器的认领租约。必须覆盖一次 Redis publish 的最长合理耗时；实例在租约内崩溃，行在到期后才会被其他实例重新认领。 */
        private int leaseSeconds = 60;

        /** 重发退避：nextRetryAt = now + min(grace * factor^retryCount, maxBackoffSeconds)。 */
        private int backoffFactor = 3;

        private int maxBackoffSeconds = 900;

        /** 超过该次数标记 FAILED，转人工（告警信号，不再自动重发）。 */
        private int maxRetryCount = 8;
    }

    /** 停摆巡检。只检测并告警"非终态但不会再被任何扫描拾取"的计划，不自动重发触达—— 触达是不可回滚的外部动作，误判的代价由用户承担。 */
    @Data
    public static class Reaper {
        private boolean enabled = true;

        /** 巡检间隔。@Scheduled 需要字面量占位符，实际读取见 StuckPlanReaper。 */
        private long intervalMs = 300000;

        /** 计划 updated_at 早于 now - 该值才纳入。须不短于 Outbox 自动重发窗口，避免 Outbox 仍在自愈时提前报停摆。 */
        private int idleMinutes = 75;

        private int batchSize = 200;
    }

    /** 触达审计 HMAC；密钥仅由环境变量/Secret 注入，绝不入库或入仓。 */
    @Data
    public static class DeliveryAudit {
        private String hmacKey = "";
        private String contentKeyId = "";
    }

    /**
     * 决策日志落库配置（供数仓分析，引擎只写不读）。Phase 1 仅记 StepResolver（④）step 级决策， 引擎侧合成 engineType=RULE /
     * confidence=1.0；fail-open 事务外写，不阻断触达链路。
     */
    @Data
    public static class DecisionLog {
        /** 关闭时引擎完全跳过 decision_log 写入（默认开）。 */
        private boolean enabled = true;
        /** 规则引擎版本标识，写入 t_decision_log.engine_version。 */
        private String version = "rule-v1";
    }
}
