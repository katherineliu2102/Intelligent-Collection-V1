package com.collection.common.enums;

/**
 * 穷尽策略动作。ExhaustionResult.action。对应领域模型 §2.10。
 */
public enum ExhaustionAction {
    /** 同阶段立即续建：创建新一轮计划（templateId 必填）。 */
    REBUILD,
    /** 升档：发布 STAGE_CHANGED 提升催收强度（targetStage 必填）。 */
    ESCALATE,
    /** 停止：标记计划 PLAN_COMPLETED（终态）。 */
    COMPLETE
}
