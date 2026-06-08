package com.collection.channel.adapter;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepResult;
import com.collection.common.dto.StepCommand;
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
class SendGridEmailAdapterTest {

    private SendGridEmailAdapter adapter;
    private ChannelProperties properties;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        properties = new ChannelProperties();
        properties.getSendgrid().setApiKey("SG.test-key");
        properties.getSendgrid().setFromEmail("collections@mocasa.test");
        properties.getSendgrid().getTemplates().put("S0_DUE_TODAY_EMAIL", "d-test-template");
        properties.getSendgrid().setApiUrl(wm.getHttpBaseUrl() + "/v3/mail/send");

        adapter = new SendGridEmailAdapter();
        RestTemplate restTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        ReflectionTestUtils.setField(adapter, "properties", properties);
        ReflectionTestUtils.setField(adapter, "channelRestTemplate", restTemplate);
    }

    @Test
    void accepted202() {
        stubFor(post(urlEqualTo("/v3/mail/send"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("X-Message-Id", "sg-msg-001")));

        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_CASE_ID, 91001L);
        meta.put(StepCommand.META_SCRIPT_SLOT, "S0_DUE_TODAY_EMAIL");
        meta.put(StepCommand.META_DYNAMIC_TEMPLATE_DATA, new HashMap<String, Object>() {{
            put("payment_link", "https://app.mocasa/mock/repay/91001");
        }});

        StepCommand command = StepCommand.builder()
                .channelType(ChannelType.EMAIL)
                .targetAddress("user@mocasa.test")
                .templateId("201")
                .idempotencyKey("1:2:0")
                .metadata(meta)
                .build();

        StepResult result = adapter.send(command);
        assertTrue(result.isSuccess());
        assertEquals("sg-msg-001", result.getProviderMsgId());
    }

    @Test
    void noEmailPermanentFailure() {
        StepCommand command = StepCommand.builder()
                .channelType(ChannelType.EMAIL)
                .targetAddress("invalid-phone")
                .templateId("201")
                .idempotencyKey("1:2:0")
                .build();
        StepResult result = adapter.send(command);
        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertEquals("NO_EMAIL", result.getErrorCode());
    }

    @Test
    void resolvesTemplateIdFromScriptSlotMap() {
        properties.getSendgrid().getTemplates().put("S0_DUE_TODAY_EMAIL", "d-mapped-from-slot");

        stubFor(post(urlEqualTo("/v3/mail/send"))
                .withRequestBody(matchingJsonPath("$.template_id", equalTo("d-mapped-from-slot")))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("X-Message-Id", "sg-slot-001")));

        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_SCRIPT_SLOT, "S0_DUE_TODAY_EMAIL");

        StepCommand command = StepCommand.builder()
                .channelType(ChannelType.EMAIL)
                .targetAddress("user@mocasa.test")
                .templateId("S0_DUE_TODAY_EMAIL")
                .idempotencyKey("1:2:0")
                .metadata(meta)
                .build();

        StepResult result = adapter.send(command);
        assertTrue(result.isSuccess());
        assertEquals("sg-slot-001", result.getProviderMsgId());
    }

    @Test
    void noTemplateMappingFails() {
        StepCommand command = StepCommand.builder()
                .channelType(ChannelType.EMAIL)
                .targetAddress("user@mocasa.test")
                .templateId("201")
                .idempotencyKey("1:2:0")
                .metadata(new HashMap<String, Object>() {{
                    put(StepCommand.META_SCRIPT_SLOT, "UNKNOWN_SLOT");
                }})
                .build();
        StepResult result = adapter.send(command);
        assertFalse(result.isSuccess());
        assertEquals("SENDGRID_NO_TEMPLATE", result.getErrorCode());
    }

    @Test
    void notConfiguredWhenMissingApiKey() {
        properties.getSendgrid().setApiKey("");
        StepCommand command = StepCommand.builder()
                .channelType(ChannelType.EMAIL)
                .targetAddress("user@mocasa.test")
                .templateId("d-xxx")
                .idempotencyKey("1:2:0")
                .build();
        StepResult result = adapter.send(command);
        assertEquals("SENDGRID_NOT_CONFIGURED", result.getErrorCode());
    }
}
