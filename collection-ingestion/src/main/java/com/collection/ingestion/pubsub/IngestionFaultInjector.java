package com.collection.ingestion.pubsub;

import com.collection.ingestion.config.IngestionProperties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L4b-7 受控 NACK 故障注入：让下一条（或下 N 条）白名单 case_push 在落库前抛异常，
 * 从而不 ack、由 PubSub 重投，用于验证「重投一次 + 幂等收敛」。
 *
 * <p>三重约束，防止误伤真实链路：{@code collection.ingestion.fault-injection-enabled} 必须为 true；
 * 只对白名单 loan_id 生效（调用点已在白名单判断之后）；必须显式 arm，且每 arm 一次只失败一次。
 */
@Component
public class IngestionFaultInjector {

    private static final Logger log = LoggerFactory.getLogger(IngestionFaultInjector.class);

    @Resource private IngestionProperties props;

    private final AtomicInteger remainingFailures = new AtomicInteger(0);

    /** 预约后续 {@code count} 条消息各失败一次；返回实际生效的剩余次数。 */
    public int arm(int count) {
        if (!props.isFaultInjectionEnabled()) {
            log.warn("[Ingestion] fault-injection 未启用，arm 被忽略");
            return 0;
        }
        int normalized = Math.max(1, count);
        remainingFailures.set(normalized);
        log.warn("[Ingestion] L4b-7 故障注入已武装：后续 {} 条白名单 case_push 将 NACK 一次", normalized);
        return normalized;
    }

    public int disarm() {
        return remainingFailures.getAndSet(0);
    }

    public int remaining() {
        return remainingFailures.get();
    }

    /** 命中时抛出瞬态异常 → 消费者 {@code reply.nack()} → PubSub 重投。 */
    void failIfArmed(Long caseId) {
        if (!props.isFaultInjectionEnabled()) {
            return;
        }
        int before = remainingFailures.get();
        if (before <= 0 || !remainingFailures.compareAndSet(before, before - 1)) {
            return;
        }
        log.warn("[Ingestion] L4b-7 注入瞬态失败 caseId={} 剩余={}", caseId, before - 1);
        throw new IllegalStateException("L4b-7 injected transient failure for caseId=" + caseId);
    }
}
