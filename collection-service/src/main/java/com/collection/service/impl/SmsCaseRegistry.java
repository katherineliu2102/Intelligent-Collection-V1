package com.collection.service.impl;

import com.collection.common.enums.Stage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 1 SMS 联调 case 注册表。
 *
 * <p>caseId = userId；手机号见 {@link #primaryPhone}。
 * 联调见功能测试指南 TC-SMS-TEST-* / TC-SMS-PROD-*。
 */
final class SmsCaseRegistry {

    /** 通知中心 testSend 默认测试号（Virtual 通道，不真实下发）。 */
    static final String TEST_MOBILE_VIRTUAL = "123456";

    /** 生产 /send 真号 A（E.164）。 */
    static final String PROD_MOBILE_A = "639451374358";

    /** 生产 /send 真号 B（9451373897，通知中心补 63）。 */
    static final String PROD_MOBILE_B = "9451373897";

    /** 生产 /send 真号 C（E.164）。 */
    static final String PROD_MOBILE_C = "639153239069";

    private static final Map<Long, SmsTestCase> BY_CASE_ID;

    static {
        Map<Long, SmsTestCase> m = new LinkedHashMap<>();
        m.put(94100L, c(94100L, "sms_test_virtual", TEST_MOBILE_VIRTUAL, 1, Stage.S1, "5000.00"));
        m.put(94101L, c(94101L, "sms_prod_phone_a", PROD_MOBILE_A, 1, Stage.S1, "5000.00"));
        m.put(94102L, c(94102L, "sms_prod_phone_b", PROD_MOBILE_B, 1, Stage.S1, "5000.00"));
        m.put(94103L, c(94103L, "sms_prod_phone_c", PROD_MOBILE_C, 1, Stage.S1, "5000.00"));
        BY_CASE_ID = Collections.unmodifiableMap(m);
    }

    private static SmsTestCase c(Long caseId, String alias, String phone, int dpd, Stage stage, String amount) {
        return new SmsTestCase(caseId, alias, phone, dpd, stage, new BigDecimal(amount),
                LocalDate.now().minusDays(Math.max(dpd, 0)));
    }

    static Optional<SmsTestCase> find(Long caseId) {
        return Optional.ofNullable(BY_CASE_ID.get(caseId));
    }

    static boolean isSmsTestCase(Long caseId) {
        return caseId != null && BY_CASE_ID.containsKey(caseId);
    }

    static final class SmsTestCase {
        final Long caseId;
        final String alias;
        final String primaryPhone;
        final int dpd;
        final Stage stage;
        final BigDecimal totalOutstanding;
        final LocalDate dueDate;

        SmsTestCase(Long caseId, String alias, String primaryPhone, int dpd, Stage stage,
                    BigDecimal totalOutstanding, LocalDate dueDate) {
            this.caseId = caseId;
            this.alias = alias;
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

    private SmsCaseRegistry() {
    }
}
