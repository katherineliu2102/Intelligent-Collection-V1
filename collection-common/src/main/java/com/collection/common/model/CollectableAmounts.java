package com.collection.common.model;

import com.collection.common.enums.Stage;
import java.math.BigDecimal;

/**
 * 可催金额口径。S0（D-3～D0 提醒）对客金额是 {@code upcomingAmount}；S1+ 是已到期 {@code totalOutstanding}。PreFlight /
 * 日切续建必须与文案、Facade 金额一致，不能只看逾期额。
 */
public final class CollectableAmounts {

    private CollectableAmounts() {}

    public static boolean isS0(Stage stage, int dpd) {
        if (stage == Stage.S0) {
            return true;
        }
        return stage == null && dpd <= 0;
    }

    /** 日切：S0 有 upcoming 或已到期余额即视为可催。 */
    public static boolean hasCollectableBalance(CaseInfo info) {
        if (info == null) {
            return false;
        }
        if (isS0(info.getStage(), info.getDpd())) {
            return positive(info.getUpcomingAmount()) || positive(info.getTotalOutstanding());
        }
        return positive(info.getTotalOutstanding());
    }

    /**
     * PreFlight：S1+ 仅在 {@code totalOutstanding} 非空且 ≤0 时阻断（null 放行，与历史一致）。S0 在 upcoming
     * 与已到期余额都非正时阻断。
     */
    public static boolean shouldBlockNoDueBalance(CaseInfo info) {
        if (info == null) {
            return true;
        }
        if (isS0(info.getStage(), info.getDpd())) {
            return !positive(info.getUpcomingAmount()) && !positive(info.getTotalOutstanding());
        }
        return info.getTotalOutstanding() != null
                && info.getTotalOutstanding().compareTo(BigDecimal.ZERO) <= 0;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
