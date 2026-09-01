package com.collection.engine.fault;

import com.collection.common.event.CollectionEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 消费链路的受控故障注入（T5-R3…R6、R13 的取证前置）。
 *
 * <p>没有它这几条在 Pilot 上做不了：PEL 滞留、超时认领重投、毒消息进 DLQ 与重放，都要求「同一条消息反复失败」。 现有的 {@code
 * IngestionFaultInjector} 只覆盖 Pub/Sub 接入侧的 NACK，走不到 Redis Stream 的 PEL/DLQ 路径；
 * 而制造真实故障（吊销库权限、锁死行）会波及其他事件， 取不到干净证据。
 *
 * <p><b>两个注入位不能互相替代</b>：
 *
 * <ul>
 *   <li>{@link Position#BEFORE_HANDLER} — 业务未执行即失败，消息留在 PEL。对应 R3（滞留可见）、R4（超时认领后重投并最终 ACK）、
 *       R5/R6（投递次数耗尽后进 DLQ）。
 *   <li>{@link Position#AFTER_HANDLER} — 业务已执行、去重标记已写、ACK 前失败。这是生产上最危险的一段窗口（提交后进程崩），
 *       重投时应命中去重直接跳过，对应 R13「PEL 重投不重复执行业务」。没有这个注入位只能靠手工改库伪造，取证不可信。
 * </ul>
 *
 * <p><b>三重约束防误伤</b>：{@code engine.fault-injection.enabled} 必须显式为 true（默认 false，且需重启才能开）； 必须显式
 * arm；arm 时必须给出 eventType 或 eventId 之一作为靶向条件，不接受"命中任意事件"。
 *
 * <p><b>T4 前必须关闭</b>：本开关属测试资产，进入固定 50 案 Pilot 前须置回 false 并确认已 disarm。
 */
@Component
public class EngineFaultInjector {

    private static final Logger log = LoggerFactory.getLogger(EngineFaultInjector.class);

    /** 无限次失败：制造 MAX_DELIVERY_EXCEEDED 时用，同一条消息要连续失败到投递次数耗尽。 */
    public static final long UNLIMITED = -1L;

    public enum Position {
        BEFORE_HANDLER,
        AFTER_HANDLER
    }

    @Value("${engine.fault-injection.enabled:false}")
    private boolean enabled;

    private volatile Armed armed;
    private final AtomicLong firedCount = new AtomicLong();

    /** 手工构造（纯逻辑单测、非 Spring 环境）时的禁用实例。 */
    public static EngineFaultInjector disabled() {
        return new EngineFaultInjector();
    }

    public static EngineFaultInjector enabledForTest() {
        EngineFaultInjector injector = new EngineFaultInjector();
        injector.enabled = true;
        return injector;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 武装一次注入。
     *
     * @param position 注入位
     * @param eventType 目标事件类型；与 eventId 至少给一个
     * @param eventId 目标事件 ID；给定时只对该条生效，适合造 MAX_DELIVERY_EXCEEDED
     * @param remaining 剩余失败次数，{@link #UNLIMITED} 表示不限次
     */
    public synchronized Map<String, Object> arm(
            Position position, String eventType, String eventId, long remaining) {
        if (!enabled) {
            throw new IllegalStateException("engine.fault-injection.enabled=false，拒绝武装；需改配置并重启后再试");
        }
        if (position == null) {
            throw new IllegalArgumentException("position 必填：BEFORE_HANDLER 或 AFTER_HANDLER");
        }
        // 不接受无靶向的注入：Pilot 上真实事件与演练事件混在同一条流里，
        // 命中任意事件会把无关案件推进 DLQ，且事后分不清哪些失败是注入的。
        boolean noType = eventType == null || eventType.trim().isEmpty();
        boolean noId = eventId == null || eventId.trim().isEmpty();
        if (noType && noId) {
            throw new IllegalArgumentException("eventType 与 eventId 至少给一个，拒绝无靶向注入");
        }
        long normalized = remaining == UNLIMITED ? UNLIMITED : Math.max(1L, remaining);
        this.armed =
                new Armed(
                        position,
                        noType ? null : eventType.trim(),
                        noId ? null : eventId.trim(),
                        new AtomicLong(normalized));
        firedCount.set(0);
        log.warn(
                "[FaultInjection] 已武装 position={} eventType={} eventId={} remaining={}",
                position,
                eventType,
                eventId,
                normalized == UNLIMITED ? "UNLIMITED" : normalized);
        return status();
    }

    public synchronized Map<String, Object> disarm() {
        Map<String, Object> previous = status();
        this.armed = null;
        log.warn("[FaultInjection] 已解除武装");
        return previous;
    }

    public Map<String, Object> status() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", enabled);
        Armed current = armed;
        state.put("armed", current != null);
        state.put("firedCount", firedCount.get());
        if (current != null) {
            state.put("position", current.position.name());
            state.put("eventType", current.eventType);
            state.put("eventId", current.eventId);
            long left = current.remaining.get();
            state.put("remaining", left == UNLIMITED ? "UNLIMITED" : left);
        }
        return state;
    }

    /** 命中则抛出瞬态异常。调用方无需判空：未启用或未武装时本方法直接返回。 */
    public void failIfArmed(Position position, CollectionEvent event) {
        if (!enabled || event == null) {
            return;
        }
        Armed current = armed;
        if (current == null || current.position != position || !current.matches(event)) {
            return;
        }
        if (!current.consume()) {
            return;
        }
        long fired = firedCount.incrementAndGet();
        long left = current.remaining.get();
        log.warn(
                "[FaultInjection] 注入失败 position={} eventId={} eventType={} 已注入={} 剩余={}",
                position,
                event.getEventId(),
                event.getEventType(),
                fired,
                left == UNLIMITED ? "UNLIMITED" : left);
        throw new InjectedFaultException(position, event.getEventId());
    }

    private static final class Armed {
        private final Position position;
        private final String eventType;
        private final String eventId;
        private final AtomicLong remaining;

        private Armed(Position position, String eventType, String eventId, AtomicLong remaining) {
            this.position = position;
            this.eventType = eventType;
            this.eventId = eventId;
            this.remaining = remaining;
        }

        private boolean matches(CollectionEvent event) {
            if (eventId != null && !eventId.equals(event.getEventId())) {
                return false;
            }
            return eventType == null
                    || (event.getEventType() != null
                            && eventType.equalsIgnoreCase(event.getEventType().name()));
        }

        /** 不限次时不递减；有限次用 CAS 扣减，避免多消费线程并发下超额注入。 */
        private boolean consume() {
            while (true) {
                long left = remaining.get();
                if (left == UNLIMITED) {
                    return true;
                }
                if (left <= 0) {
                    return false;
                }
                if (remaining.compareAndSet(left, left - 1)) {
                    return true;
                }
            }
        }
    }
}
