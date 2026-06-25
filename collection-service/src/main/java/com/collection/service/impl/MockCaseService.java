package com.collection.service.impl;

import com.collection.common.enums.Stage;
import com.collection.common.model.*;
import com.collection.common.service.CaseService;
import com.collection.common.service.ProfileService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 Mock CaseService —— 合成案件数据，使全链路在不依赖旧库 schema 的情况下可跑通。
 *
 * <p>约定 caseId 见功能测试指南 §1.3、{@code docs/email-templates/email-e2e-test-cases.md}。
 */
@Service
public class MockCaseService implements CaseService {

    private final Set<Long> repaidCases = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Long> ceasedCases = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Resource
    private ProfileService profileService;

    public void markRepaid(Long caseId) {
        repaidCases.add(caseId);
    }

    public void markCeased(Long caseId) {
        ceasedCases.add(caseId);
    }

    @Override
    public CaseInfo getCaseInfo(Long caseId) {
        MockCaseProfile profile = resolveProfile(caseId);
        CaseInfo info = new CaseInfo();
        info.setCaseId(caseId);
        info.setUserId(caseId);
        info.setDpd(profile.dpd);
        info.setStage(profile.stage);
        info.setProduct("MOCK_PRODUCT");
        info.setCaseStatus(profile.caseStatus);
        info.setTotalOutstanding(profile.totalOutstanding);
        info.setDueDate(profile.dueDate);
        info.setRepaid(repaidCases.contains(caseId));
        info.setFrozen(profile.frozen || ceasedCases.contains(caseId));
        return info;
    }

    @Override
    public CaseContext buildContext(Long caseId) {
        MockCaseProfile profile = resolveProfile(caseId);
        boolean ceased = ceasedCases.contains(caseId) || caseId == 90091L;

        CaseContext ctx = new CaseContext();
        ctx.setCaseId(caseId);
        ctx.setUserId(caseId);
        ctx.setDpd(ceased ? 91 : profile.dpd);
        ctx.setStage(profile.stage);
        ctx.setProduct("MOCK_PRODUCT");
        ctx.setLoanAmount(profile.totalOutstanding);
        ctx.setOverdueAmount(profile.totalOutstanding.multiply(new BigDecimal("0.96")));
        ctx.setPenaltyAmount(profile.totalOutstanding.multiply(new BigDecimal("0.04")));
        ctx.setTotalOutstanding(profile.totalOutstanding);
        ctx.setDueDate(profile.dueDate);
        ctx.setCaseStatus(ceased ? "CEASED" : profile.caseStatus);
        ctx.setCollectionStatus(ceased ? "CEASED" : "ACTIVE");
        ctx.setStrategyTone(profile.strategyTone);
        ctx.setComplaintFrozen(profile.frozen);
        ctx.setFirstLoan(true);
        ctx.setPayCount(0);
        ctx.setRepaymentUrl("https://app.mocasa.test/repay/" + caseId);
        ctx.setEmailScriptSlot(profile.emailScriptSlot);
        return ctx;
    }

    @Override
    public ContactHistory buildContactHistory(Long userId, Long caseId) {
        ContactHistory h = new ContactHistory();
        h.setTotalTouchCount(0);
        h.setTodayTouchCount(0);
        h.setTodayPhoneAnswered(false);
        h.setCurrentPlanAiBotFailCount(0);
        h.setStageEntryDate(LocalDate.now());
        return h;
    }

    @Override
    public ContextSnapshot getContextSnapshot(Long caseId) {
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(buildContext(caseId));
        snapshot.setContactHistory(buildContactHistory(caseId, caseId));
        snapshot.setUserProfile(profileService.getFullProfile(caseId));
        snapshot.setSnapshotTime(LocalDateTime.now());
        snapshot.setSnapshotVersion("mock-v1");
        return snapshot;
    }

    @Override
    public boolean isRepaid(Long caseId) {
        return repaidCases.contains(caseId);
    }

    private MockCaseProfile resolveProfile(Long caseId) {
        if (ceasedCases.contains(caseId) || caseId == 90091L) {
            return new MockCaseProfile(91, Stage.S4, "CEASED", false, "STANDARD",
                    new BigDecimal("5000.00"), LocalDate.now().minusDays(91), null);
        }
        if (caseId == 90100L) {
            return new MockCaseProfile(-3, Stage.S0, "ACTIVE", false, "STANDARD",
                    new BigDecimal("5000.00"), LocalDate.now().plusDays(3), null);
        }
        Optional<MockCaseProfile> smsProfile = SmsCaseRegistry.find(caseId)
                .map(tc -> new MockCaseProfile(tc.dpd, tc.stage, "OVERDUE", false, "STANDARD",
                        tc.totalOutstanding, tc.dueDate, null));
        if (smsProfile.isPresent()) {
            return smsProfile.get();
        }
        Optional<MockCaseProfile> pushProfile = PushCaseRegistry.find(caseId)
                .map(tc -> new MockCaseProfile(tc.dpd, tc.stage, "OVERDUE", false, "STANDARD",
                        tc.totalOutstanding, tc.dueDate, null));
        if (pushProfile.isPresent()) {
            return pushProfile.get();
        }
        return EmailCaseRegistry.find(caseId)
                .map(tc -> new MockCaseProfile(tc.dpd, tc.stage, "OVERDUE", false, "STANDARD",
                        tc.totalOutstanding, tc.dueDate, tc.emailScriptSlot))
                .orElseGet(() -> {
                    if (caseId == 90007L) {
                        return new MockCaseProfile(10, Stage.S2, "OVERDUE", false, "FIRM",
                                new BigDecimal("5000.00"), LocalDate.now().minusDays(10), null);
                    }
                    if (caseId == 90008L) {
                        return new MockCaseProfile(5, Stage.S2, "OVERDUE", true, "STANDARD",
                                new BigDecimal("5000.00"), LocalDate.now().minusDays(5), null);
                    }
                    return new MockCaseProfile(1, Stage.S1, "OVERDUE", false, "STANDARD",
                            new BigDecimal("5000.00"), LocalDate.now().minusDays(1), null);
                });
    }

    private static final class MockCaseProfile {
        final int dpd;
        final Stage stage;
        final String caseStatus;
        final boolean frozen;
        final String strategyTone;
        final BigDecimal totalOutstanding;
        final LocalDate dueDate;
        final String emailScriptSlot;

        MockCaseProfile(int dpd, Stage stage, String caseStatus, boolean frozen, String strategyTone,
                        BigDecimal totalOutstanding, LocalDate dueDate, String emailScriptSlot) {
            this.dpd = dpd;
            this.stage = stage;
            this.caseStatus = caseStatus;
            this.frozen = frozen;
            this.strategyTone = strategyTone;
            this.totalOutstanding = totalOutstanding;
            this.dueDate = dueDate;
            this.emailScriptSlot = emailScriptSlot;
        }
    }
}
