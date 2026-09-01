package com.collection.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.service.mapper.AiCollectionCaseMapper;
import com.collection.service.mapper.AiCollectionCaseRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 投影 stage 列 → {@link Stage} 的解析。
 *
 * <p>覆盖 2026-08-24 数仓 dpd 口径：无尾款 {@code dpd=0/stage=null}；有尾款取剩余未结清项的 max dpd， {@code dpd < -3} 时
 * {@code stage=null}。
 */
@ExtendWith(MockitoExtension.class)
class AiCollectionCaseServiceStageTest {

    private static final long CASE_ID = 502880L;

    @Mock private AiCollectionCaseMapper mapper;

    @InjectMocks private AiCollectionCaseService service;

    private CaseInfo caseInfoWith(Integer dpd, String stage, String collectionStatus) {
        AiCollectionCaseRow row = new AiCollectionCaseRow();
        row.setCaseId(CASE_ID);
        row.setUserId(2145521L);
        row.setDpd(dpd);
        row.setStage(stage);
        row.setCollectionStatus(collectionStatus);
        when(mapper.selectByCaseId(CASE_ID)).thenReturn(row);
        return service.getCaseInfo(CASE_ID);
    }

    @Test
    @DisplayName("无尾款：dpd=0 且 stage 为 null，绝不能被 fromDpd 兜成 S0")
    void settledCaseWithZeroDpdResolvesToNoStage() {
        CaseInfo info = caseInfoWith(0, null, "SETTLED");

        assertThat(info.getStage()).isNull();
        assertThat(info.isRepaid()).isTrue();
    }

    @Test
    @DisplayName("结清状态优先于 stage 列：口径打架时按不催处理，失败方向更安全")
    void settledCaseOverridesStaleStageColumn() {
        assertThat(caseInfoWith(0, "S2", "SETTLED").getStage()).isNull();
    }

    @Test
    @DisplayName("有尾款且今日到期：dpd=0 但未结清，属 S0 提醒窗口")
    void dueTodayWithRemainingBalanceResolvesToS0() {
        assertThat(caseInfoWith(0, "S0", "IN_COLLECTION").getStage()).isEqualTo(Stage.S0);
    }

    @Test
    @DisplayName("有尾款且下期超过 3 天：dpd<-3 且 stage 为 null，不属任何催收阶段")
    void notYetInCollectionWindowResolvesToNoStage() {
        assertThat(caseInfoWith(-10, null, "IN_COLLECTION").getStage()).isNull();
    }

    @Test
    @DisplayName("有尾款且在 [-3,0] 内：数仓给 S0 就用 S0")
    void upcomingWindowUsesUpstreamStage() {
        assertThat(caseInfoWith(-2, "S0", "IN_COLLECTION").getStage()).isEqualTo(Stage.S0);
    }

    @Test
    @DisplayName("已逾期但 stage 列缺失：按 dpd 兜底，漏催代价高于错档")
    void overdueWithoutStageFallsBackToDpdMapping() {
        assertThat(caseInfoWith(9, null, "IN_COLLECTION").getStage()).isEqualTo(Stage.S2);
    }

    @Test
    @DisplayName("stage 列有值时以其为准，不再按 dpd 推导")
    void upstreamStageWinsOverDpdMapping() {
        assertThat(caseInfoWith(9, "S4", "IN_COLLECTION").getStage()).isEqualTo(Stage.S4);
    }
}
