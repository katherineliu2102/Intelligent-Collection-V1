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

import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
class NotificationPushAdapterTest {

    private NotificationPushAdapter pushAdapter;
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

        NotificationSmsAdapter smsAdapter = new NotificationSmsAdapter();
        ReflectionTestUtils.setField(smsAdapter, "properties", properties);
        ReflectionTestUtils.setField(smsAdapter, "notificationClient", client);

        pushAdapter = new NotificationPushAdapter();
        ReflectionTestUtils.setField(pushAdapter, "properties", properties);
        ReflectionTestUtils.setField(pushAdapter, "notificationClient", client);
        ReflectionTestUtils.setField(pushAdapter, "notificationSmsAdapter", smsAdapter);
    }

    private StepCommand pushCommand(String token, String fallbackPhone) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_TITLE, "MOCASA Payment Reminder");
        meta.put(StepCommand.META_BODY, "Your payment is due.");
        meta.put(StepCommand.META_PUSH_DATA, "{\"scene\":\"collection\"}");
        meta.put(StepCommand.META_SMS_BODY, "[Fallback] please pay");
        if (fallbackPhone != null) {
            meta.put("fallbackPhone", fallbackPhone);
        }
        return StepCommand.builder()
                .channelType(ChannelType.PUSH)
                .targetAddress(token)
                .idempotencyKey("1:1:0")
                .metadata(meta)
                .build();
    }

    @Test
    void enqueueSuccessNoProviderMsgId() {
        stubFor(post(urlEqualTo("/v1/app_notification/send"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"msg\":\"success\"}")));

        StepResult result = pushAdapter.send(pushCommand("jpush-reg-id-abc", null));
        assertTrue(result.isSuccess());
        assertNull(result.getProviderMsgId());
        assertFalse(result.isRetryable());
    }

    @Test
    void noTokenFallbackToSms() {
        stubFor(post(urlEqualTo("/v1/sms/send"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"msg\":\"success\",\"data\":{\"requestSuccess\":true,\"channel\":\"QHSms\",\"requestId\":\"fb-1\"}}")));

        // token 占位为手机号且 fallbackPhone 一致 → 走 SMS fallback
        StepResult result = pushAdapter.send(pushCommand("639171234567", "639171234567"));
        assertTrue(result.isSuccess());
        assertEquals("fb-1", result.getProviderMsgId());
        verify(postRequestedFor(urlEqualTo("/v1/sms/send")));
    }

    @Test
    void invalidProviderFailed() {
        stubFor(post(urlEqualTo("/v1/app_notification/send"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":3001,\"msg\":\"invalid provider\"}")));

        StepResult result = pushAdapter.send(pushCommand("jpush-reg-id-abc", null));
        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertEquals("NOTIFICATION_INVALID_PROVIDER", result.getErrorCode());
    }

    @Test
    void transient5xxRetryable() {
        stubFor(post(urlEqualTo("/v1/app_notification/send")).willReturn(aResponse().withStatus(503)));

        StepResult result = pushAdapter.send(pushCommand("jpush-reg-id-abc", null));
        assertFalse(result.isSuccess());
        assertTrue(result.isRetryable());
        assertEquals("NOTIFICATION_TIMEOUT", result.getErrorCode());
    }

    @Test
    void syncModeHitsSyncEndpointAndReturnsRequestId() {
        properties.getNotification().setPushSyncMode(true);
        stubFor(post(urlEqualTo("/v1/app_notification/sync/send"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"msg\":\"success\",\"data\":{\"requestSuccess\":true,\"channel\":\"JPush\",\"requestId\":\"jp-1\"}}")));

        StepResult result = pushAdapter.send(pushCommand("jpush-reg-id-abc", null));
        assertTrue(result.isSuccess());
        assertEquals("jp-1", result.getProviderMsgId());
        verify(postRequestedFor(urlEqualTo("/v1/app_notification/sync/send")));
    }

    @Test
    void syncModeRejectedWhenRequestSuccessFalse() {
        properties.getNotification().setPushSyncMode(true);
        stubFor(post(urlEqualTo("/v1/app_notification/sync/send"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"msg\":\"success\",\"data\":{\"requestSuccess\":false}}")));

        StepResult result = pushAdapter.send(pushCommand("jpush-reg-id-abc", null));
        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertEquals("NOTIFICATION_PUSH_REJECTED", result.getErrorCode());
    }
}
