package com.collection.admin.health;

import com.collection.engine.bus.RedisStreamEventBus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 把 Redis 事件总线的消费存活并入 {@code /actuator/health}。
 *
 * <p>2026-08-25：Redis 实例重启后消费组消失，应用每秒抛一次 NOGROUP、十余小时没消费任何事件， 而 {@code /actuator/health} 全程是
 * UP——`redis` 那一项只做 PING，连得上就算健康，看不出 组没了、消费停了。整条触达链路停摆却无任何红灯，与之前 Pub/Sub 订阅流的教训完全同型。
 *
 * <p>判 DOWN 而非 OUT_OF_SERVICE：总线不消费时，due 事件不会被执行，等同于本实例失去承载能力， 编排层应把流量判给别的实例而不是继续观望。
 */
@Component
@ConditionalOnBean(RedisStreamEventBus.class)
public class RedisEventBusHealthIndicator implements HealthIndicator {

    private final RedisStreamEventBus eventBus;

    public RedisEventBusHealthIndicator(RedisStreamEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Health health() {
        Throwable failure = eventBus.getConsumeFailure();
        if (failure == null) {
            return Health.up().build();
        }
        return Health.down()
                .withDetail("reason", "事件总线未在消费，本实例不会执行任何到期步骤")
                .withDetail("consecutiveFailures", eventBus.getConsecutiveConsumeFailures())
                .withDetail("cause", String.valueOf(failure))
                .build();
    }
}
