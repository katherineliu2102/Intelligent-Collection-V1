package com.collection.channel.strategy;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.enums.ChannelType;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.repository.EmailSuppressionRepository;
import com.collection.common.service.ComplianceCounterService;
import com.collection.common.spi.ExecutionGuard;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Phase 1 简化版 ExecutionGuard —— 时段 + 空地址 + 内存频率计数器（单渠道日上限及跨渠道日总上限，无 Redis）。
 *
 * <p>主架构临时代写，推进 L4a-全测试。编排同事回来后替换为 Redis Lua 原子计数的生产实现。
 */
@Primary
@Component
public class ConfigurableExecutionGuard implements ExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableExecutionGuard.class);

    @Resource private ChannelProperties channelProperties;

    @Resource private ComplianceCounterService complianceCounterService;

    @Resource private EmailSuppressionRepository emailSuppressionRepository;

    @Override
    public GuardVerdict evaluate(ExecutionContext context) {
        GuardVerdict timeCheck = checkQuietHours();
        if (timeCheck != null) {
            return timeCheck;
        }

        GuardVerdict addressCheck = checkAddress(context);
        if (addressCheck != null) {
            return addressCheck;
        }

        FrequencyOutcome frequency = checkFrequency(context);
        if (frequency.verdict != null) {
            return frequency.verdict;
        }

        return frequency.reservation != null
                ? GuardVerdict.allowAfterConsumingQuota(frequency.reservation)
                : GuardVerdict.allow();
    }

    private GuardVerdict checkQuietHours() {
        ChannelProperties.Compliance comp = channelProperties.getCompliance();
        String tz = comp.getTimezone();
        ZoneId zone = ZoneId.of(tz != null ? tz : "Asia/Manila");
        LocalTime now = ZonedDateTime.now(zone).toLocalTime();

        LocalTime start = parseTime(comp.getQuietHoursStart(), LocalTime.of(21, 0));
        LocalTime end = parseTime(comp.getQuietHoursEnd(), LocalTime.of(8, 0));

        boolean inQuiet;
        if (start.isAfter(end)) {
            inQuiet = now.isAfter(start) || now.isBefore(end);
        } else {
            inQuiet = now.isAfter(start) && now.isBefore(end);
        }

        if (inQuiet) {
            return GuardVerdict.defer(
                    "QUIET_HOURS "
                            + comp.getQuietHoursStart()
                            + "-"
                            + comp.getQuietHoursEnd()
                            + " "
                            + tz,
                    "TIME_WINDOW",
                    nextAllowedAt(ZonedDateTime.now(zone), start, end));
        }
        return null;
    }

    private static java.time.LocalDateTime nextAllowedAt(
            ZonedDateTime now, LocalTime start, LocalTime end) {
        ZonedDateTime next =
                now.withHour(end.getHour()).withMinute(end.getMinute()).withSecond(0).withNano(0);
        if (start.isAfter(end) && !now.toLocalTime().isBefore(start)) {
            next = next.plusDays(1);
        }
        return next.toLocalDateTime();
    }

    private GuardVerdict checkAddress(ExecutionContext context) {
        ChannelType channel = context.getCurrentStep().getChannelType();
        ContextSnapshot snapshot = context.getContextSnapshot();
        UserProfile profile = snapshot != null ? snapshot.getUserProfile() : null;
        UserProfile.BasicInfo basic = profile != null ? profile.getBasic() : null;

        switch (channel) {
            case SMS:
            case AI_CALL:
                if (basic == null || StringUtils.isBlank(basic.getPrimaryPhone())) {
                    return GuardVerdict.block("NO_PHONE", "NO_PHONE");
                }
                break;
            case PUSH:
                // PUSH 无 token 时由 Adapter 内部 fallback 走 SMS，Guard 不拦截；
                // 仅当连 phone 都没有（无法 fallback）时才 block
                if (basic == null || StringUtils.isBlank(basic.getPrimaryPhone())) {
                    UserProfile.DeviceInfo device = profile != null ? profile.getDevice() : null;
                    if (device == null || StringUtils.isBlank(device.getJpushToken())) {
                        return GuardVerdict.block("NO_TOKEN_NO_PHONE", "NO_TOKEN");
                    }
                }
                break;
            case EMAIL:
                if (basic == null || StringUtils.isBlank(basic.getEmail())) {
                    return GuardVerdict.block("NO_EMAIL", "NO_EMAIL");
                }
                // 抑制名单判定放在 Guard 而非 Adapter：退信地址再发一次不是渠道故障而是不该发，
                // 落到 Adapter 会记成 step FAILED 并触发重试，把「地址已废」读成供应商抖动。
                if (emailSuppressionRepository.isSuppressed(basic.getEmail())) {
                    return GuardVerdict.block("EMAIL_SUPPRESSED", "EMAIL_SUPPRESSED");
                }
                break;
            default:
                break;
        }
        return null;
    }

    /** 频控结果：{@code verdict != null} 表示拦截；放行时 {@code reservation} 非空即本次预占了配额。 */
    private static final class FrequencyOutcome {
        private static final FrequencyOutcome PASSED_WITHOUT_CONSUMING =
                new FrequencyOutcome(null, null);

        private final GuardVerdict verdict;
        private final GuardVerdict.QuotaReservation reservation;

        private FrequencyOutcome(GuardVerdict verdict, GuardVerdict.QuotaReservation reservation) {
            this.verdict = verdict;
            this.reservation = reservation;
        }

        private static FrequencyOutcome blocked(GuardVerdict verdict) {
            return new FrequencyOutcome(verdict, null);
        }

        private static FrequencyOutcome consumed(GuardVerdict.QuotaReservation reservation) {
            return new FrequencyOutcome(null, reservation);
        }
    }

    private FrequencyOutcome checkFrequency(ExecutionContext context) {
        ChannelType channel = context.getCurrentStep().getChannelType();
        Long caseId = context.getPlan().getCaseId();

        // 重试是同一次触达尝试的延续，不是新的一次触达：配额已在首次尝试（retryCount=0）预占，
        // 这里再占一次会让退避重试自己撞上日限——生产单渠道日限为 1 时，首次瞬态故障后的重试
        // 必定被 FREQUENCY_LIMIT 拦掉，且 step 终态被记成 COMPLIANCE_BLOCKED，告警指向合规而非供应商。
        // 重试次数由 engine.step.max-retry-count 封顶，跳过频控不会让触达无限放大。
        int retryCount = context.getCurrentStep().getRetryCount();
        if (retryCount > 0) {
            log.debug(
                    "[guard] retry attempt {} for step {}, quota already reserved at first attempt",
                    retryCount,
                    context.getCurrentStep().getId());
            return FrequencyOutcome.PASSED_WITHOUT_CONSUMING;
        }

        Integer limit = null;
        if (caseId != null && caseId.equals(channelProperties.getL4a().getGuardFrequencyCaseId())) {
            limit = channelProperties.getL4a().getGuardFrequencyDailyLimit();
        } else {
            Map<String, Integer> limits = channelProperties.getCompliance().getDailyLimit();
            if (limits != null && !limits.isEmpty()) {
                limit = limits.get(channel.name());
            }
        }
        Long userId = context.getPlan().getUserId();
        java.time.LocalDate date =
                ZonedDateTime.now(ZoneId.of(channelProperties.getCompliance().getTimezone()))
                        .toLocalDate();
        int channelLimit = limit == null ? 0 : limit;
        int totalLimit = channelProperties.getCompliance().getDailyTotalLimit();
        if (channelLimit > 0 || totalLimit > 0) {
            ComplianceCounterService.Counts counts =
                    complianceCounterService.tryConsume(
                            userId, channel.name(), date, channelLimit, totalLimit);
            if (channelLimit > 0 && counts.channel > channelLimit) {
                return FrequencyOutcome.blocked(
                        GuardVerdict.block(
                                "DAILY_LIMIT_EXCEEDED "
                                        + channel.name()
                                        + " "
                                        + counts.channel
                                        + "/"
                                        + channelLimit,
                                "FREQUENCY_LIMIT"));
            }
            if (totalLimit > 0 && counts.total > totalLimit) {
                return FrequencyOutcome.blocked(
                        GuardVerdict.block(
                                "DAILY_TOTAL_LIMIT_EXCEEDED " + counts.total + "/" + totalLimit,
                                "FREQUENCY_LIMIT"));
            }
            return FrequencyOutcome.consumed(
                    new GuardVerdict.QuotaReservation(userId, channel.name(), date));
        }
        return FrequencyOutcome.PASSED_WITHOUT_CONSUMING;
    }

    private static LocalTime parseTime(String timeStr, LocalTime fallback) {
        if (StringUtils.isBlank(timeStr)) {
            return fallback;
        }
        try {
            return LocalTime.parse(timeStr);
        } catch (Exception e) {
            return fallback;
        }
    }
}
