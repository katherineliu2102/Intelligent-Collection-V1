package com.collection.admin.job;

import javax.annotation.Resource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 把调度订阅流的存活并入 {@code /actuator/health}。
 *
 * <p>迁出调度控制台后，Cloud Scheduler 只能证明消息已发出，不能证明本实例收到了。订阅流一旦因鉴权失败终止， 表现是进程存活、health 为
 * UP、日志里有一行「调度订阅消费已启动」，而所有 tick 都收不到——整条触达链路停摆却无任何红灯。
 */
@Component
@ConditionalOnProperty(prefix = "collection.scheduler", name = "enabled", havingValue = "true")
public class PubSubScheduleHealthIndicator implements HealthIndicator {

    @Resource private PubSubScheduleConsumer consumer;

    @Override
    public Health health() {
        Throwable failure = consumer.getFailure();
        if (failure != null) {
            return Health.down()
                    .withDetail("subscription", consumer.subscriptionPath())
                    .withDetail("reason", "调度订阅流已终止，本实例不再收到任何 tick")
                    .withDetail("cause", String.valueOf(failure))
                    .build();
        }
        if (!consumer.isRunning()) {
            return Health.down()
                    .withDetail("subscription", consumer.subscriptionPath())
                    .withDetail("reason", "调度消费者未启动")
                    .build();
        }
        return Health.up().withDetail("subscription", consumer.subscriptionPath()).build();
    }
}
