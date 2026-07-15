package com.collection.common.enums;

/**
 * 步骤执行状态。对应领域模型 §2.4。
 */
public enum StepStatus {
    PENDING,
    EXECUTING,
    COMPLETED,
    SKIPPED,
    FAILED
}
