package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.collection.common.event.CollectionEvent;
import com.collection.ingestion.config.IngestionProperties;
import com.collection.ingestion.metrics.IngestionMetrics;
import com.google.api.core.ApiService;
import com.google.api.gax.batching.FlowControlSettings;
import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * B1 真实 PubSub 消费者（数据接入规格 §2）。订阅 {@code intelligent-collection-cases-v1-sub}（topic {@code
 * intelligent-collection-cases-v1}），按 {@code dataType} 路由 {@code caseEvent} / {@code
 * repaymentEvent}， 交由 {@link AiCaseIngestionProcessor} 写投影并发布领域事件。
 *
 * <p><b>门控</b>：{@code @ConditionalOnProperty(collection.ingestion.enabled=true)} —— 本地 / CI （默认
 * false）不实例化本 bean，启动完全不依赖 GCP 凭证 / 网络。
 *
 * <p><b>ACK 语义（§2.3）</b>：处理成功（含按幂等 / 白名单 / 乱序<i>跳过</i>）→ ack；不可修复消息 （{@link
 * PoisonMessageException}，含违反契约的外部阶段/停催事件）→ ack + 告警（不重投毒丸）；瞬态失败（解析以外的异常，如投影写入或下游 publish 失败）→ nack
 * 重投（支撑 L4b-7）。ack 只发生在投影事务已提交、领域事件已发布之后。
 *
 * <p>未启用 spring-cloud-gcp 自动装配：直接用 {@link Subscriber} 自建，凭证经 {@code
 * GOOGLE_APPLICATION_CREDENTIALS}（ADC）加载。
 */
@Component
@ConditionalOnProperty(prefix = "collection.ingestion", name = "enabled", havingValue = "true")
public class PubSubCaseConsumer implements SmartLifecycle, MessageReceiver {

    private static final Logger log = LoggerFactory.getLogger(PubSubCaseConsumer.class);

    private static final String DATA_TYPE_CASE_EVENT = "caseEvent";
    private static final String DATA_TYPE_REPAYMENT_EVENT = "repaymentEvent";

    @Resource private IngestionProperties props;
    @Resource private AiCaseIngestionProcessor processor;
    @Resource private IngestionMetrics metrics;

    private volatile Subscriber subscriber;
    private volatile boolean running;
    private volatile Throwable failure;

    @Override
    public void start() {
        if (running) {
            return;
        }
        if (StringUtils.isBlank(props.getProjectId())
                || StringUtils.isBlank(props.getSubscription())) {
            throw new IllegalStateException(
                    "collection.ingestion.enabled=true 但 projectId/subscription 未配置"
                            + "（映射 GCP_PUBSUB_PROJECT / GCP_PUBSUB_SUBSCRIPTION）");
        }
        ProjectSubscriptionName subscriptionName =
                ProjectSubscriptionName.of(props.getProjectId(), props.getSubscription());
        FlowControlSettings flowControl =
                FlowControlSettings.newBuilder()
                        .setMaxOutstandingElementCount((long) props.getMaxConcurrency())
                        .build();
        this.subscriber =
                Subscriber.newBuilder(subscriptionName, this)
                        .setParallelPullCount(1)
                        .setFlowControlSettings(flowControl)
                        .setExecutorProvider(
                                InstantiatingExecutorProvider.newBuilder()
                                        .setExecutorThreadCount(
                                                Math.max(1, props.getMaxConcurrency()))
                                        .build())
                        .build();
        // awaitRunning() 只等到本地 Subscriber 就绪，拉流的鉴权失败发生在其后：凭证或 scope 不对时
        // 这里照样返回成功、日志照样打印 started，而流已经终止，进程活着但一条都不消费。
        // 2026-08-24 在 Pilot 机实测到该状态下 /actuator/health 仍是 UP——「没收到案件」与「上游没推」
        // 从外部完全无法区分。故把失败原因记下来，由 PubSubIngestionHealthIndicator 暴露成 DOWN。
        subscriber.addListener(
                new ApiService.Listener() {
                    @Override
                    public void failed(ApiService.State from, Throwable cause) {
                        failure = cause;
                        log.error("[Ingestion] PubSub 订阅流终止（此后不再消费任何消息） from={}", from, cause);
                    }
                },
                MoreExecutors.directExecutor());
        subscriber.startAsync().awaitRunning();
        running = true;
        failure = null;
        log.info(
                "[Ingestion] PubSub consumer started — subscription={} maxConcurrency={}",
                subscriptionName,
                props.getMaxConcurrency());
    }

    /** 供健康检查判定：订阅流是否已终止。null 表示正常。 */
    public Throwable getFailure() {
        return failure;
    }

