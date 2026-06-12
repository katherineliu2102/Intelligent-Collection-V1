package com.collection.channel.adapter;

import com.collection.channel.client.NotificationClient;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
class NotificationSmsAdapterTest {

    private NotificationSmsAdapter adapter;
    private ChannelProperties properties;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        properties = new ChannelProperties();
        properties.getNotification().setBaseUrl(wm.getHttpBaseUrl());
        properties.getNotification().setAppCode("mocasa");
        properties.getNotification().setAppKey("test-key");

        RestTemplate restTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        NotificationClient client = new NotificationClient();
        ReflectionTestUtils.setField(client, "properties", properties);
        ReflectionTestUtils.setField(client, "channelRestTemplate", restTemplate);

        adapter = new NotificationSmsAdapter();
        ReflectionTestUtils.setField(adapter, "properties", properties);
        ReflectionTestUtils.setField(adapter, "notificationClient", client);
    }

    private StepCommand smsCommand() {
        return StepCommand.builder()
                .channelType(ChannelType.SMS)
                .targetAddress("+639171234567")
                .idempotencyKey("1:1:0")
                .metadata(new HashMap<>(Collections.singletonMap(StepCommand.META_SMS_BODY, "Hello test")))
                .build();
    }

    @Test
    void sendSuccess() {
        stubFor(post(urlEqualTo("/v1/sms/send"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"msg\":\"success\",\"data\":{\"requestSuccess\":true,\"channel\":\"QHSms\",\"requestId\":\"req-123\"}}")));

        StepResult result = adapter.send(smsCommand());
        assertTrue(result.isSuccess());
        assertEquals("req-123", result.getProviderMsgId());
        assertFalse(result.isRetryable());
    }

    @Test
    void rejectedNoAccount() {
        stubFor(post(urlEqualTo("/v1/sms/send"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":2001,\"msg\":\"no available account\"}")));

        StepResult result = adapter.send(smsCommand());
        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertEquals("NOTIFICATION_NO_ACCOUNT", result.getErrorCode());
    }

    @Test
    void requestSuccessFalseRejected() {
        stubFor(post(urlEqualTo("/v1/sms/send"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"msg\":\"success\",\"data\":{\"requestSuccess\":false}}")));

        StepResult result = adapter.send(smsCommand());
        assertFalse(result.isSuccess());
        assertEquals("NOTIFICATION_SMS_REJECTED", result.getErrorCode());
    }

    @Test
    void transient5xxRetryable() {
        stubFor(post(urlEqualTo("/v1/sms/send")).willReturn(aResponse().withStatus(503)));

        StepResult result = adapter.send(smsCommand());
        assertFalse(result.isSuccess());
        assertTrue(result.isRetryable());
        assertEquals("NOTIFICATION_TIMEOUT", result.getErrorCode());
    }

    @Test
    void notConfigured() {
        properties.getNotification().setBaseUrl("");
        StepResult result = adapter.send(smsCommand());
        assertFalse(result.isSuccess());
        assertEquals("NOTIFICATION_NOT_CONFIGURED", result.getErrorCode());
    }

    @Test
    void testModeHitsTestSendWithoutSign() {
        properties.getNotification().setSmsTestMode(true);
        properties.getNotification().setAppKey("");   // 测试端点免签名，appKey 可空
        stubFor(post(urlEqualTo("/v1/sms/testSend"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"msg\":\"success\",\"data\":{\"requestSuccess\":true,\"channel\":\"QHSms\",\"requestId\":\"test-1\"}}")));

        StepResult result = adapter.send(smsCommand());
        assertTrue(result.isSuccess());
        assertEquals("test-1", result.getProviderMsgId());
        // 走 testSend，不应携带 sign 字段
        verify(postRequestedFor(urlEqualTo("/v1/sms/testSend"))
                .withRequestBody(matchingJsonPath("$.appCode", equalTo("mocasa")))
                .withRequestBody(matchingJsonPath("$.contentType", equalTo("collection")))
                .withRequestBody(notMatching(".*\\\"sign\\\".*")));
    }
}
