package com.collection.common.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;

/** 守卫裁定。ExecutionGuard.evaluate() 的输出。对应领域模型 §5.3。 */
@Getter
public class GuardVerdict {

    /** true=放行，false=拦截。 */
    private final boolean allowed;
    /** 拦截原因（allowed=true 时为 null）。 */
    private final String blockedReason;
    /** 拦截规则类型：FREQUENCY_LIMIT / TIME_WINDOW / CONNECT_AND_STOP / ABANDONMENT_RATE。 */
    private final String blockedRuleType;
    /** 延后执行的目标时间；仅 TIME_WINDOW 等可延后规则使用。 */
    private final LocalDateTime deferUntil;

    /**
     * 本次放行为该步骤预占的日频配额；null 表示未占用。
     *
     * <p>配额必须先占后发——后置计数会在校验与提交之间开并发窗口，同一用户同渠道可能双双通过校验后双双发出， 而超发是合规事故。代价是触达没发出去时配额已经扣掉：生产单渠道日限为 1
     * 时，一次瞬态故障 就等于该客户当天该渠道零触达。故由占用方在此交回键，引擎在渠道能证明请求未写给供应商 （{@code StepResult.retryable}）的终态调用
     * {@code ComplianceCounterService.release} 归还。
     *
     * <p>由 Guard 交回键而非引擎重算：日期取自 Guard 自己的合规时区配置，引擎按别的时区推可能落到相邻一天， 归还就会打在错误的计数上。
     *
     * <p>未声明即 null：自定义 Guard 沿用 {@link #allow()} 时引擎不回滚，不会把计数扣成负数。
     */
    private final QuotaReservation quotaReservation;

    private GuardVerdict(
            boolean allowed,
            String blockedReason,
            String blockedRuleType,
            LocalDateTime deferUntil,
            QuotaReservation quotaReservation) {
        this.allowed = allowed;
        this.blockedReason = blockedReason;
        this.blockedRuleType = blockedRuleType;
        this.deferUntil = deferUntil;
        this.quotaReservation = quotaReservation;
    }

    public static GuardVerdict allow() {
        return new GuardVerdict(true, null, null, null, null);
    }

    /** 放行，且已按 {@code reservation} 预占日频配额——引擎须在确认未发出的终态归还。 */
    public static GuardVerdict allowAfterConsumingQuota(QuotaReservation reservation) {
        return new GuardVerdict(true, null, null, null, reservation);
    }

    public static GuardVerdict block(String reason, String ruleType) {
        return new GuardVerdict(false, reason, ruleType, null, null);
    }

    /** 拦截当前执行，但允许引擎在指定时点重新调度。 */
    public static GuardVerdict defer(String reason, String ruleType, LocalDateTime deferUntil) {
        return new GuardVerdict(false, reason, ruleType, deferUntil, null);
    }

    /** 一次日频配额预占的坐标，原样交回给 {@code ComplianceCounterService.release}。 */
    @Getter
    public static final class QuotaReservation {
        private final Long userId;
        private final String channel;
        private final LocalDate date;

        public QuotaReservation(Long userId, String channel, LocalDate date) {
            this.userId = userId;
            this.channel = channel;
            this.date = date;
        }
    }
}
