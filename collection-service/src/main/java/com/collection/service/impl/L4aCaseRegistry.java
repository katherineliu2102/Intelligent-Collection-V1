package com.collection.service.impl;

<<<<<<< HEAD
=======
import com.collection.common.enums.Stage;
import java.math.BigDecimal;
import java.time.LocalDate;

>>>>>>> origin/ca_branch
/**
 * L4a 官方用例专用合成案件（§L4a.2 / §L4a.5）。
 *
 * <p>与 Sms/Push/Email CaseRegistry 并列；{@link MockProfileService} 按 caseId 合并三渠道画像。
 */
final class L4aCaseRegistry {

    /** L4a-1：SMS + JPush + 126 邮箱三渠道合成 case。 */
    static final long THREE_CHANNEL = 94999L;

    /** Guard block（NO_PHONE）：单步 SMS 应 SKIPPED。 */
    static final long GUARD_NO_PHONE = 94801L;

    /** Exhaustion REBUILD/ESCALATE：无效 emailScriptSlot 触发 dispatch 失败。 */
    static final long REBUILD_FAIL = 94804L;

    /** Guard FREQUENCY：同 plan 两步 SMS，第二步触发 DAILY_LIMIT（需 compliance.daily-limit.SMS=1）。 */
    static final long GUARD_FREQUENCY = 94805L;

    static boolean isGuardFrequency(Long caseId) {
        return caseId != null && caseId == GUARD_FREQUENCY;
    }

    private L4aCaseRegistry() {}

    static boolean isL4aCase(Long caseId) {
        if (caseId == null) {
            return false;
        }
<<<<<<< HEAD
        return caseId == THREE_CHANNEL
                || caseId == GUARD_NO_PHONE
                || caseId == REBUILD_FAIL
=======
        return caseId == THREE_CHANNEL || caseId == GUARD_NO_PHONE || caseId == REBUILD_FAIL
>>>>>>> origin/ca_branch
                || caseId == GUARD_FREQUENCY;
    }

    static boolean isThreeChannel(Long caseId) {
        return caseId != null && caseId == THREE_CHANNEL;
    }

    static boolean isGuardNoPhone(Long caseId) {
        return caseId != null && caseId == GUARD_NO_PHONE;
    }

    static boolean isRebuildFail(Long caseId) {
        return caseId != null && caseId == REBUILD_FAIL;
    }
<<<<<<< HEAD
=======

>>>>>>> origin/ca_branch
}
