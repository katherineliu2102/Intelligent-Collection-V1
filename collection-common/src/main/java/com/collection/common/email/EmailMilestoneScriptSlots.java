package com.collection.common.email;

import com.collection.common.enums.Stage;

/**
 * Email 里程碑 scriptSlot 解析（Phase 1 Mock / DefaultStepResolver 共用）。
 *
 * <p>里程碑 DPD 与编排规格 §7.9 对齐；测试 case 可通过 {@link com.collection.common.model.CaseContext#setEmailScriptSlot} 显式指定。
 */
public final class EmailMilestoneScriptSlots {

    private EmailMilestoneScriptSlots() {
    }

    /**
     * 按当前 dpd 解析应发送的里程碑 Email scriptSlot（取已达到的最高里程碑）。
     */
    public static String resolveByDpd(int dpd) {
        if (dpd <= 0) {
            return "S0_DUE_TODAY_EMAIL";
        }
        if (dpd <= 1) {
            return "S1_EMAIL_OVERDUE_NOTICE";
        }
        if (dpd <= 3) {
            return "S1_EMAIL_STAGE_WARNING";
        }
        if (dpd <= 4) {
            return "S2_EMAIL_ENTRY";
        }
        if (dpd <= 7) {
            return "S2_EMAIL_MID";
        }
        if (dpd <= 12) {
            return "S2_EMAIL_PRE_S3";
        }
        if (dpd <= 16) {
            return "S3_EMAIL_ENTRY";
        }
        if (dpd <= 23) {
            return "S3_EMAIL_MID";
        }
        if (dpd <= 30) {
            return "S3_EMAIL_PRE_S4";
        }
        if (dpd <= 31) {
            return "S4_EMAIL_ENTRY";
        }
        if (dpd <= 45) {
            return "S4_EMAIL_FINAL_REMINDER";
        }
        if (dpd <= 60) {
            return "S4_EMAIL_MID";
        }
        if (dpd <= 90) {
            return "S4_EMAIL_PRE_CLOSE";
        }
        return null;
    }

    /** 推断 stage（仅 Mock 进案缺省 stage 参数时使用）。 */
    public static Stage inferStage(int dpd) {
        if (dpd <= 0) {
            return Stage.S0;
        }
        if (dpd <= 3) {
            return Stage.S1;
        }
        if (dpd <= 15) {
            return Stage.S2;
        }
        if (dpd <= 30) {
            return Stage.S3;
        }
        return Stage.S4;
    }
}
