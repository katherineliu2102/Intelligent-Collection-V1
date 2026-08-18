package com.collection.ingestion.pubsub;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.collection.ingestion.config.IngestionProperties;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.pubsub.v1.PubsubMessage;
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
        String body = "{\"dataType\":\"caseEvent\",\"data\":{\"eventId\":\"evt-1\",\"caseId\":525441}}";
        PubsubMessage message = message(null, body);
        AckReplyConsumer reply = mock(AckReplyConsumer.class);

        consumer.receiveMessage(message, reply);

        ArgumentCaptor<JSONObject> payload = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).handleCaseEvent(payload.capture(), eq(body));
        org.junit.jupiter.api.Assertions.assertEquals(525441L, payload.getValue().getLong("caseId"));
        verify(reply).ack();
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
