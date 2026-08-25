package com.collection.ingestion.pubsub;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.alibaba.fastjson.JSONObject;
import com.collection.ingestion.config.IngestionProperties;
import com.collection.ingestion.metrics.IngestionMetrics;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.pubsub.v1.PubsubMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 两类 v3 Pub/Sub 路由与 ACK/NACK 语义。 */
class PubSubCaseConsumerTest {

    private PubSubCaseConsumer consumer;
    private AiCaseIngestionProcessor processor;

    @BeforeEach
    void setUp() {
        consumer = new PubSubCaseConsumer();
        processor = mock(AiCaseIngestionProcessor.class);
        ReflectionTestUtils.setField(consumer, "props", new IngestionProperties());
        ReflectionTestUtils.setField(consumer, "processor", processor);
        ReflectionTestUtils.setField(
                consumer, "metrics", new IngestionMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void caseEvent_routesAndAcksAfterProcessorReturns() {
        PubsubMessage message = message("caseEvent", "{\"eventId\":\"evt-1\"}");
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verify(processor).handleCaseEvent(any(), eq("{\"eventId\":\"evt-1\"}"));
        verify(reply).ack();
    }

    @Test
    void envelopeBody_routesInnerDataAndRetainsRawPayload() {
        String body =
                "{\"dataType\":\"caseEvent\",\"data\":{\"eventId\":\"evt-1\",\"caseId\":525441}}";
        PubsubMessage message = message(null, body);
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        ArgumentCaptor<JSONObject> payload = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).handleCaseEvent(payload.capture(), eq(body));
        org.junit.jupiter.api.Assertions.assertEquals(
                525441L, payload.getValue().getLong("caseId"));
        verify(reply).ack();
    }

    /**
     * data 是标量而不是 object：必须 ack + 告警，不能 nack。
     *
     * <p>fastjson 的 {@code getJSONObject} 对字符串值会把它当 JSON 文本再解析一次并抛 {@code JSONException}， 该异常一旦逃出
     * poison 判定就会落进通用 catch 变成 nack，让一条永远处理不成的消息被反复重投直到耗尽 投递次数进 DLQ（2026-08-21 L4b-14 实测 5 次）。
     */
    @Test
    void nonObjectData_isPoisonAndAcked() {
        PubsubMessage message = message("caseEvent", "{\"dataType\":\"caseEvent\",\"data\":\"x\"}");
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verify(processor, never()).handleCaseEvent(any(), any());
        verify(reply).ack();
        verify(reply, never()).nack();
    }

    /** data 显式为 null 同样是不可恢复的契约错误。 */
    @Test
    void nullData_isPoisonAndAcked() {
        PubsubMessage message = message("caseEvent", "{\"dataType\":\"caseEvent\",\"data\":null}");
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verify(processor, never()).handleCaseEvent(any(), any());
        verify(reply).ack();
        verify(reply, never()).nack();
    }

    @Test
    void transientProcessorFailure_nacks() {
        doThrow(new IllegalStateException("db unavailable"))
                .when(processor)
                .handleRepaymentEvent(any(), any());
        PubsubMessage message = message("repaymentEvent", "{\"eventId\":\"evt-2\"}");
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verify(reply).nack();
    }

    @Test
    void unsupportedLegacyType_isAcknowledgedWithoutProcessorCall() {
        PubsubMessage message = message("case_push", "{\"dataType\":\"case_push\"}");
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verify(reply).ack();
    }

    @Test
    void caseOutsideWhitelist_isAckedWithoutProcessorCall() {
        whitelist(99000000L, 99000001L);
        PubsubMessage message = message("caseEvent", "{\"eventId\":\"evt-3\",\"caseId\":525441}");
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verifyNoInteractions(processor);
        verify(reply).ack();
        verify(reply, never()).nack();
    }

    @Test
    void repaymentOutsideWhitelist_isAckedWithoutProcessorCall() {
        whitelist(99000000L);
        PubsubMessage message =
                message("repaymentEvent", "{\"eventId\":\"evt-4\",\"caseId\":525441}");
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verifyNoInteractions(processor);
        verify(reply).ack();
    }

    @Test
    void caseInsideWhitelist_isProcessed() {
        whitelist(99000000L, 99000001L);
        String body = "{\"eventId\":\"evt-5\",\"caseId\":99000001}";
        PubsubMessage message = message("caseEvent", body);
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verify(processor).handleCaseEvent(any(), eq(body));
        verify(reply).ack();
    }

    /** caseId 为字符串时同样参与名单判定，避免数仓改类型就绕过隔离。 */
    @Test
    void stringCaseId_isMatchedAgainstWhitelist() {
        whitelist(99000000L);
        PubsubMessage message =
                message("caseEvent", "{\"eventId\":\"evt-6\",\"caseId\":\"525441\"}");
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verifyNoInteractions(processor);
        verify(reply).ack();
    }

    /** 名单生效但 caseId 缺失时不得静默吞掉：放行到映射层按 poison 处置。 */
    @Test
    void missingCaseId_isPassedToProcessorEvenWhenWhitelistActive() {
        whitelist(99000000L);
        String body = "{\"eventId\":\"evt-7\"}";
        PubsubMessage message = message("caseEvent", body);
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verify(processor).handleCaseEvent(any(), eq(body));
        verify(reply).ack();
    }

    @Test
    void emptyWhitelist_allowsEveryCase() {
        String body = "{\"eventId\":\"evt-8\",\"caseId\":525441}";
        PubsubMessage message = message("caseEvent", body);
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        verify(processor).handleCaseEvent(any(), eq(body));
        verify(reply).ack();
    }

    private void whitelist(Long... loanIds) {
        IngestionProperties props = new IngestionProperties();
        props.setLoanIdWhitelist(Arrays.asList(loanIds));
        ReflectionTestUtils.setField(consumer, "props", props);
    }

    private PubsubMessage message(String dataType, String body) {
        PubsubMessage.Builder builder =
                PubsubMessage.newBuilder()
                        .setMessageId("pubsub-id")
                        .setData(com.google.protobuf.ByteString.copyFromUtf8(body));
        if (dataType != null) {
            builder.putAttributes("dataType", dataType);
        }
        return builder.build();
    }
}
