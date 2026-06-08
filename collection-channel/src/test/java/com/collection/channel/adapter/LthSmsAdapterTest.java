package com.collection.channel.adapter;

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
class LthSmsAdapterTest {

    private LthSmsAdapter adapter;
    private ChannelProperties properties;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        properties = new ChannelProperties();
        properties.getLth().getSms().setUrl(wm.getHttpBaseUrl() + "/send");
        properties.getLth().getSms().setSenderId("MOCASA");

        adapter = new LthSmsAdapter();
        RestTemplate restTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        ReflectionTestUtils.setField(adapter, "properties", properties);
        ReflectionTestUtils.setField(adapter, "channelRestTemplate", restTemplate);
    }

    @Test
    void sendSuccess() {
        stubFor(post(urlEqualTo("/send"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"msgId\":\"lth-abc123\"}")));

        StepCommand command = StepCommand.builder()
                .channelType(ChannelType.SMS)
                .targetAddress("639171234567")
                .templateId("101")
                .idempotencyKey("1:1:0")
                .metadata(Collections.singletonMap(StepCommand.META_SMS_BODY, "Hello test"))
                .build();

        StepResult result = adapter.send(command);
        assertTrue(result.isSuccess());
        assertEquals("lth-abc123", result.getProviderMsgId());
        assertFalse(result.isRetryable());
    }

    @Test
    void send5xxRetryable() {
        stubFor(post(urlEqualTo("/send")).willReturn(aResponse().withStatus(503)));

        StepCommand command = StepCommand.builder()
                .channelType(ChannelType.SMS)
                .targetAddress("639171234567")
                .idempotencyKey("1:1:0")
                .metadata(new HashMap<>())
                .build();

        StepResult result = adapter.send(command);
        assertFalse(result.isSuccess());
        assertTrue(result.isRetryable());
    }

    @Test
    void notConfigured() {
        properties.getLth().getSms().setUrl("");
        StepResult result = adapter.send(StepCommand.builder()
                .channelType(ChannelType.SMS)
                .targetAddress("639171234567")
                .idempotencyKey("1:1:0")
                .build());
        assertFalse(result.isSuccess());
        assertEquals("LTH_SMS_NOT_CONFIGURED", result.getErrorCode());
    }
}
