package com.collection.ingestion.pubsub;

import java.time.Duration;
import java.util.Collections;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Pilot / 生产实现：三类去重标记落 Redis，跨重启保留、跨实例共享。
 *
 * <p>`last_seen` 用 Lua 做"仅当更大才写"，避免并发乱序消息把水位改小。
 */
@Component
@ConditionalOnProperty(
        prefix = "collection.ingestion",
        name = "redis-dedup-enabled",
        havingValue = "true")
public class RedisIngestionDedupStore implements IngestionDedupStore {

    private static final String MESSAGE_PREFIX = "collection:ingestion:dedup:msg:";
    private static final String LAST_SEEN_PREFIX = "collection:ingestion:last-seen:";
    private static final String INGESTED_PREFIX = "collection:ingestion:ingested:";
    private static final Duration MESSAGE_TTL = Duration.ofDays(7);
    private static final Duration LOAN_TTL = Duration.ofDays(90);

    private static final RedisScript<Long> SET_IF_GREATER =
            new DefaultRedisScript<>(
                    "local cur = redis.call('GET', KEYS[1]) "
                            + "if (not cur) or (tonumber(cur) < tonumber(ARGV[1])) then "
                            + "  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) "
                            + "  return 1 "
                            + "end "
                            + "return 0",
                    Long.class);

    private final StringRedisTemplate redis;

    public RedisIngestionDedupStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isMessageProcessed(String messageId) {
        return messageId != null && Boolean.TRUE.equals(redis.hasKey(MESSAGE_PREFIX + messageId));
    }

    @Override
    public void markMessageProcessed(String messageId) {
        if (messageId != null) {
            redis.opsForValue().set(MESSAGE_PREFIX + messageId, "1", MESSAGE_TTL);
        }
    }

    @Override
    public boolean isStale(Long loanId, Long publishMillis) {
        if (loanId == null || publishMillis == null) {
            return false;
        }
        String seen = redis.opsForValue().get(LAST_SEEN_PREFIX + loanId);
        return seen != null && publishMillis < Long.parseLong(seen);
    }

    @Override
    public void recordSeen(Long loanId, Long publishMillis) {
        if (loanId == null || publishMillis == null) {
            return;
        }
        redis.execute(
                SET_IF_GREATER,
                Collections.singletonList(LAST_SEEN_PREFIX + loanId),
                String.valueOf(publishMillis),
                String.valueOf(LOAN_TTL.getSeconds()));
    }

    @Override
    public boolean isIngested(Long loanId) {
        return loanId != null && Boolean.TRUE.equals(redis.hasKey(INGESTED_PREFIX + loanId));
    }

    @Override
    public void markIngested(Long loanId) {
        if (loanId != null) {
            redis.opsForValue().set(INGESTED_PREFIX + loanId, "1", LOAN_TTL);
        }
    }

    @Override
    public void clearIngested(Long loanId) {
        if (loanId != null) {
            redis.delete(INGESTED_PREFIX + loanId);
        }
    }
}
