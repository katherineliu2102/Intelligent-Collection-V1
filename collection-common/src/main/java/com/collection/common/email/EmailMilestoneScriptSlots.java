package com.collection.common.email;

import com.collection.common.enums.Stage;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Email 里程碑 scriptSlot 解析（Phase 1 Mock / DefaultStepResolver 共用）。
 *
 * <p>Phase 1 仅启用 5 个里程碑 Email（降频、控封号风险）；其余 HTML 保留于 {@code docs/email-templates/} 备用。 测试 case 可通过
 * {@link com.collection.common.model.CaseContext#setEmailScriptSlot} 显式指定。
 */
public final class EmailMilestoneScriptSlots {

    /** Phase 1 实际发信 + Nacos {@code channel.sendgrid.templates} 映射的 scriptSlot。 */
    public static final Set<String> PHASE1_ACTIVE =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    "S0_DUE_TODAY_EMAIL",
                                    "S1_EMAIL_OVERDUE_NOTICE",
                                    "S2_EMAIL_ENTRY",
                                    "S4_EMAIL_ENTRY",
                                    "S4_EMAIL_PRE_CLOSE")));

    /** Phase 1 各 Email 触发的精确 DPD（14:00 PHT 里程碑日）。 */
    public static final int DPD_S0_DUE_TODAY = 0;

    public static final int DPD_S1_OVERDUE = 1;
    public static final int DPD_S2_ENTRY = 4;
    public static final int DPD_S4_ENTRY = 31;
    public static final int DPD_S4_PRE_CLOSE = 75;

    private EmailMilestoneScriptSlots() {}

    public static boolean isPhase1Active(String scriptSlot) {
        return scriptSlot != null && PHASE1_ACTIVE.contains(scriptSlot);
    }

    /** Phase 1：仅在里程碑触发日返回 scriptSlot；其余 DPD 返回 null（不应发 Email）。 */
    public static String resolvePhase1ByDpd(int dpd) {
        switch (dpd) {
            case DPD_S0_DUE_TODAY:
                return "S0_DUE_TODAY_EMAIL";
            case DPD_S1_OVERDUE:
                return "S1_EMAIL_OVERDUE_NOTICE";
            case DPD_S2_ENTRY:
                return "S2_EMAIL_ENTRY";
            case DPD_S4_ENTRY:
                return "S4_EMAIL_ENTRY";
            case DPD_S4_PRE_CLOSE:
                return "S4_EMAIL_PRE_CLOSE";
            default:
                return null;
        }
    }

    /** 按 dpd 解析；Phase 1 等同 {@link #resolvePhase1ByDpd(int)}。 */
    public static String resolveByDpd(int dpd) {
        return resolvePhase1ByDpd(dpd);
    }

    /** 推断 stage（Mock 进案缺省 stage 参数时使用）。 */
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
