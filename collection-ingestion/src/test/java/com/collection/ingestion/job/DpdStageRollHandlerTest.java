package com.collection.ingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.service.CaseService;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 日切阶段规则单测（数据接入规格 §4）。
 *
 * <p>走白名单路径以避开 Redis 全量扫描；{@code dailyRollDeduplicator} 保持 null，此时去重恒放行， 便于单独断言阶段判据本身。
 */
class DpdStageRollHandlerTest {

    private static final long LOAN_ID = 525441L;
    private static final long USER_ID = 2145521L;

    private DpdStageRollHandler handler;
    private CaseService caseService;
    private ContactPlanRepository planRepository;
    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        handler = new DpdStageRollHandler();
        caseService = mock(CaseService.class);
        planRepository = mock(ContactPlanRepository.class);
        ingestionService = mock(IngestionService.class);

        IngestionProperties props = new IngestionProperties();
        props.setLoanIdWhitelist(Collections.singletonList(LOAN_ID));

        ReflectionTestUtils.setField(handler, "props", props);
        ReflectionTestUtils.setField(handler, "caseService", caseService);
        ReflectionTestUtils.setField(handler, "planRepository", planRepository);
        ReflectionTestUtils.setField(handler, "ingestionService", ingestionService);
    }

    /** 让投影返回指定 dpd 与其推导阶段。 */
    private void givenProjection(int dpd) {
        CaseInfo info = new CaseInfo();
        info.setCaseId(LOAN_ID);
        info.setUserId(USER_ID);
        info.setDpd(dpd);
        info.setStage(Stage.fromDpd(dpd));
        when(caseService.getCaseInfo(LOAN_ID)).thenReturn(info);
    }

    /** 让案件存在一个处于指定阶段的活跃计划。 */
    private void givenActivePlanAtStage(Stage stage) {
        ContactPlan plan = new ContactPlan();
        plan.setId(1L);
        plan.setCaseId(LOAN_ID);
        plan.setStage(stage);
        when(planRepository.findActivePlansByCase(LOAN_ID))
                .thenReturn(Collections.singletonList(plan));
    }

    @Test
    @DisplayName("投影阶段更严重 → 发布 STAGE_CHANGED（升档前进）")
    void publishesStageChangedWhenProjectionIsMoreSevere() {
        givenProjection(20); // S3
        givenActivePlanAtStage(Stage.S1);

        handler.dailyRoll();

        verify(ingestionService).changeStage(eq(LOAN_ID), eq(USER_ID), eq(Stage.S3), any());
    }

    @Test
    @DisplayName("投影阶段更轻 → 不发布回退事件，避免与引擎 ESCALATE 形成降档 ping-pong")
    void doesNotPublishStageRollbackWhenProjectionIsLessSevere() {
        // 引擎 ESCALATE 已把计划抬到 S3，而引擎从不回写投影，故投影仍停在 DPD 推导的 S1。
        // 若日切按「不同即发」处理，S3 计划会被 STAGE_UPGRADE 取消并重建回 S1。
        givenProjection(2); // S1
        givenActivePlanAtStage(Stage.S3);

        handler.dailyRoll();

        verify(ingestionService, never())
                .changeStage(anyLong(), anyLong(), any(Stage.class), any());
    }

    @Test
    @DisplayName("阶段一致 → 不发布任何事件")
    void publishesNothingWhenStageUnchanged() {
        givenProjection(5); // S2
        givenActivePlanAtStage(Stage.S2);

        handler.dailyRoll();

        verify(ingestionService, never())
                .changeStage(anyLong(), anyLong(), any(Stage.class), any());
        verify(ingestionService, never()).caseCeased(anyLong(), any());
    }

    @Test
    @DisplayName("无活跃计划 → 不发布阶段事件")
    void publishesNothingWithoutActivePlan() {
        givenProjection(20);
        when(planRepository.findActivePlansByCase(LOAN_ID)).thenReturn(Collections.emptyList());

        handler.dailyRoll();

        verify(ingestionService, never())
                .changeStage(anyLong(), anyLong(), any(Stage.class), any());
    }

    @Test
    @DisplayName("dpd ≥ 91 且有活跃计划 → 发布 CASE_CEASED，不发阶段变更")
    void publishesCaseCeasedBeyondD91() {
        givenProjection(95);
        givenActivePlanAtStage(Stage.S4);

        handler.dailyRoll();

        verify(ingestionService).caseCeased(LOAN_ID, 95);
        verify(ingestionService, never())
                .changeStage(anyLong(), anyLong(), any(Stage.class), any());
    }

    @Test
    @DisplayName("已结清 → 整案跳过")
    void skipsRepaidCase() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(LOAN_ID);
        info.setUserId(USER_ID);
        info.setDpd(20);
        info.setStage(Stage.S3);
        info.setRepaid(true);
        when(caseService.getCaseInfo(LOAN_ID)).thenReturn(info);

        handler.dailyRoll();

        verify(planRepository, never()).findActivePlansByCase(anyLong());
        verify(ingestionService, never())
                .changeStage(anyLong(), anyLong(), any(Stage.class), any());
        verify(ingestionService, never()).caseCeased(anyLong(), any());
    }

    @Test
    @DisplayName("案件不存在 → 跳过且不抛异常")
    void skipsMissingCase() {
        when(caseService.getCaseInfo(LOAN_ID)).thenReturn(null);

        handler.dailyRoll();

        verify(ingestionService, never())
                .changeStage(anyLong(), anyLong(), any(Stage.class), any());
    }

    @Test
    @DisplayName("白名单为空且未开全量扫描 → 跳过，不触碰投影")
    void skipsFullScanWhenDisabled() {
        IngestionProperties props = new IngestionProperties();
        props.setLoanIdWhitelist(Collections.emptyList());
        ReflectionTestUtils.setField(handler, "props", props);

        int processed = handler.dailyRoll();

        assertThat(processed).isZero();
        verify(caseService, never()).getCaseInfo(anyLong());
    }
}
