package com.collection.engine.bus;

import com.collection.common.service.IdempotencyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存幂等锁（Phase 1 链路验证默认实现）。对应 {@link IdempotencyService}。
 *
 * <p>生产应替换为 Redis SETNX 实现（基础设施规范 §3）。
 * 激活条件：collection.idempotency=memory（缺省默认）。
 */
@Component
@ConditionalOnProperty(name = "collection.idempotency", havingValue = "memory", matchIfMissing = true)
public class InMemoryIdempotencyService implements IdempotencyService {

    /** key -> 过期时间戳(ms)。 */
    private final ConcurrentHashMap<String, Long> store = new ConcurrentHashMap<>();

    @Override
    public boolean acquire(String idempotencyKey, int ttlMinutes) {
        long now = System.currentTimeMillis();
        long expireAt = now + ttlMinutes * 60_000L;
        // 惰性清理过期 key
        Long existing = store.get(idempotencyKey);
        if (existing != null && existing < now) {
            store.remove(idempotencyKey, existing);
        }
        return store.putIfAbsent(idempotencyKey, expireAt) == null;
    }
}
