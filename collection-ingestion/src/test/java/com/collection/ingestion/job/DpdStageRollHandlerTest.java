package com.collection.ingestion.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.enums.CancelReason;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.service.CaseService;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DpdStageRollHandlerTest {

    private static final long LOAN = 506565L;

    @Mock private CaseService caseService;
    @Mock private ContactPlanRepository planRepository;
    @Mock private IngestionService ingestionService;

    private DpdStageRollHandler handler;

    @BeforeEach
    void setUp() {
        IngestionProperties props = new IngestionProperties();
        props.setLoanIdWhitelist(Collections.singletonList(LOAN));
        handler = new DpdStageRollHandler();
        ReflectionTestUtils.setField(handler, "props", props);
        ReflectionTestUtils.setField(handler, "caseService", caseService);
        ReflectionTestUtils.setField(handler, "planRepository", planRepository);
        ReflectionTestUtils.setField(handler, "ingestionService", ingestionService);
        when(ingestionService.currentSnapshotFields(any())).thenReturn(Collections.emptyMap());
    }

    @Test
    @DisplayName("有活跃计划且档变 → STAGE_CHANGED")
    void activePlan_stageDiffers_publishesStageChanged() {
        stubCase(4, Stage.S2);
        when(planRepository.findActivePlansByCase(LOAN))
                .thenReturn(
                        Collections.singletonList(plan(1L, Stage.S1, PlanStatus.STEP_SCHEDULED)));

        handler.dailyRoll();

        verifyStageChanged(Stage.S2);
        verify(planRepository, never()).findRecentPlansByCase(any(), anyInt());
    }

    @Test
    @DisplayName("有活跃计划且同档 → 不发")
    void activePlan_sameStage_noop() {
        stubCase(5, Stage.S2);
        when(planRepository.findActivePlansByCase(LOAN))
                .thenReturn(
                        Collections.singletonList(plan(1L, Stage.S2, PlanStatus.STEP_SCHEDULED)));

        handler.dailyRoll();

        verifyNoStageOrCease();
    }

    @Test
    @DisplayName("有活跃计划回退 S4→S3 → 单调前进不发")
    void activePlan_rollback_skipped() {
        stubCase(20, Stage.S3);
        when(planRepository.findActivePlansByCase(LOAN))
                .thenReturn(
                        Collections.singletonList(plan(1L, Stage.S4, PlanStatus.STEP_SCHEDULED)));

        handler.dailyRoll();

        verifyNoStageOrCease();
    }

    @Test
    @DisplayName("S0 末日 COMPLETED → 投影 S1 → STAGE_CHANGED")
    void completedS0_toS1() {
        stubCompletedResume(1, Stage.S0, Stage.S1);
        handler.dailyRoll();
        verifyStageChanged(Stage.S1);
    }

    @Test
    @DisplayName("S1 末日 COMPLETED → 投影 S2 → STAGE_CHANGED")
    void completedS1_toS2() {
        stubCompletedResume(4, Stage.S1, Stage.S2);
        handler.dailyRoll();
        verifyStageChanged(Stage.S2);
    }

    @Test
    @DisplayName("S2 末日 COMPLETED → 投影 S3 → STAGE_CHANGED")
    void completedS2_toS3() {
        stubCompletedResume(16, Stage.S2, Stage.S3);
        handler.dailyRoll();
        verifyStageChanged(Stage.S3);
    }

    @Test
    @DisplayName("S3 末日 COMPLETED → 投影 S4 → STAGE_CHANGED")
    void completedS3_toS4() {
        stubCompletedResume(31, Stage.S3, Stage.S4);
        handler.dailyRoll();
        verifyStageChanged(Stage.S4);
    }

    @Test
    @DisplayName("COMPLETED 后 DPD 跳档 S1→S3 → 按投影档建")
    void completedS1_jumpToS3() {
        stubCompletedResume(16, Stage.S1, Stage.S3);
        handler.dailyRoll();
        verifyStageChanged(Stage.S3);
    }

    @Test
    @DisplayName("COMPLETED 同档 → 不建")
    void completedSameStage_noop() {
        stubCompletedResume(2, Stage.S1, Stage.S1);
        handler.dailyRoll();
        verifyNoStageOrCease();
    }

    @Test
    @DisplayName("最近一份 MANUAL_CLEANUP → 不建（不救活停催圈）")
    void cancelledManualCleanup_noop() {
        stubCase(32, Stage.S4);
        when(planRepository.findActivePlansByCase(LOAN)).thenReturn(Collections.emptyList());
        ContactPlan cancelled = plan(9L, Stage.S3, PlanStatus.PLAN_CANCELLED);
        cancelled.setCancelReason(CancelReason.MANUAL_CLEANUP);
        when(planRepository.findRecentPlansByCase(LOAN, 1))
                .thenReturn(Collections.singletonList(cancelled));

        handler.dailyRoll();

        verifyNoStageOrCease();
    }

    @Test
    @DisplayName("最近一份 REPAID 取消 → 不建（即便投影未标结清）")
    void cancelledRepaid_noop() {
        stubCase(4, Stage.S2);
        when(planRepository.findActivePlansByCase(LOAN)).thenReturn(Collections.emptyList());
        ContactPlan cancelled = plan(9L, Stage.S1, PlanStatus.PLAN_CANCELLED);
        cancelled.setCancelReason(CancelReason.REPAID);
        when(planRepository.findRecentPlansByCase(LOAN, 1))
                .thenReturn(Collections.singletonList(cancelled));

        handler.dailyRoll();

        verifyNoStageOrCease();
    }

    @Test
    @DisplayName("NO_DUE_BALANCE 取消后投影已有余额 → 按当天档重建")
    void cancelledNoDueBalance_resumesWhenOutstandingReturns() {
        CaseInfo info = stubCase(8, Stage.S2);
        info.setTotalOutstanding(new BigDecimal("2500"));
        when(planRepository.findActivePlansByCase(LOAN)).thenReturn(Collections.emptyList());
        ContactPlan cancelled = plan(9L, Stage.S2, PlanStatus.PLAN_CANCELLED);
        cancelled.setCancelReason(CancelReason.NO_DUE_BALANCE);
        when(planRepository.findRecentPlansByCase(LOAN, 1))
                .thenReturn(Collections.singletonList(cancelled));

        handler.dailyRoll();

        verifyStageChanged(Stage.S2);
    }

    @Test
    @DisplayName("NO_DUE_BALANCE 取消后仍无余额 → 不建，避免取消/重建打转")
    void cancelledNoDueBalance_stillZero_noop() {
        CaseInfo info = stubCase(8, Stage.S2);
        info.setTotalOutstanding(BigDecimal.ZERO);
        when(planRepository.findActivePlansByCase(LOAN)).thenReturn(Collections.emptyList());
        ContactPlan cancelled = plan(9L, Stage.S2, PlanStatus.PLAN_CANCELLED);
        cancelled.setCancelReason(CancelReason.NO_DUE_BALANCE);
        when(planRepository.findRecentPlansByCase(LOAN, 1))
                .thenReturn(Collections.singletonList(cancelled));

        handler.dailyRoll();

        verifyNoStageOrCease();
    }

    @Test
    @DisplayName("NO_DUE_BALANCE 取消后 S0 仅有 upcoming → 按当天档重建")
    void cancelledNoDueBalance_s0UpcomingResumes() {
        CaseInfo info = stubCase(-2, Stage.S0);
        info.setTotalOutstanding(BigDecimal.ZERO);
        info.setUpcomingAmount(new BigDecimal("1800"));
        when(planRepository.findActivePlansByCase(LOAN)).thenReturn(Collections.emptyList());
        ContactPlan cancelled = plan(9L, Stage.S0, PlanStatus.PLAN_CANCELLED);
        cancelled.setCancelReason(CancelReason.NO_DUE_BALANCE);
        when(planRepository.findRecentPlansByCase(LOAN, 1))
                .thenReturn(Collections.singletonList(cancelled));

        handler.dailyRoll();

        verifyStageChanged(Stage.S0);
    }

    @Test
    @DisplayName("从无计划（从未建档）→ 不由日切首建，等 CASE_INGESTED")
    void neverHadPlan_noop() {
        stubCase(4, Stage.S2);
        when(planRepository.findActivePlansByCase(LOAN)).thenReturn(Collections.emptyList());
        when(planRepository.findRecentPlansByCase(LOAN, 1)).thenReturn(Collections.emptyList());

        handler.dailyRoll();

        verifyNoStageOrCease();
    }

    @Test
    @DisplayName("dpd≥91 有活跃计划 → CASE_CEASED")
    void dpd91_active_ceases() {
        stubCase(95, Stage.S4);
        when(planRepository.findActivePlansByCase(LOAN))
                .thenReturn(
                        Collections.singletonList(plan(1L, Stage.S4, PlanStatus.STEP_SCHEDULED)));

        handler.dailyRoll();

        verify(ingestionService).caseCeased(LOAN, 95);
        verify(ingestionService, never()).changeStage(any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("dpd≥91 无活跃计划（S4 已 COMPLETED）→ 不发 CEASED")
    void dpd91_completedNoActive_noop() {
        stubCase(91, Stage.S4);
        when(planRepository.findActivePlansByCase(LOAN)).thenReturn(Collections.emptyList());

        handler.dailyRoll();

        verifyNoStageOrCease();
        verify(planRepository, never()).findRecentPlansByCase(any(), anyInt());
    }

    @Test
    @DisplayName("已结清 → 跳过")
    void repaid_skip() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(LOAN);
        info.setRepaid(true);
        info.setDpd(4);
        info.setStage(Stage.S2);
        when(caseService.getCaseInfo(LOAN)).thenReturn(info);

        handler.dailyRoll();

        verify(planRepository, never()).findActivePlansByCase(any());
        verifyNoStageOrCease();
    }

    private void stubCompletedResume(int dpd, Stage lastStage, Stage projectionStage) {
        stubCase(dpd, projectionStage);
        when(planRepository.findActivePlansByCase(LOAN)).thenReturn(Collections.emptyList());
        when(planRepository.findRecentPlansByCase(LOAN, 1))
                .thenReturn(
                        Collections.singletonList(plan(8L, lastStage, PlanStatus.PLAN_COMPLETED)));
    }

    private CaseInfo stubCase(int dpd, Stage stage) {
        CaseInfo info = new CaseInfo();
        info.setCaseId(LOAN);
        info.setUserId(LOAN);
        info.setDpd(dpd);
        info.setStage(stage);
        info.setRepaid(false);
        info.setTotalOutstanding(new BigDecimal("1000"));
        when(caseService.getCaseInfo(LOAN)).thenReturn(info);
        return info;
    }

    private static ContactPlan plan(long id, Stage stage, PlanStatus status) {
        ContactPlan plan = new ContactPlan();
        plan.setId(id);
        plan.setCaseId(LOAN);
        plan.setStage(stage);
        plan.setStatus(status);
        return plan;
    }

    private void verifyStageChanged(Stage expected) {
        verify(ingestionService).changeStage(eq(LOAN), eq(LOAN), eq(expected), anyMap());
        verify(ingestionService, never()).caseCeased(any(), any());
    }

    private void verifyNoStageOrCease() {
        verify(ingestionService, never()).changeStage(any(), any(), any(), anyMap());
        verify(ingestionService, never()).caseCeased(any(), any());
    }
}
