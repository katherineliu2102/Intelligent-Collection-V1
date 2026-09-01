package com.collection.ingestion.pubsub;

import com.collection.ingestion.config.IngestionProperties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 受控 NACK 故障注入：让下一条（或下 N 条）白名单 v3 案件事件抛异常，从而不 ack、由 PubSub 重投。
 *
 * <p>两个注入点对应两条不同的重投语义，不能互相替代：
 *
 * <ul>
 *   <li>{@link #failIfArmed}（投影落库**前**）— L4b-7：重投时投影还没写过，走完整的 {@code APPLIED} 路径，验证「重投一次 + 幂等收敛」；
 *   <li>{@link #failAfterProjectionIfArmed}（投影落库**后**、发领域事件**前**）— L4b-11：重投时收件箱已是
 *       PENDING_PUBLISH，验证「只补发事件、不重复写投影、不重复入案」。这是生产上最危险的一段窗口（提交后进程崩）， 没有第二个注入点就只能靠手工改库伪造，取证不可信。
 * </ul>
 *
 * <p>三重约束，防止误伤真实链路：{@code collection.ingestion.fault-injection-enabled} 必须为 true； 只对白名单 loan_id
 * 生效（调用点均在白名单判断之后）；必须显式 arm，且每 arm 一次只失败一次。
 */
@Component
public class IngestionFaultInjector {

    private static final Logger log = LoggerFactory.getLogger(IngestionFaultInjector.class);

    @Resource private IngestionProperties props;

    private final AtomicInteger remainingFailures = new AtomicInteger(0);
    private final AtomicInteger remainingPostProjectionFailures = new AtomicInteger(0);

    /** 预约后续 {@code count} 条消息在投影落库前各失败一次；返回实际生效的剩余次数。 */
    public int arm(int count) {
        return armCounter(remainingFailures, count, "投影落库前");
    }

    /** 预约后续 {@code count} 条消息在投影已落库、领域事件未发出时各失败一次。 */
    public int armPostProjection(int count) {
        return armCounter(remainingPostProjectionFailures, count, "投影已落库/事件未发出");
    }

    public int disarm() {
        return remainingFailures.getAndSet(0) + remainingPostProjectionFailures.getAndSet(0);
    }

    public int remaining() {
        return remainingFailures.get();
    }

    public int remainingPostProjection() {
        return remainingPostProjectionFailures.get();
    }

    /** 命中时抛出瞬态异常 → 消费者 {@code reply.nack()} → PubSub 重投。 */
    void failIfArmed(Long caseId) {
        fireIfArmed(remainingFailures, caseId, "投影落库前");
    }

    /** 投影已提交、领域事件尚未发出时抛出瞬态异常，使重投命中 {@code PENDING_PUBLISH} 补发路径。 */
    void failAfterProjectionIfArmed(Long caseId) {
        fireIfArmed(remainingPostProjectionFailures, caseId, "投影已落库/事件未发出");
    }

    private int armCounter(AtomicInteger counter, int count, String where) {
        if (!props.isFaultInjectionEnabled()) {
            log.warn("[Ingestion] fault-injection 未启用，arm({}) 被忽略", where);
            return 0;
        }
        int normalized = Math.max(1, count);
        counter.set(normalized);
        log.warn("[Ingestion] 故障注入已武装（{}）：后续 {} 条白名单案件事件将 NACK 一次", where, normalized);
        return normalized;
    }

    private void fireIfArmed(AtomicInteger counter, Long caseId, String where) {
        if (!props.isFaultInjectionEnabled()) {
            return;
        }
        int before = counter.get();
        if (before <= 0 || !counter.compareAndSet(before, before - 1)) {
            return;
        }
        log.warn("[Ingestion] 注入瞬态失败（{}）caseId={} 剩余={}", where, caseId, before - 1);
        throw new IllegalStateException(
                "injected transient failure (" + where + ") for caseId=" + caseId);
    }
}
