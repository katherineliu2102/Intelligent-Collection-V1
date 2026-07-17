package com.collection.common.dto;

import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 守卫裁定。ExecutionGuard.evaluate() 的输出。对应领域模型 §5.3。
 */
@Getter
public class GuardVerdict {

    /** true=放行，false=拦截。 */
    private final boolean allowed;
    /** 拦截原因（allowed=true 时为 null）。 */
    private final String blockedReason;
    /** 拦截规则类型：FREQUENCY_LIMIT / TIME_WINDOW / CONNECT_AND_STOP / ABANDONMENT_RATE。 */
    private final String blockedRuleType;
    /** 延后执行的目标时间；仅 TIME_WINDOW 等可延后规则使用。 */
    private final LocalDateTime deferUntil;

    private GuardVerdict(
            boolean allowed, String blockedReason, String blockedRuleType, LocalDateTime deferUntil) {
        this.allowed = allowed;
        this.blockedReason = blockedReason;
        this.blockedRuleType = blockedRuleType;
        this.deferUntil = deferUntil;
    }

    public static GuardVerdict allow() {
        return new GuardVerdict(true, null, null, null);
    }

    public static GuardVerdict block(String reason, String ruleType) {
        return new GuardVerdict(false, reason, ruleType, null);
    }

    /** 拦截当前执行，但允许引擎在指定时点重新调度。 */
    public static GuardVerdict defer(String reason, String ruleType, LocalDateTime deferUntil) {
        return new GuardVerdict(false, reason, ruleType, deferUntil);
    }
}