    public String subscriptionPath() {
        return props.getProjectId() + "/" + props.getSubscription();
    }

    @Override
    public void stop() {
        running = false;
        if (subscriber != null) {
            try {
                subscriber.stopAsync().awaitTerminated(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[Ingestion] PubSub consumer stop 超时/异常: {}", e.getMessage());
            }
            subscriber = null;
            log.info("[Ingestion] PubSub consumer stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void receiveMessage(PubsubMessage message, AckReplyConsumer reply) {
        String pubsubMsgId = message.getMessageId();
        String dataType = "unknown";
        try {
            String body = message.getData().toStringUtf8();
            JSONObject json = parse(body);
            dataType = message.getAttributesOrDefault("dataType", json.getString("dataType"));
            JSONObject payload = payload(json);
            putMdc(payload);
            String outcome = route(dataType, payload, body);
            reply.ack();
            metrics.ack(dataType, outcome);
        } catch (PoisonMessageException e) {
            log.warn(
                    "[Ingestion] poison message ack+skip msgId={}: {}",
                    pubsubMsgId,
                    e.getMessage());
            reply.ack();
            metrics.poison(dataType);
        } catch (Exception e) {
            log.error("[Ingestion] 处理失败 nack 重投 msgId={}: {}", pubsubMsgId, e.toString());
            reply.nack();
            metrics.nack(dataType);
        } finally {
            MDC.remove("eventId");
            MDC.remove("caseId");
        }
    }

    private String route(String dataType, JSONObject json, String rawPayload) {
        if (!withinWhitelist(json)) {
            return "WHITELIST_SKIPPED";
        }
        if (DATA_TYPE_CASE_EVENT.equals(dataType)) {
            processor.handleCaseEvent(json, rawPayload);
            return "PROCESSED";
        } else if (DATA_TYPE_REPAYMENT_EVENT.equals(dataType)) {
            processor.handleRepaymentEvent(json, rawPayload);
            return "PROCESSED";
        } else {
            log.warn("[Ingestion] 不支持的 dataType={}，ack 跳过", dataType);
            return "UNKNOWN_TYPE";
        }
    }

    private void putMdc(JSONObject json) {
        String eventId = json.getString("eventId");
        if (StringUtils.isNotBlank(eventId)) {
            MDC.put("eventId", eventId);
        }
        Long caseId = caseId(json);
        if (caseId != null) {
            MDC.put("caseId", String.valueOf(caseId));
        }
    }

    /**
     * 联调隔离（数据接入规格 §6.1）：不在 {@code collection.ingestion.loan-id-whitelist} 内的案件不进入任何处理，由调用方 ack
     * 跳过；空名单放行全部。
     *
     * <p>取不到 {@code caseId} 时放行，交由映射层按 poison 处置，避免在此吞掉契约错误。
     */
    private boolean withinWhitelist(JSONObject json) {
        Long caseId = caseId(json);
        if (caseId == null || props.whitelisted(caseId)) {
            return true;
        }
        log.info("[Ingestion] 案件不在白名单，ack 跳过 caseId={}", caseId);
        return false;
    }

    private Long caseId(JSONObject json) {
        Object raw = json.get(CollectionEvent.CASE_ID);
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 每条 Pub/Sub 消息的业务体固定在顶层 data 中；兼容历史平铺 body。 */
    private JSONObject payload(JSONObject outer) {
        if (!outer.containsKey("data")) {
            return outer;
        }
        // 不能用 getJSONObject：值为字符串时 fastjson 会把它当 JSON 文本再解析一遍并抛 JSONException，
        // 该异常逃出 poison 判定、落到消费者的通用 catch 里变成 nack，于是一条永远处理不成的消息
        // 被反复重投直到耗尽投递次数进 DLQ（2026-08-21 L4b-14 实测 5 次）。契约要求这类不可恢复错误
        // ack + 告警，故先判类型再取值。
        Object data = outer.get("data");
        if (data == null) {
            throw new PoisonMessageException("消息 data 必须为 JSON object，实际为 null");
        }
        if (!(data instanceof JSONObject)) {
            throw new PoisonMessageException(
                    "消息 data 必须为 JSON object，实际类型=" + data.getClass().getSimpleName());
        }
        return (JSONObject) data;
    }

    private JSONObject parse(String body) {
        if (StringUtils.isBlank(body)) {
            throw new PoisonMessageException("空消息体");
        }
        JSONObject json;
        try {
            json = JSON.parseObject(body);
        } catch (Exception e) {
            throw new PoisonMessageException("JSON 解析失败: " + e.getMessage());
        }
        if (json == null) {
            throw new PoisonMessageException("JSON 解析为空");
        }
        return json;
    }
}
