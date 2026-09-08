package com.collection.service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.model.CaseProjection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 还款事件 dpd/stage 自洽性护栏。数值取自案 502880 的真实还款明细。 */
class RepaymentConsistencyGuardTest {

    private static CaseProjection projection(
            Integer dpd, BigDecimal overdueAmount, LocalDateTime updatedAt) {
        CaseProjection p = new CaseProjection();
        p.setCaseId(502880L);
        p.setDpd(dpd);
        p.setStage("S4");
        p.setOverdueAmount(overdueAmount);
        p.setUpdatedAt(updatedAt);
        return p;
    }

    @Test
    @DisplayName("502880 实况：逾期已归零却报 dpd=66（锚在早已结清的首期），必须拒绝")
    void rejectsPositiveDpdWhenNothingRemainsOverdue() {
        // 入案 8-22 02:00 报 dpd=4：第三期 8-18 到期、逾期 4 天，与还款明细一致
        CaseProjection baseline =
                projection(4, new BigDecimal("1620.64"), LocalDateTime.of(2026, 8, 22, 2, 0, 40));
        // 还款事件报 dpd=66，反算锚点 6-18，而该期 6-20 即已结清
        CaseProjection delta =
                projection(66, BigDecimal.ZERO, LocalDateTime.of(2026, 8, 23, 5, 45, 4));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isFalse();
    }

    @Test
    @DisplayName("提前还清：逾期归零且 dpd 为负（距下一期的天数），是合法更新必须放行")
    void acceptsNegativeDpdAfterEarlyPayoff() {
        CaseProjection baseline =
                projection(4, new BigDecimal("1620.64"), LocalDateTime.of(2026, 8, 22, 2, 0));
        CaseProjection delta =
                projection(-5, BigDecimal.ZERO, LocalDateTime.of(2026, 8, 22, 21, 0));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isTrue();
    }

    @Test
    @DisplayName("正常部分还款：dpd 随天数自然推进，落在上限内即采信")
    void acceptsDpdThatOnlyGrewWithElapsedTime() {
        CaseProjection baseline =
                projection(10, new BigDecimal("2000.00"), LocalDateTime.of(2026, 8, 1, 3, 0));
        CaseProjection delta =
                projection(15, new BigDecimal("900.00"), LocalDateTime.of(2026, 8, 6, 3, 0));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isTrue();
    }

    @Test
    @DisplayName("同日还款 dpd 不变：最常见的正常情形，不得被护栏误伤")
    void acceptsUnchangedDpdOnSameDayRepayment() {
        CaseProjection baseline =
                projection(9, new BigDecimal("2000.00"), LocalDateTime.of(2026, 8, 6, 3, 0));
        CaseProjection delta =
                projection(9, new BigDecimal("900.00"), LocalDateTime.of(2026, 8, 6, 19, 57));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isTrue();
    }

    @Test
    @DisplayName("还款不会让逾期加深：超出「基线 + 间隔天数」的跳增一律拒绝")
    void rejectsJumpBeyondElapsedDays() {
        CaseProjection baseline =
                projection(10, new BigDecimal("2000.00"), LocalDateTime.of(2026, 8, 1, 3, 0));
        CaseProjection delta =
                projection(40, new BigDecimal("900.00"), LocalDateTime.of(2026, 8, 2, 3, 0));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isFalse();
    }

    @Test
    @DisplayName("基线无 dpd 时无从比对，放行以免卡住首次增量")
    void acceptsWhenBaselineHasNoDpd() {
        CaseProjection baseline =
                projection(null, new BigDecimal("2000.00"), LocalDateTime.of(2026, 8, 1, 3, 0));
        CaseProjection delta =
                projection(30, new BigDecimal("900.00"), LocalDateTime.of(2026, 8, 2, 3, 0));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isTrue();
    }

    @Test
    @DisplayName("无尾款结清：dpd=0 虽高于负基线，仍是合法语义跳变，不得按单调性上限误杀")
    void acceptsZeroDpdOnFullClearEvenWhenBaselineWasNegative() {
        // 仅剩一期、10 天后到期 → 基线 dpd=-10；当天全额结清 → 数仓报 dpd=0
        CaseProjection baseline =
                projection(-10, BigDecimal.ZERO, LocalDateTime.of(2026, 8, 20, 3, 0));
        CaseProjection delta = projection(0, BigDecimal.ZERO, LocalDateTime.of(2026, 8, 20, 19, 0));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isTrue();
    }

    @Test
    @DisplayName("有尾款且全部未到期：dpd 由负变负仍放行，单调性只约束正 dpd")
    void acceptsNegativeDpdRegardlessOfBaseline() {
        CaseProjection baseline =
                projection(-20, BigDecimal.ZERO, LocalDateTime.of(2026, 8, 1, 3, 0));
        CaseProjection delta = projection(-2, BigDecimal.ZERO, LocalDateTime.of(2026, 8, 2, 3, 0));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isTrue();
    }

    @Test
    @DisplayName("仍有逾期却报负 dpd（锚在下一期）：必须拒绝，535728 类错口径")
    void rejectsNegativeDpdWhileOverdueRemains() {
        CaseProjection baseline =
                projection(1, new BigDecimal("240.77"), LocalDateTime.of(2026, 9, 8, 2, 0));
        CaseProjection delta =
                projection(-29, new BigDecimal("240.77"), LocalDateTime.of(2026, 9, 8, 11, 0));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isFalse();
    }

    @Test
    @DisplayName("当期还清、仅剩未到期尾款：逾期归零且 dpd 为负，合法放行")
    void acceptsNegativeDpdWhenOverdueClearedToUpcomingOnly() {
        CaseProjection baseline =
                projection(1, new BigDecimal("240.77"), LocalDateTime.of(2026, 9, 8, 2, 0));
        CaseProjection delta =
                projection(-29, BigDecimal.ZERO, LocalDateTime.of(2026, 9, 8, 11, 0));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isTrue();
    }

    @Test
    @DisplayName("增量无 dpd：没有可同步的值，保持基线")
    void rejectsWhenDeltaHasNoDpd() {
        CaseProjection baseline =
                projection(10, new BigDecimal("2000.00"), LocalDateTime.of(2026, 8, 1, 3, 0));
        CaseProjection delta =
                projection(null, new BigDecimal("900.00"), LocalDateTime.of(2026, 8, 2, 3, 0));

        assertThat(RepaymentConsistencyGuard.acceptsDpdAndStage(baseline, delta)).isFalse();
    }
}
