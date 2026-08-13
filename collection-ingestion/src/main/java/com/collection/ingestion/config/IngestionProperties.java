package com.collection.ingestion.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据接入（B1）配置绑定，prefix = {@code collection.ingestion}（数据接入规格 §2.1）。
 *
 * <p>GCP 连接信息（project / subscription / 凭证）走环境变量，<b>不入仓</b>：YAML 中以 {@code ${GCP_PUBSUB_PROJECT:}} /
 * {@code ${GCP_PUBSUB_SUBSCRIPTION:}} 映射；凭证由 {@code GOOGLE_APPLICATION_CREDENTIALS} 经 ADC 自动加载。
 *
 * <p>{@link #enabled} 默认 {@code false}：本地 / CI 不启动 PubSub 消费（{@link
 * com.collection.ingestion.pubsub.PubSubCaseConsumer} 受 {@code @ConditionalOnProperty} 门控）， 联调 /
 * 生产置 {@code true}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.ingestion")
public class IngestionProperties {

    /** 是否启动真实 PubSub 消费。本地 / CI false；联调 / 生产 true。 */
    private boolean enabled = false;

    /** GCP 项目（映射环境变量 {@code GCP_PUBSUB_PROJECT}）。 */
    private String projectId;

    /** 订阅短名（映射 {@code GCP_PUBSUB_SUBSCRIPTION}；生产 {@code collection-ai-events-v1-sub}，联调 {@code collection-ai-events-test1-sub}）。 */
    private String subscription;

    /** ACK deadline（秒），默认 60（§2.1）。 */
    private int ackDeadlineSeconds = 60;

    /** 并发消费度，默认 4（§2.1），用作 flow-control 最大未确认条数与拉取线程数。 */
    private int maxConcurrency = 4;

    /** 仅处理名单内 loan_id；空 = 全量（§6.1 联调隔离，名单不入仓）。 */
    private List<Long> loanIdWhitelist = new ArrayList<>();

    /** 白名单为空时是否启用旧库全量日切；默认关闭，避免未经审批扫描全表。 */
    private boolean dailyRollFullScanEnabled = false;

    /** 全量日切每次调度触发最多处理的 keyset 页大小。 */
    private int dailyRollBatchSize = 1000;

    /**
     * 是否允许 L4b-7 受控 NACK 故障注入。**联调专用，生产必须 false**；为 true 时仍只能对白名单 loan_id 生效， 且需显式调用 {@code POST
     * /mock/ingestion-fault/arm} 才会失败一次。
     */
    private boolean faultInjectionEnabled = false;

    /** loan_id 是否在白名单内（空名单视为放行全部）。 */
    public boolean whitelisted(Long loanId) {
        return loanIdWhitelist == null
                || loanIdWhitelist.isEmpty()
                || (loanId != null && loanIdWhitelist.contains(loanId));
    }

}
