package com.collection.common.enums;

/**
 * 推进决策。AdvancementPolicy.decide() 的输出。对应领域模型 §5.6、核心引擎规格 §2.3.2。
 */
public enum AdvancementDecision {
    /** 推进到下一步：注册下一步 Job 或立即执行。 */
    ADVANCE_NEXT,
    /** 计划完成：进入终态 PLAN_COMPLETED。 */
    PLAN_COMPLETED,
    /** 计划穷尽：发布 PLAN_EXHAUSTED 事件 → §2.5。 */
    PLAN_EXHAUSTED
}
