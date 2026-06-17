package com.collection.engine.spi;

import com.collection.engine.config.EngineProperties;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SPI 硬超时统一调用器（核心引擎规格 §4.1）。
 *
 * <p>把"线程级强制超时 + 异常解包 + MDC 跨线程传递"集中到一处， 替代原先散落在 Orchestrator / LifecycleManager 的 5 段裸调， 让 5 个 SPI
 * 的失败语义可单测、可冻结。
 *
 * <p><b>职责边界</b>：本类只负责"在阈值内拿到结果，否则抛异常"。 fail-close（Guard→SKIPPED、Resolver→FAILED）与
 * NACK（PlanFactory/Advancement/Exhaustion） 仍由调用方的 try-catch 决定——超时被转为 {@link SpiTimeoutException}，
 * 运行时异常原样上抛，二者对调用方表现一致。
 *
 * <p><b>限制（不夸大）</b>：{@code Future.cancel(true)} 是尽力而为， 卡死在不可中断 I/O（如 socket read）的实现不一定能被真正终止。 因此
 * I/O 型 SPI（如 ExecutionGuard 的 Redis Lua）仍须自带 client 级超时作第一道防线， 本调用器是保护 Consumer 线程池不被拖垮的统一兜底。
 *
 * <p>{@code engine.spi.timeout-enabled=false} 时退化为直连（不提交线程池、不强制超时）， 用于本地调试与纯逻辑单测（见 {@link
 * #direct()}）。
 */
@Component
public class SpiInvoker {

    private static final Logger log = LoggerFactory.getLogger(SpiInvoker.class);
    private static final long DEFAULT_TIMEOUT_MS = 50L;

    /** null 表示直连模式（不强制超时）。 */
    private final ExecutorService pool;

    private final Map<SpiType, Long> timeoutsMs;

    @Autowired
    public SpiInvoker(EngineProperties props) {
        EngineProperties.Spi cfg = props.getSpi();
        Map<SpiType, Long> t = new EnumMap<>(SpiType.class);
        t.put(SpiType.PLAN_FACTORY, cfg.getPlanFactoryTimeoutMs());
        t.put(SpiType.EXECUTION_GUARD, cfg.getExecutionGuardTimeoutMs());
        t.put(SpiType.STEP_RESOLVER, cfg.getStepResolverTimeoutMs());
        t.put(SpiType.ADVANCEMENT_POLICY, cfg.getAdvancementPolicyTimeoutMs());
        t.put(SpiType.EXHAUSTION_POLICY, cfg.getExhaustionPolicyTimeoutMs());
        this.timeoutsMs = t;
        this.pool =
                cfg.isTimeoutEnabled() ? buildPool(props.getConsumer().getThreadPoolSize()) : null;
        log.info("[SpiInvoker] timeoutEnabled={} timeouts(ms)={}", cfg.isTimeoutEnabled(), t);
    }

    private SpiInvoker() {
        this.pool = null;
        this.timeoutsMs = new EnumMap<>(SpiType.class);
    }

    /** 直连模式实例（不强制超时）：供纯逻辑单测与本地调试使用。 */
    public static SpiInvoker direct() {
        return new SpiInvoker();
    }

    /**
     * 在该 SPI 的硬超时阈值内执行 {@code body}。
     *
     * @throws SpiTimeoutException 超过阈值（按调用方语义转 fail-close / NACK）
     * @throws RuntimeException body 自身抛出的运行时异常（原样上抛）
     * @throws SpiInvocationException body 抛出受检异常时的兜底包装
     */
    public <T> T call(SpiType type, Supplier<T> body) {
        if (pool == null) {
            return body.get();
        }
        long timeoutMs = timeoutsMs.getOrDefault(type, DEFAULT_TIMEOUT_MS);
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        Future<T> future =
                pool.submit(
                        () -> {
                            if (parentMdc != null) {
                                MDC.setContextMap(parentMdc);
                            }
                            try {
                                return body.get();
                            } finally {
                                MDC.clear();
                            }
                        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[SpiInvoker] {} exceeded hard timeout {}ms", type, timeoutMs);
            throw new SpiTimeoutException(type.name(), timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new SpiInvocationException(type.name(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new SpiTimeoutException(type.name(), timeoutMs);
        }
    }

    private static ExecutorService buildPool(int size) {
        int poolSize = Math.max(1, size);
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        poolSize,
                        poolSize,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(poolSize * 2),
                        r -> {
                            Thread t = new Thread(r, "engine-spi-" + seq.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        },
                        // 池满时由调用线程自跑（不强制超时但不丢任务）；SPI 调用极短，常态不触发
                        new ThreadPoolExecutor.CallerRunsPolicy());
        executor.prestartAllCoreThreads(); // 预热，避免首调线程创建延迟撞上 10ms 阈值
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
