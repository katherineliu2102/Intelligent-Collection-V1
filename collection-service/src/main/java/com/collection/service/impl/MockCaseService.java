package com.collection.service.impl;

import com.collection.common.enums.Stage;
import com.collection.common.model.*;
import com.collection.common.service.CaseService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 Mock CaseService —— 合成案件数据，使全链路在不依赖旧库 schema 的情况下可跑通。
 *
 * <p>⚠ 替换指引：服务层开发者用真实实现替换本类（映射 t_collection / t_user_repayment_plan 等），
 * 删除本类或加 {@code @Primary} 到真实实现即可。
 *
 * <p>支持通过 {@link #markRepaid(Long)} 在运行时把某 caseId 标为已还款，用于验证
 * REPAYMENT/PreFlightChecker 中断链路。
 */
@Service
public class MockCaseService implements CaseService {

    /** 运行时已还款案件集合（供链路测试用）。 */
    private final Set<Long> repaidCases = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void markRepaid(Long caseId) {
        repaidCases.add(caseId);
    }

    @Override
    public CaseInfo getCaseInfo(Long caseId) {
        CaseInfo info = new CaseInfo();
        info.setCaseId(caseId);
        info.setUserId(caseId);
        info.setDpd(1);
        info.setStage(Stage.S1);
        info.setProduct("MOCK_PRODUCT");
        info.setCaseStatus("OVERDUE");
        info.setTotalOutstanding(new BigDecimal("5000.00"));
        info.setDueDate(LocalDate.now().minusDays(1));
        info.setRepaid(repaidCases.contains(caseId));
        info.setFrozen(false);
        return info;
    }

    @Override
    public CaseContext buildContext(Long caseId) {
        CaseContext ctx = new CaseContext();
        ctx.setCaseId(caseId);
        ctx.setUserId(caseId);
        ctx.setDpd(1);
        ctx.setStage(Stage.S1);
        ctx.setProduct("MOCK_PRODUCT");
        ctx.setLoanAmount(new BigDecimal("5000.00"));
        ctx.setOverdueAmount(new BigDecimal("4800.00"));
        ctx.setPenaltyAmount(new BigDecimal("200.00"));
        ctx.setTotalOutstanding(new BigDecimal("5000.00"));
        ctx.setDueDate(LocalDate.now().minusDays(1));
        ctx.setCaseStatus("OVERDUE");
        ctx.setFirstLoan(true);
        ctx.setPayCount(0);
        return ctx;
    }

    @Override
    public ContactHistory buildContactHistory(Long userId, Long caseId) {
        ContactHistory h = new ContactHistory();
        h.setTotalTouchCount(0);
        h.setTodayTouchCount(0);
        h.setTodayPhoneAnswered(false);
        h.setCurrentPlanAiBotFailCount(0);
        h.setPtpCount(0);
        h.setPtpFulfilledCount(0);
        h.setStageEntryDate(LocalDate.now());
        return h;
    }

    @Override
    public ContextSnapshot getContextSnapshot(Long caseId) {
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(buildContext(caseId));
        snapshot.setContactHistory(buildContactHistory(caseId, caseId));
        UserProfile profile = new UserProfile();
        profile.setUserId(caseId);
        snapshot.setUserProfile(profile);
        snapshot.setSnapshotTime(LocalDateTime.now());
        snapshot.setSnapshotVersion("mock-v1");
        return snapshot;
    }

    @Override
    public boolean isRepaid(Long caseId) {
        return repaidCases.contains(caseId);
    }
}
