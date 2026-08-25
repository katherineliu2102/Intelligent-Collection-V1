package com.collection.service.repository;

import com.collection.common.model.CaseProjection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 还款事件里 {@code dpd} / {@code stage} 的自洽性护栏。
 *
 * <p>口径（2026-08-24 与数仓确认）：<b>无尾款</b>时 {@code dpd=0} 且 {@code stage=null}；<b>有尾款</b>时 {@code dpd}
 * 取剩余未结清项中的 max dpd（全部未到期则为负），{@code dpd < -3} 时 {@code stage=null}，其余按规则映射。 据此有两条不变量：
 *
 * <ol>
 *   <li>逾期金额归零时不存在「已到期未还」的项，dpd 必须是零或负数；此时报正 dpd 属自相矛盾。
 *   <li>还款只会移除未还项，因此<b>正</b> dpd 相对基线的增长上限，就是两次事件之间流逝的天数。
 * </ol>
 *
 * <p>第二条只约束正 dpd。无尾款用 {@code dpd=0} 表示，而基线可能是负数（例：仅剩一期、10 天后到期， 基线 {@code dpd=-10}），结清后 {@code 0 >
 * -10} 是正常的语义跳变而非口径错误——若一并按上限卡， 会把正常的结清事件误判掉。非正 dpd 意味着根本没有逾期项，单调性无从谈起。
 *
 * <p>加这道护栏的直接原因：案 502880 的还款事件报 {@code dpd=65/66}，反算锚点是首期到期日 2026-06-18—— 而该期早在 06-20 就已结清，真实值应为
 * 4。若照单全收，投影会落下错档，日切据此判为升档， 进而取消在跑的计划并按更高阶段重建，客户收到过激话术。
 *
 * <p>护栏只拒绝 dpd/stage 两个字段，金额与结清标志照常入账——那部分数据经明细核对无误，而丢还款的 代价（已还清客户继续被催）远高于阶段晚一个日切纠正。
 */
final class RepaymentConsistencyGuard {

    private static final Logger log = LoggerFactory.getLogger(RepaymentConsistencyGuard.class);

    /** 同日事件的边界与取整容差；不放宽到 1 以上，否则 502880 那类跳变会被放过。 */
    private static final long TOLERANCE_DAYS = 1L;

    private RepaymentConsistencyGuard() {}

    /**
     * 还款增量携带的 dpd/stage 是否可信。
     *
     * @return {@code false} 表示保持基线值，交由 caseEvent 与日切纠正
     */
    static boolean acceptsDpdAndStage(CaseProjection current, CaseProjection delta) {
        Integer incoming = delta.getDpd();
        if (incoming == null) {
            return false;
        }
        BigDecimal overdue = delta.getOverdueAmount();
        if (overdue != null && overdue.signum() == 0 && incoming > 0) {
            log.warn(
                    "[Repayment] caseId={} 逾期金额已归零却报 dpd={}（正数意味着仍有到期未还），"
                            + "判定该值口径有误，本次不同步 dpd/stage，金额照常入账",
                    delta.getCaseId(),
                    incoming);
            return false;
        }
        Integer baseline = current.getDpd();
        if (baseline == null || incoming <= 0) {
            return true;
        }
        long elapsed = elapsedDays(current.getUpdatedAt(), delta.getUpdatedAt());
        long ceiling = baseline + elapsed + TOLERANCE_DAYS;
        if (incoming > ceiling) {
            log.warn(
                    "[Repayment] caseId={} 还款事件 dpd={} 超出可能上限 {}（基线 {} + 间隔 {} 天）："
                            + "还款不会让逾期加深，判定该值口径有误，本次不同步 dpd/stage，金额照常入账",
                    delta.getCaseId(),
                    incoming,
                    ceiling,
                    baseline,
                    elapsed);
            return false;
        }
        return true;
    }

    private static long elapsedDays(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return 0L;
        }
        return Math.max(0L, ChronoUnit.DAYS.between(from, to));
    }
}
