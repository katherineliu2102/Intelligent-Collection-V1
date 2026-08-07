package com.collection.ingestion.job;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 日切重跑去重与全量扫描游标：均存 Redis，避免应用重启后重复全表扫描。 */
@Component
@ConditionalOnProperty(
        prefix = "collection.ingestion",
        name = "redis-dedup-enabled",
        havingValue = "true")
public class RedisDailyRollDeduplicator {

    private static final String KEY_PREFIX = "collection:ingestion:dedup:";
    private static final String ROLL_KEY_PREFIX = "collection:ingestion:daily-roll:";
    private static final ZoneId PHT = ZoneId.of("Asia/Manila");
    private final StringRedisTemplate redisTemplate;

    public RedisDailyRollDeduplicator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean acquire(String type, Long loanId, int dpd) {
        String key = KEY_PREFIX + type + ":" + loanId + ":" + dpd;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofDays(2));
        return Boolean.TRUE.equals(acquired);
    }

    public Long currentCursor() {
        String value = redisTemplate.opsForValue().get(cursorKey());
        return value == null ? null : Long.valueOf(value);
    }

    public void advanceCursor(Long loanId) {
        redisTemplate.opsForValue().set(cursorKey(), String.valueOf(loanId), Duration.ofDays(2));
    }

    public void clearCursor() {
        redisTemplate.delete(cursorKey());
    }

    public boolean completedToday() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(completedKey()));
    }

    public void markCompletedToday() {
        redisTemplate.opsForValue().set(completedKey(), "1", Duration.ofDays(2));
        clearCursor();
    }

    private String cursorKey() {
        return ROLL_KEY_PREFIX + businessDate() + ":cursor";
    }

    private String completedKey() {
        return ROLL_KEY_PREFIX + businessDate() + ":completed";
    }

    private String businessDate() {
        return LocalDate.now(PHT).toString();
    }
}
