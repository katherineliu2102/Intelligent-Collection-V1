package com.collection.common.enums;

/**
 * 触达计划状态。对应领域模型 §6.3、核心引擎规格 §2.1。
 * 6 态：4 非终态 + 2 终态。
 */
public enum PlanStatus {

    PENDING(false),
    STEP_SCHEDULED(false),
    STEP_EXECUTING(false),
    STEP_WAITING(false),
    PLAN_COMPLETED(true),
    PLAN_CANCELLED(true);

    private final boolean terminal;

    PlanStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** 是否为终态（PLAN_COMPLETED / PLAN_CANCELLED）。终态不可逆。 */
    public boolean isTerminal() {
        return terminal;
    }
}
