package com.collection.service.impl;

import com.collection.common.enums.Stage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 1 Email 全链路联调 case 注册表（方式 C）。
 *
 * <p>caseId = userId；邮箱统一 {@code wzynju@126.com}（见 {@link MockProfileService}）。
 * Phase 1 实际发信仅 5 个 scriptSlot，联调见 {@code docs/email-templates/email-e2e-test-cases.md}。
 */
final class MockEmailTestCases {

    static final String TEST_EMAIL = "wzynju@126.com";

    private static final Map<Long, EmailTestCase> BY_CASE_ID;

    static {
        Map<Long, EmailTestCase> m = new LinkedHashMap<>();
        m.put(92001L, c(92001L, "test_s0_user1", 0, Stage.S0, "5000.00", null, "S0_DUE_TODAY_EMAIL"));
        m.put(92002L, c(92002L, "test_s0_user2", 0, Stage.S0, "5000.00", null, "S0_DUE_TODAY_EMAIL"));
        m.put(93101L, c(93101L, "test_s1_user1", 2, Stage.S1, "2500.00", LocalDate.of(2026, 6, 6), "S1_EMAIL_OVERDUE_NOTICE"));
        m.put(93102L, c(93102L, "test_s1_user2", 3, Stage.S1, "2500.00", LocalDate.of(2026, 6, 5), "S1_EMAIL_STAGE_WARNING"));
        m.put(93201L, c(93201L, "test_s2_user1", 4, Stage.S2, "4000.00", LocalDate.of(2026, 6, 1), "S2_EMAIL_ENTRY"));
        m.put(93202L, c(93202L, "test_s2_user2", 7, Stage.S2, "4000.00", LocalDate.of(2026, 6, 1), "S2_EMAIL_MID"));
        m.put(93203L, c(93203L, "test_s2_user3", 12, Stage.S2, "4000.00", LocalDate.of(2026, 5, 27), "S2_EMAIL_PRE_S3"));
        m.put(93301L, c(93301L, "test_s3_user1", 16, Stage.S3, "5000.00", LocalDate.of(2026, 5, 23), "S3_EMAIL_ENTRY"));
        m.put(93302L, c(93302L, "test_s3_user2", 23, Stage.S3, "5000.00", LocalDate.of(2026, 5, 16), "S3_EMAIL_MID"));
        m.put(93303L, c(93303L, "test_s3_user3", 30, Stage.S3, "5000.00", LocalDate.of(2026, 5, 9), "S3_EMAIL_PRE_S4"));
        m.put(93401L, c(93401L, "test_s4_user1", 31, Stage.S4, "5000.00", LocalDate.of(2026, 5, 8), "S4_EMAIL_ENTRY"));
        m.put(93402L, c(93402L, "test_s4_user2", 45, Stage.S4, "5000.00", LocalDate.of(2026, 4, 24), "S4_EMAIL_FINAL_REMINDER"));
        m.put(93403L, c(93403L, "test_s4_user3", 60, Stage.S4, "5000.00", LocalDate.of(2026, 4, 9), "S4_EMAIL_MID"));
        m.put(93404L, c(93404L, "test_s4_user4", 75, Stage.S4, "5000.00", LocalDate.of(2026, 3, 25), "S4_EMAIL_PRE_CLOSE"));
        BY_CASE_ID = Collections.unmodifiableMap(m);
    }

    private static EmailTestCase c(Long caseId, String alias, int dpd, Stage stage, String amount,
                                   LocalDate dueDate, String scriptSlot) {
        return new EmailTestCase(caseId, alias, dpd, stage, new BigDecimal(amount),
                dueDate != null ? dueDate : LocalDate.now().minusDays(Math.max(dpd, 0)),
                scriptSlot);
    }

    static Optional<EmailTestCase> find(Long caseId) {
        return Optional.ofNullable(BY_CASE_ID.get(caseId));
    }

    static boolean isEmailTestCase(Long caseId) {
        return caseId != null && BY_CASE_ID.containsKey(caseId);
    }

    static final class EmailTestCase {
        final Long caseId;
        final String alias;
        final int dpd;
        final Stage stage;
        final BigDecimal totalOutstanding;
        final LocalDate dueDate;
        final String emailScriptSlot;

        EmailTestCase(Long caseId, String alias, int dpd, Stage stage, BigDecimal totalOutstanding,
                      LocalDate dueDate, String emailScriptSlot) {
            this.caseId = caseId;
            this.alias = alias;
            this.dpd = dpd;
            this.stage = stage;
            this.totalOutstanding = totalOutstanding;
            this.dueDate = dueDate;
            this.emailScriptSlot = emailScriptSlot;
        }

        String displayName() {
            return alias.replace('_', ' ');
        }
    }

    private MockEmailTestCases() {
    }
}
