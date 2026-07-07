package com.collection.engine.spi;

import com.collection.engine.config.EngineProperties;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
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
 * I/O 型 SPI（如 ExecutionGuard 的 Redis Lua）仍须自带 client 级超时（且 &lt; 执行器阈值）作第一道防线， 本调用器是保护 Consumer
 * 线程池不被拖垮的统一兜底。
 *
 * <p><b>过载即拒</b>：池满时用 {@link ThreadPoolExecutor.AbortPolicy} 主动 shed（映射为 {@link
 * SpiTimeoutException}）， 而非内联执行——不可中断 I/O 泄漏占满池后，内联跑会让 SPI 无界执行在持锁的调用线程上（3 个计划级 SPI 在
 * {@code @Transactional} 内调用）， 引发行锁 / DB 连接堆积雪崩。常态下 submitter 数 ≤ 池大小，不会触发拒绝。
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
        Future<T> future;
        try {
            future =
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
        } catch (RejectedExecutionException e) {
            // 池满即拒：SPI 线程被不可中断 I/O（如 Redis socket read）泄漏占满时，主动 shed 而非内联跑。
            // 按超时语义交调用方兜底（计划级 NACK / 步骤级 fail-close），避免拖垮持锁的调用线程。
            log.warn(
                    "[SpiInvoker] {} rejected: spi pool saturated, shedding → timeout semantics",
                    type);
            throw new SpiTimeoutException(type.name(), timeoutMs);
        }
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
                        // 池满即拒（AbortPolicy）：由 call() 映射为 SpiTimeoutException 交调用方 fail-close/NACK。
                        // 不用 CallerRunsPolicy——池满多因不可中断 I/O 泄漏，内联跑会让 SPI 无界执行在持锁的
                        // 调用线程上，引发行锁/DB 连接堆积雪崩；主动 shed 更安全（常态 submitter≤池大小，不触发）。
                        new ThreadPoolExecutor.AbortPolicy());
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
