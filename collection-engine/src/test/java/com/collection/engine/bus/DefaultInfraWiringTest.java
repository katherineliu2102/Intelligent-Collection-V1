package com.collection.engine.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.event.CollectionEventBus;
import com.collection.common.service.IdempotencyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 防漂移守卫：Phase 1 基础设施为内存版（架构设计文档 §3.1 / §1.5）。
 *
 * <p>断言默认装配为内存实现，且 Redis 版尚未引入。若未来接入 Redis（多实例前置），
 * 本测试会失败——提醒同步更新架构文档中「Phase 1 现状为内存版」的口径。
 */
class DefaultInfraWiringTest {

    @Test
    @DisplayName("默认事件总线为 InMemoryEventBus（collection.eventbus 缺省=memory）")
    void eventBusDefaultsToMemory() {
        assertThat(CollectionEventBus.class).isAssignableFrom(InMemoryEventBus.class);
        ConditionalOnProperty cond =
                InMemoryEventBus.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(cond).isNotNull();
        assertThat(cond.name()).containsExactly("collection.eventbus");
        assertThat(cond.havingValue()).isEqualTo("memory");
        assertThat(cond.matchIfMissing()).isTrue();
    }

    @Test
    @DisplayName("默认幂等为 InMemoryIdempotencyService（collection.idempotency 缺省=memory）")
    void idempotencyDefaultsToMemory() {
        assertThat(IdempotencyService.class).isAssignableFrom(InMemoryIdempotencyService.class);
        ConditionalOnProperty cond =
                InMemoryIdempotencyService.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(cond).isNotNull();
        assertThat(cond.name()).containsExactly("collection.idempotency");
        assertThat(cond.havingValue()).isEqualTo("memory");
        assertThat(cond.matchIfMissing()).isTrue();
    }

    @Test
    @DisplayName("Redis 版基础设施尚未引入（切多实例时才补，届时更新本测试与架构文档 §3.1）")
    void redisImplementationsNotYetPresent() {
        assertThat(classPresent("com.collection.engine.bus.RedisStreamEventBus")).isFalse();
        assertThat(classPresent("com.collection.engine.bus.RedisIdempotencyService")).isFalse();
    }

    private static boolean classPresent(String fqcn) {
        try {
            Class.forName(fqcn);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
