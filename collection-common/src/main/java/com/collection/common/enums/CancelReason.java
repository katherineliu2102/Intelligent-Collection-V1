package com.collection.common.enums;

/** 计划取消原因。对应领域模型 §6.7。 engineManaged 标注核心引擎状态机代码中直接写入的值。 */
public enum CancelReason {
    REPAID(true),
    STAGE_UPGRADE(true),
    /** Max DPD ≥91 完全停催（CASE_CEASED）。 */
    CEASED(true),
    COMPLAINT(false),
    MANUAL(false),
    /** Phase 2 预留：Phase 1 引擎不写入、不使用（核心引擎规格 §2.6）。 */
    PTP_EXPIRED(false);

    private final boolean engineManaged;

    CancelReason(boolean engineManaged) {
        this.engineManaged = engineManaged;
    }

    public boolean isEngineManaged() {
        return engineManaged;
    }
}
