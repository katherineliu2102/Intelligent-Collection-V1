package com.collection.service.impl;

import com.collection.common.enums.Stage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 1 App Push 联调 case 注册表。
 *
 * <p>caseId = userId。Phase 1 暂无真实极光 Registration ID，使用 <b>假 jpushToken 占位</b>
 * （{@link #PLACEHOLDER_JPUSH_TOKEN}）先跑通「编排 → DefaultStepResolver → NotificationPushAdapter → 通知中心」链路。
 * 拿到真实 token 后，仅需在 ingestion / profile 写入真实值即可，无需改适配器。
 *
 * <p>联调见功能测试指南 TC-PUSH-*。
 */
final class MockPushTestCases {

    /**
     * 假 jpushToken 占位：明显非真实值，仅用于让 PushAdapter 不走 SMS fallback、跑通 push 调用链路。
     * 通知中心侧用此 token 调极光会因 token 无效而推送失败（联调可见），但不影响链路验证。
     */
    static final String PLACEHOLDER_JPUSH_TOKEN = "MOCK_JPUSH_RID_PLACEHOLDER_0001";

    /** 94200：有假 jpushToken → 走 push（不 fallback）。 */
    static final long PUSH_WITH_TOKEN = 94200L;
    /** 94201：无 jpushToken → 验证 push → SMS fallback。 */
    static final long PUSH_NO_TOKEN = 94201L;

    private static final Map<Long, PushTestCase> BY_CASE_ID;

    static {
        Map<Long, PushTestCase> m = new LinkedHashMap<>();
        m.put(PUSH_WITH_TOKEN, c(PUSH_WITH_TOKEN, "push_with_token", PLACEHOLDER_JPUSH_TOKEN,
                "9451374358", 1, Stage.S1, "5000.00"));
        m.put(PUSH_NO_TOKEN, c(PUSH_NO_TOKEN, "push_no_token", null,
                "9451373897", 1, Stage.S1, "5000.00"));
        BY_CASE_ID = Collections.unmodifiableMap(m);
    }

    private static PushTestCase c(Long caseId, String alias, String jpushToken, String phone,
                                  int dpd, Stage stage, String amount) {
        return new PushTestCase(caseId, alias, jpushToken, phone, dpd, stage, new BigDecimal(amount),
                LocalDate.now().minusDays(Math.max(dpd, 0)));
    }

    static Optional<PushTestCase> find(Long caseId) {
        return Optional.ofNullable(BY_CASE_ID.get(caseId));
    }

    static boolean isPushTestCase(Long caseId) {
        return caseId != null && BY_CASE_ID.containsKey(caseId);
    }

    static final class PushTestCase {
        final Long caseId;
        final String alias;
        /** 假 jpushToken 占位；null 表示无 token（验证 fallback）。 */
        final String jpushToken;
        final String primaryPhone;
        final int dpd;
        final Stage stage;
        final BigDecimal totalOutstanding;
        final LocalDate dueDate;

        PushTestCase(Long caseId, String alias, String jpushToken, String primaryPhone, int dpd,
                     Stage stage, BigDecimal totalOutstanding, LocalDate dueDate) {
            this.caseId = caseId;
            this.alias = alias;
            this.jpushToken = jpushToken;
            this.primaryPhone = primaryPhone;
            this.dpd = dpd;
            this.stage = stage;
            this.totalOutstanding = totalOutstanding;
            this.dueDate = dueDate;
        }

        String displayName() {
            return alias.replace('_', ' ');
        }
    }

    private MockPushTestCases() {
    }
}
