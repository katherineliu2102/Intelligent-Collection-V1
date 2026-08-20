package com.collection.channel.compliance;

import com.collection.common.service.ComplianceCounterService;
import java.time.*;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 单个 Lua 调用原子占用渠道与跨渠道日配额。Redis 异常向上抛出以触发 fail-close。 */
@Component
@ConditionalOnProperty(name = "collection.compliance.counter", havingValue = "redis")
public class RedisComplianceCounterService implements ComplianceCounterService {
    private static final String KEY_PREFIX = "collection:compliance:daily:";

    private static final DefaultRedisScript<java.util.List> SCRIPT =
            new DefaultRedisScript<>(
                    "local c=redis.call('INCR',KEYS[1]); if c==1 then redis.call('EXPIREAT',KEYS[1],ARGV[1]) end; "
                            + "local t=redis.call('INCR',KEYS[2]); if t==1 then redis.call('EXPIREAT',KEYS[2],ARGV[1]) end; return {c,t}",
                    java.util.List.class);
    private final StringRedisTemplate redis;

    public RedisComplianceCounterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Counts tryConsume(
            Long userId, String channel, LocalDate date, int channelLimit, int totalLimit) {
        ZoneId zone = ZoneId.of("Asia/Manila");
        long expiry = date.plusDays(1).atStartOfDay(zone).toEpochSecond();
        String base = KEY_PREFIX + userId + ":";
        java.util.List result =
                redis.execute(
                        SCRIPT,
                        Arrays.asList(base + channel + ":" + date, base + "ALL:" + date),
                        String.valueOf(expiry));
        if (result == null || result.size() != 2)
            throw new IllegalStateException("compliance Redis Lua empty response");
        return new Counts(
                ((Number) result.get(0)).longValue(), ((Number) result.get(1)).longValue());
    }
}
