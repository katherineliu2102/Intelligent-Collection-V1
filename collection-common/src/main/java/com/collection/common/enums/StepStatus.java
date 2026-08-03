package com.collection.common.enums;

/** 步骤执行状态。对应领域模型 §2.4。 */
public enum StepStatus {
    PENDING,
    EXECUTING,
    COMPLETED,
    SKIPPED,
    FAILED;

    /** 终态：已写 completed_at，不得再被 due/timeout 事件重新前置为 EXECUTING。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == SKIPPED || this == FAILED;
    }
}
