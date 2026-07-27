package com.collection.channel.strategy;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.enums.ChannelType;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.spi.ExecutionGuard;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Phase 1 简化版 ExecutionGuard —— 时段 + 空地址 + 内存频率计数器（无 Redis）。
 *
 * <p>主架构临时代写，推进 L4a-全测试。编排同事回来后替换为 Redis Lua 原子计数的生产实现。
 */
@Primary
@Component
public class ConfigurableExecutionGuard implements ExecutionGuard {

    @Resource private ChannelProperties channelProperties;

    private final ConcurrentHashMap<String, AtomicInteger> frequencyMap = new ConcurrentHashMap<>();

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

        GuardVerdict freqCheck = checkFrequency(context);
        if (freqCheck != null) {
            return freqCheck;
        }

        return GuardVerdict.allow();
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
            return GuardVerdict.block(
                    "QUIET_HOURS "
                            + comp.getQuietHoursStart()
                            + "-"
                            + comp.getQuietHoursEnd()
                            + " "
                            + tz,
                    "TIME_WINDOW");
        }
        return null;
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
                break;
            default:
                break;
        }
        return null;
    }

    private GuardVerdict checkFrequency(ExecutionContext context) {
        ChannelType channel = context.getCurrentStep().getChannelType();
        Long caseId = context.getPlan().getCaseId();

        Integer limit = null;
        if (caseId != null && caseId.equals(channelProperties.getL4a().getGuardFrequencyCaseId())) {
            limit = channelProperties.getL4a().getGuardFrequencyDailyLimit();
        } else {
            Map<String, Integer> limits = channelProperties.getCompliance().getDailyLimit();
            if (limits != null && !limits.isEmpty()) {
                limit = limits.get(channel.name());
            }
        }
        if (limit == null || limit <= 0) {
            return null;
        }

        Long userId = context.getPlan().getUserId();
        String dateKey =
                ZonedDateTime.now(ZoneId.of(channelProperties.getCompliance().getTimezone()))
                        .toLocalDate()
                        .toString();
        String key = userId + ":" + channel.name() + ":" + dateKey;

        AtomicInteger counter = frequencyMap.computeIfAbsent(key, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current > limit) {
            return GuardVerdict.block(
                    "DAILY_LIMIT_EXCEEDED " + channel.name() + " " + current + "/" + limit,
                    "FREQUENCY_LIMIT");
        }
        return null;
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
