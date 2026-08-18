package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.collection.ingestion.config.IngestionProperties;
import com.google.api.gax.batching.FlowControlSettings;
import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private volatile Subscriber subscriber;
    private volatile boolean running;

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
        subscriber.startAsync().awaitRunning();
        running = true;
        log.info(
                "[Ingestion] PubSub consumer started — subscription={} maxConcurrency={}",
                subscriptionName,
                props.getMaxConcurrency());
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
        try {
            String body = message.getData().toStringUtf8();
            JSONObject json = parse(body);
            String dataType =
                    message.getAttributesOrDefault("dataType", json.getString("dataType"));
            route(dataType, payload(json), body);
            reply.ack();
        } catch (PoisonMessageException e) {
            log.warn(
                    "[Ingestion] poison message ack+skip msgId={}: {}",
                    pubsubMsgId,
                    e.getMessage());
            reply.ack();
        } catch (Exception e) {
            log.error("[Ingestion] 处理失败 nack 重投 msgId={}: {}", pubsubMsgId, e.toString());
            reply.nack();
        }
    }

    private void route(String dataType, JSONObject json, String rawPayload) {
        if (DATA_TYPE_CASE_EVENT.equals(dataType)) {
            processor.handleCaseEvent(json, rawPayload);
        } else if (DATA_TYPE_REPAYMENT_EVENT.equals(dataType)) {
            processor.handleRepaymentEvent(json, rawPayload);
        } else {
            log.warn("[Ingestion] 不支持的 dataType={}，ack 跳过", dataType);
        }
    }

    /** 每条 Pub/Sub 消息的业务体固定在顶层 data 中；兼容历史平铺 body。 */
    private JSONObject payload(JSONObject outer) {
        if (!outer.containsKey("data")) {
            return outer;
        }
        JSONObject data = outer.getJSONObject("data");
        if (data == null) {
            throw new PoisonMessageException("消息 data 必须为 JSON object");
        }
        return data;
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
