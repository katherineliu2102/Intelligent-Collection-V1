package com.collection.common.enums;

/**
 * 催收阶段。对应领域模型 §2.9。
 *
 * <p>{@link #fromDpd(int)} DPD→Stage 映射边界已与渠道编排同事对齐（2026-06-15）： S1 DPD∈[1,3]、S2 DPD∈[4,15]、S3
 * DPD∈[16,30]、S4 DPD∈[31,∞)。 DPD≥91 停催逻辑由 PlanFactory.shouldRejectPlan / ingestion 日切独立处理，Stage
 * 不感知。
 */
public enum Stage {
    S0(-3, 0),
    S1(1, 3),
    S2(4, 15),
    S3(16, 30),
    S4(31, Integer.MAX_VALUE);

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
     * DPD → Stage 映射（边界与渠道编排对齐）。 D-3~D0 → S0，D1~D3 → S1，D4~D15 → S2，D16~D30 → S3，D31+ → S4。 D91+
     * 停催（CASE_CEASED）不属于 Stage 概念，由 PlanFactory.shouldRejectPlan / ingestion 单独拦截。
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
