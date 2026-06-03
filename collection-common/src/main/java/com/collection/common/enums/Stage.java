package com.collection.common.enums;

/**
 * 催收阶段。对应领域模型 §6.9。
 *
 * <p>{@link #fromDpd(int)} 为默认 DPD→Stage 映射。文档要求区间边界从
 * t_contact_plan_template 配置读取、不硬编码；Phase 1 骨架先用默认值，
 * 渠道编排接管模板表后可改为查表。
 */
public enum Stage {

    S0(-3, 0),
    S1(1, 3),
    S2(4, 10),
    S3(11, 15),
    S4(16, 30),
    S4_PLUS(31, Integer.MAX_VALUE);

    private final int minDpd;
    private final int maxDpd;

    Stage(int minDpd, int maxDpd) {
        this.minDpd = minDpd;
        this.maxDpd = maxDpd;
    }

    public int getMinDpd() {
        return minDpd;
    }

    public int getMaxDpd() {
        return maxDpd;
    }

    /**
     * 默认 DPD → Stage 映射。
     * D-3~D0 → S0，D+1~D+3 → S1，... D+31+ → S4_PLUS。
     * 注意：D+91 完全停催（CASE_CEASED）不属于 Stage 概念，由引擎单独处理（对齐待办 E1）。
     */
    public static Stage fromDpd(int dpd) {
        for (Stage stage : values()) {
            if (dpd >= stage.minDpd && dpd <= stage.maxDpd) {
                return stage;
            }
        }
        // dpd < -3 时尚未进入催收窗口，按 S0 兜底（实际是否建计划由 PlanFactory 决定）
        return S0;
    }
}
