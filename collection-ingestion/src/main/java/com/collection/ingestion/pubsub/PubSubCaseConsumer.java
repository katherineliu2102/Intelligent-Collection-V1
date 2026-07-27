package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import com.google.api.gax.batching.FlowControlSettings;
import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.protobuf.Timestamp;
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
 * B1 真实 PubSub 消费者（数据接入规格 §2）。订阅 {@code collection-cases-ai-v1-sub}（topic {@code
 * collection-cases}），按 {@code dataType} 路由 {@code case_push} / {@code repayment_push_and_load}，
 * 经 {@link CasePayloadMapper} 映射后调 {@link IngestionService} publish 领域事件。
 *
 * <p><b>门控</b>：{@code @ConditionalOnProperty(collection.ingestion.enabled=true)} —— 本地 / CI
 * （默认 false）不实例化本 bean，启动完全不依赖 GCP 凭证 / 网络。
 *
 * <p><b>ACK 语义（§2.3）</b>：处理成功（含按幂等 / 白名单 / 乱序<i>跳过</i>）→ ack；不可修复消息
 * （{@link PoisonMessageException}）→ ack + 告警（不重投毒丸）；瞬态失败（解析以外的异常，如下游
 * publish 失败）→ nack 重投（支撑 L4b-7）。幂等键仅在 publish 成功后标记（{@link IngestionDedupStore}）。
 *
 * <p>未启用 spring-cloud-gcp 自动装配：直接用 {@link Subscriber} 自建，凭证经 {@code
 * GOOGLE_APPLICATION_CREDENTIALS}（ADC）加载。
 */
@Component
@ConditionalOnProperty(prefix = "collection.ingestion", name = "enabled", havingValue = "true")
public class PubSubCaseConsumer implements SmartLifecycle, MessageReceiver {

    private static final Logger log = LoggerFactory.getLogger(PubSubCaseConsumer.class);

    private static final String DATA_TYPE_CASE_PUSH = "case_push";
    private static final String DATA_TYPE_REPAYMENT = "repayment_push_and_load";

    @Resource private IngestionProperties props;
    @Resource private IngestionService ingestionService;
    @Resource private CasePayloadMapper mapper;
    @Resource private IngestionDedupStore dedup;

    private volatile Subscriber subscriber;
    private volatile boolean running;

    @Override
    public void start() {
        if (running) {
            return;
        }
        if (StringUtils.isBlank(props.getProjectId()) || StringUtils.isBlank(props.getSubscription())) {
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
                                        .setExecutorThreadCount(Math.max(1, props.getMaxConcurrency()))
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
            JSONObject json = parse(message);
            String attrDataType = message.getAttributesOrDefault("dataType", null);
            String dataType = mapper.dataType(json, attrDataType);
            String bizMsgId = mapper.messageId(json, pubsubMsgId);
            long publishMillis = toMillis(message.getPublishTime());
            route(dataType, json, bizMsgId, publishMillis);
            reply.ack();
        } catch (PoisonMessageException e) {
            log.warn("[Ingestion] poison message ack+skip msgId={}: {}", pubsubMsgId, e.getMessage());
            reply.ack();
        } catch (Exception e) {
            log.error("[Ingestion] 处理失败 nack 重投 msgId={}: {}", pubsubMsgId, e.toString());
            reply.nack();
        }
    }

    private void route(String dataType, JSONObject json, String bizMsgId, long publishMillis) {
        if (DATA_TYPE_CASE_PUSH.equals(dataType)) {
            handleCasePush(json, bizMsgId, publishMillis);
        } else if (DATA_TYPE_REPAYMENT.equals(dataType)) {
            handleRepayment(json, bizMsgId);
        } else {
            log.debug("[Ingestion] 未知 dataType={} ack 跳过", dataType);
        }
    }

    private void handleCasePush(JSONObject json, String bizMsgId, long publishMillis) {
        if (dedup.isMessageProcessed(bizMsgId)) {
            log.debug("[Ingestion] case_push 重复消息 msgId={} 跳过", bizMsgId);
            return;
        }
        CasePayloadMapper.CaseIngest ci = mapper.mapCasePush(json);
        if (dedup.isStale(ci.caseId, publishMillis)) {
            log.info("[Ingestion] case_push 乱序旧消息 caseId={} 跳过", ci.caseId);
            return;
        }
        if (!props.whitelisted(ci.caseId)) {
            log.info("[Ingestion] case_push caseId={} 不在白名单，ack 跳过", ci.caseId);
            return;
        }
        if (dedup.isIngested(ci.caseId)) {
            dedup.recordSeen(ci.caseId, publishMillis);
            log.info("[Ingestion] case_push caseId={} 本周期已入催，跳过（阶段变靠日切）", ci.caseId);
            return;
        }
        ingestionService.ingestCase(ci.caseId, ci.userId, ci.stage, ci.snapshotFields);
        dedup.markIngested(ci.caseId);
        dedup.recordSeen(ci.caseId, publishMillis);
        dedup.markMessageProcessed(bizMsgId);
    }

    private void handleRepayment(JSONObject json, String bizMsgId) {
        if (dedup.isMessageProcessed(bizMsgId)) {
            log.debug("[Ingestion] repayment 重复消息 msgId={} 跳过", bizMsgId);
            return;
        }
        Long userId = mapper.repaymentUserId(json);
        ingestionService.repayment(userId);
        if (mapper.fullySettled(json)) {
            Long loanId = mapper.repaymentLoanId(json);
            if (loanId != null) {
                dedup.clearIngested(loanId);
                log.info("[Ingestion] 全额结清 DEL ingested loanId={}", loanId);
            }
        }
        dedup.markMessageProcessed(bizMsgId);
    }

    private JSONObject parse(PubsubMessage message) {
        String body = message.getData().toStringUtf8();
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

    private static long toMillis(Timestamp ts) {
        return ts.getSeconds() * 1000L + ts.getNanos() / 1_000_000L;
    }
}
