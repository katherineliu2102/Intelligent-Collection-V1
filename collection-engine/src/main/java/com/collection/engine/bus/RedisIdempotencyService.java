package com.collection.engine.bus;

import com.collection.common.service.IdempotencyService;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Pilot/production 幂等锁：Redis SET NX EX，实例间共享且自动过期。 */
@Component
@ConditionalOnProperty(name = "collection.idempotency", havingValue = "redis")
public class RedisIdempotencyService implements IdempotencyService {

    private static final String KEY_PREFIX = "collection:pilot:processed:";

    private final StringRedisTemplate redisTemplate;

    public RedisIdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean acquire(String idempotencyKey, int ttlMinutes) {
        Boolean acquired =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(
                                KEY_PREFIX + idempotencyKey,
                                "1",
                                Duration.ofMinutes(Math.max(1, ttlMinutes)));
        return Boolean.TRUE.equals(acquired);
    }
}
