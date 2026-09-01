package com.collection.ingestion.config;

import java.util.Arrays;
import java.util.List;
import javax.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 联调隔离启动闸门（测试 SSOT T0-3 / T0-4）。仅在 {@code collection.ingestion.enabled=true} 时实例化， 且只约束 local /
 * test profile —— Pilot 由 {@code PilotReadinessValidator} 单独把关。
 *
 * <p>拦两类事故，均在 {@link com.collection.ingestion.pubsub.PubSubCaseConsumer} 建立订阅之前失败：
 *
 * <ol>
 *   <li><b>抢生产订阅</b>：配到 {@code collection.ingestion.reserved-subscriptions} 里的订阅名。Pub/Sub
 *       同一订阅的消息只投给一个消费者，联调进程一旦接上去就是从生产消费者嘴里抢消息。隔离验证必须用 自建订阅（正式 topic 上的独立订阅拿的是消息副本，互不影响）。
 *   <li><b>空白名单</b>：空名单在 {@link IngestionProperties#whitelisted} 语义下等于放行全部。若订阅承载
 *       真实数仓流量，就会对全量真实案件建计划并触达。联调必须显式给出批准的 loan_id。
 * </ol>
 */
@Component
@ConditionalOnProperty(prefix = "collection.ingestion", name = "enabled", havingValue = "true")
public class IngestionIsolationGuard {

    private static final Logger log = LoggerFactory.getLogger(IngestionIsolationGuard.class);
    private static final List<String> GUARDED_PROFILES = Arrays.asList("local", "test");

    private final IngestionProperties props;
    private final Environment environment;

    public IngestionIsolationGuard(IngestionProperties props, Environment environment) {
        this.props = props;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (!isGuardedProfile()) {
            return;
        }
        String subscription = StringUtils.trimToEmpty(props.getSubscription());
        if (props.getReservedSubscriptions() != null
                && props.getReservedSubscriptions().contains(subscription)) {
            throw new IllegalStateException(
                    "拒绝启动：profile="
                            + String.join(",", environment.getActiveProfiles())
                            + " 配到了保留订阅 "
                            + subscription
                            + "。同一订阅的消息只会投给一个消费者，联调接上去会抢走生产消息；"
                            + "请改用自建隔离订阅（见 scripts/test/provision-l4-pubsub.py）。");
        }
        if (props.getLoanIdWhitelist() == null || props.getLoanIdWhitelist().isEmpty()) {
            throw new IllegalStateException(
                    "拒绝启动：collection.ingestion.enabled=true 但 loan-id-whitelist 为空，"
                            + "空名单等于放行全部案件；联调必须显式配置批准的 loan_id。");
        }
        log.info(
                "[Ingestion] 隔离闸门通过 — subscription={} 白名单 {} 个 loan_id",
                subscription,
                props.getLoanIdWhitelist().size());
    }

    private boolean isGuardedProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (GUARDED_PROFILES.contains(profile)) {
                return true;
            }
        }
        return false;
    }
}
