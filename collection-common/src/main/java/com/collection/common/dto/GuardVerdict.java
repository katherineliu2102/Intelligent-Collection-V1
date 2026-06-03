package com.collection.common.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 守卫裁定。ExecutionGuard.evaluate() 的输出。对应领域模型 §4.2。
 */
@Getter
@AllArgsConstructor
public class GuardVerdict {

    /** true=放行，false=拦截。 */
    private final boolean allowed;
    /** 拦截原因（allowed=true 时为 null）。 */
    private final String blockedReason;
    /** 拦截规则类型：FREQUENCY_LIMIT / TIME_WINDOW / CONNECT_AND_STOP / ABANDONMENT_RATE。 */
    private final String blockedRuleType;

    public static GuardVerdict allow() {
        return new GuardVerdict(true, null, null);
    }

    public static GuardVerdict block(String reason, String ruleType) {
        return new GuardVerdict(false, reason, ruleType);
    }
}
