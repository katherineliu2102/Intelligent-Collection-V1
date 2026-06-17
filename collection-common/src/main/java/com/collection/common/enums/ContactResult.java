package com.collection.common.enums;

/**
 * 触达结果。对应领域模型 §6.2。
 *
 * <p>priority 用于回调更新时的"只升级不降级"判定： DELIVERED(1) &lt; READ(2) &lt; CLICKED(3) &lt; REPLIED(4)。
 * 电话类结果（ANSWERED/NO_ANSWER/BUSY）及失败类为终态，priority=0，不参与升级链。
 */
public enum ContactResult {
    DELIVERED(1),
    READ(2),
    CLICKED(3),
    REPLIED(4),
    ANSWERED(0),
    NO_ANSWER(0),
    BUSY(0),
    FAILED(0),
    REJECTED(0),
    SENT_NO_RESPONSE(0),
    COMPLIANCE_BLOCKED(0),
    /** StepResolver 主动跳过该步（如 EMAIL 非里程碑 DPD / 无邮箱）；非失败，引擎照常推进。 */
    SKIPPED(0),
    CHANNEL_DOWN(0);

    private final int priority;

    ContactResult(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    /** 回调更新时判断 newResult 是否应覆盖 currentResult。 仅当 newResult 的 priority 严格大于 currentResult 时升级。 */
    public boolean canUpgradeFrom(ContactResult current) {
        if (current == null) {
            return true;
        }
        return this.priority > current.priority;
    }
}
