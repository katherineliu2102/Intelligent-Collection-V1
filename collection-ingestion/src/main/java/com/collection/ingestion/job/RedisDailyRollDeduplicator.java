package com.collection.ingestion.job;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
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
        redisTemplate
                .opsForValue()
                .set(advancedAtKey(), LocalDateTime.now(PHT).toString(), Duration.ofDays(2));
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

    /**
     * T3o-O3 / T5-S7 的可查询证据：日切窗口内游标是否在推进、当日完成标记是否已写。
     *
     * <p>窗口是 03:35–05:55 每 5 分钟续跑，判「按时完成」要区分三种状态：还没开始、在推进、已完成。 只看完成标记分不出后两者与「卡住不动」，故一并给出游标值与上次推进时间。
     */
    public Map<String, Object> evidenceSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("businessDate", businessDate());
        snapshot.put("cursorKey", cursorKey());
        snapshot.put("cursor", currentCursor());
        snapshot.put("completedKey", completedKey());
        snapshot.put("completedToday", completedToday());
        snapshot.put("lastAdvancedAt", redisTemplate.opsForValue().get(advancedAtKey()));
        return snapshot;
    }

    private String cursorKey() {
        return ROLL_KEY_PREFIX + businessDate() + ":cursor";
    }

    private String completedKey() {
        return ROLL_KEY_PREFIX + businessDate() + ":completed";
    }

    private String advancedAtKey() {
        return ROLL_KEY_PREFIX + businessDate() + ":cursor-advanced-at";
    }

    private String businessDate() {
        return LocalDate.now(PHT).toString();
    }
}
