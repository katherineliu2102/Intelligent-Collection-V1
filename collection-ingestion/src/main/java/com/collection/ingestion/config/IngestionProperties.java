package com.collection.ingestion.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据接入（B1）配置绑定，prefix = {@code collection.ingestion}（数据接入规格 §2.1）。
 *
 * <p>GCP 连接信息（project / subscription / 凭证）走环境变量，<b>不入仓</b>：YAML 中以
 * {@code ${GCP_PUBSUB_PROJECT:}} / {@code ${GCP_PUBSUB_SUBSCRIPTION:}} 映射；凭证由
 * {@code GOOGLE_APPLICATION_CREDENTIALS} 经 ADC 自动加载。
 *
 * <p>{@link #enabled} 默认 {@code false}：本地 / CI 不启动 PubSub 消费（{@link
 * com.collection.ingestion.pubsub.PubSubCaseConsumer} 受 {@code @ConditionalOnProperty} 门控），
 * 联调 / 生产置 {@code true}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.ingestion")
public class IngestionProperties {

    /** 是否启动真实 PubSub 消费。本地 / CI false；联调 / 生产 true。 */
    private boolean enabled = false;

    /** GCP 项目（映射环境变量 {@code GCP_PUBSUB_PROJECT}）。 */
    private String projectId;

    /** 订阅短名（映射 {@code GCP_PUBSUB_SUBSCRIPTION}，定稿 {@code collection-cases-ai-v1-sub}）。 */
    private String subscription;

    /** ACK deadline（秒），默认 60（§2.1）。 */
    private int ackDeadlineSeconds = 60;

    /** 并发消费度，默认 4（§2.1），用作 flow-control 最大未确认条数与拉取线程数。 */
    private int maxConcurrency = 4;

    /** 仅处理名单内 loan_id；空 = 全量（§6.0 联调隔离，名单不入仓）。 */
    private List<Long> loanIdWhitelist = new ArrayList<>();

    /** 消息缺 jpushToken 时是否查新库 {@code t_user_device_token} 补全（B3，Phase 1 默认关）。 */
    private boolean enrichJpushToken = false;

    private CasePush casePush = new CasePush();

    /** loan_id 是否在白名单内（空名单视为放行全部）。 */
    public boolean whitelisted(Long loanId) {
        return loanIdWhitelist == null
                || loanIdWhitelist.isEmpty()
                || (loanId != null && loanIdWhitelist.contains(loanId));
    }

    /**
     * case_push 报文解析约定。上游 JSON key 与契约（领域模型 §6.2）不一致时，用 {@link #fieldMap}
     * 把<b>语义字段</b>映射到上游实际 key；未配置则按同名取值（C-I-01 待信贷联调）。
     */
    @Data
    public static class CasePush {
        /** 消息类型字段（先读 PubSub attribute，缺失再读 JSON 此字段）。 */
        private String dataTypeField = "dataType";

        /** 业务 message_id 字段（缺失则回退 PubSub messageId，用于去重）。 */
        private String messageIdField = "messageId";

        /** 语义字段 → 上游 JSON key 的别名表；缺省同名（契约 §6.2 key）。 */
        private Map<String, String> fieldMap = new HashMap<>();
    }
}
