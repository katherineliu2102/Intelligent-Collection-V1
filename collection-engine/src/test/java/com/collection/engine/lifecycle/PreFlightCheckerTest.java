package com.collection.engine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.collection.common.enums.CancelReason;
import com.collection.common.model.CaseInfo;
import com.collection.common.service.CaseService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PreFlightChecker 系统级守卫分支单测（核心引擎规格 §3.1②、§5）。全 mock，不连库。 覆盖测试矩阵 #5a-5e：案件不存在 / 已还款 / 存活 / 读取失败重投。
 */
@ExtendWith(MockitoExtension.class)
class PreFlightCheckerTest {

    private static final long CASE_ID = 1002L;

    @Mock private CaseService caseService;
    @InjectMocks private PreFlightChecker preFlightChecker;

    private CaseInfo alive() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(CASE_ID);
        info.setDpd(37);
        info.setTotalOutstanding(new BigDecimal("1234.56"));
        info.setRepaid(false);
        return info;
    }

    @Test
    @DisplayName("#5a 案件不存在(null) → 阻断 CASE_NOT_FOUND")
    void caseNotFound_blocks() {
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(null);

        PreFlightResult result = preFlightChecker.inspect(CASE_ID);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getBlockingReason()).isEqualTo(CancelReason.CASE_NOT_FOUND);
    }

    @Test
    @DisplayName("#5b 已还款 → 阻断 REPAID")
    void repaid_blocks() {
        CaseInfo info = alive();
        info.setRepaid(true);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(info);

        PreFlightResult result = preFlightChecker.inspect(CASE_ID);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getBlockingReason()).isEqualTo(CancelReason.REPAID);
    }

    @Test
    @DisplayName("#5c 无已到期余额 → 阻断 NO_DUE_BALANCE，禁止发送零金额文案")
    void noDueBalance_blocks() {
        CaseInfo info = alive();
        info.setTotalOutstanding(BigDecimal.ZERO);
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(info);

        PreFlightResult result = preFlightChecker.inspect(CASE_ID);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getBlockingReason()).isEqualTo(CancelReason.NO_DUE_BALANCE);
    }

    @Test
    @DisplayName("S0 逾期额为 0 但有 upcomingAmount → 放行，避免提醒案被当没钱可催")
    void s0UpcomingOnly_passes() {
        CaseInfo info = alive();
        info.setStage(com.collection.common.enums.Stage.S0);
        info.setDpd(-2);
        info.setTotalOutstanding(BigDecimal.ZERO);
        info.setUpcomingAmount(new BigDecimal("1800"));
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(info);

        PreFlightResult result = preFlightChecker.inspect(CASE_ID);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getCaseInfo().getUpcomingAmount())
                .isEqualByComparingTo(new BigDecimal("1800"));
    }

    @Test
    @DisplayName("#5d 案件存活（未还款）→ 放行并带出实时案件数据，供渲染前刷新日变字段")
    void alive_passesWithCaseInfo() {
        when(caseService.getCaseInfo(CASE_ID)).thenReturn(alive());

        PreFlightResult result = preFlightChecker.inspect(CASE_ID);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getCaseInfo()).isNotNull();
        assertThat(result.getCaseInfo().getDpd()).isEqualTo(37);
        assertThat(result.getCaseInfo().getTotalOutstanding())
                .isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    @DisplayName("#5e 读取失败(DB 不可达) → 抛出异常，由事件总线重投")
    void readFailure_propagatesForRetry() {
        when(caseService.getCaseInfo(CASE_ID)).thenThrow(new RuntimeException("MySQL down"));
        assertThatThrownBy(() -> preFlightChecker.inspect(CASE_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("MySQL down");
    }
}
