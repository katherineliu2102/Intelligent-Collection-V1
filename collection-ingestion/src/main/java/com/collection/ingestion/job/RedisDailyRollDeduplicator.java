package com.collection.ingestion.job;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Pilot 日切重跑去重：同 loan/day/eventType 只允许首次发布领域事件。 */
@Component
@ConditionalOnProperty(
        prefix = "collection.ingestion",
        name = "redis-dedup-enabled",
        havingValue = "true")
public class RedisDailyRollDeduplicator {

    private final StringRedisTemplate redisTemplate;

    public RedisDailyRollDeduplicator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean acquire(String type, Long loanId, int dpd) {
        String key = "collection:pilot:dedup:" + type + ":" + loanId + ":" + dpd;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofHours(30));
        return Boolean.TRUE.equals(acquired);
    }
}
