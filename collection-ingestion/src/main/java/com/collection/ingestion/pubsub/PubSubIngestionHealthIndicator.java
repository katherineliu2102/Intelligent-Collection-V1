package com.collection.ingestion.pubsub;

import javax.annotation.Resource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 把 Pub/Sub 订阅流的存活并入 {@code /actuator/health}。
 *
 * <p>没有这一项时，鉴权失败（凭证缺失、scope 不足、订阅无权限）的表现是：进程存活、health 为 UP、日志里有一行 started，但订阅流早已终止，一条消息都不消费。
 * 「库里没有案件」于是既可能是上游没推，也可能是本实例根本没在收——两者在监控上无法区分，2026-08-24 的 Pilot 排查就卡在这个歧义上。
 *
 * <p>订阅流一旦终止不会自愈，因此这里判定为 DOWN 而非 OUT_OF_SERVICE：需要人工介入换凭证并重启。
 */
@Component
@ConditionalOnProperty(prefix = "collection.ingestion", name = "enabled", havingValue = "true")
public class PubSubIngestionHealthIndicator implements HealthIndicator {

    @Resource private PubSubCaseConsumer consumer;

    @Override
    public Health health() {
        Throwable failure = consumer.getFailure();
        if (failure != null) {
            return Health.down()
                    .withDetail("subscription", consumer.subscriptionPath())
                    .withDetail("reason", "订阅流已终止，本实例不再消费任何案件事件")
                    .withDetail("cause", String.valueOf(failure))
                    .build();
        }
        if (!consumer.isRunning()) {
            return Health.down()
                    .withDetail("subscription", consumer.subscriptionPath())
                    .withDetail("reason", "消费者未启动")
                    .build();
        }
        return Health.up().withDetail("subscription", consumer.subscriptionPath()).build();
    }
}
