package com.collection.channel.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@WireMockTest
class SendGridEmailAdapterTest {

    private SendGridEmailAdapter adapter;
    private ChannelProperties properties;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        properties = new ChannelProperties();
        properties.getSendgrid().setApiKey("SG.test-key");
        properties.getSendgrid().setFromEmail("collections@mocasa.test");
        properties.getSendgrid().setApiUrl(wm.getHttpBaseUrl() + "/v3/mail/send");

        adapter = new SendGridEmailAdapter();
        RestTemplate restTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        ReflectionTestUtils.setField(adapter, "properties", properties);
        ReflectionTestUtils.setField(adapter, "channelRestTemplate", restTemplate);
    }

    @Test
    void accepted202() {
        stubFor(
                post(urlEqualTo("/v3/mail/send"))
                        .willReturn(
                                aResponse()
                                        .withStatus(202)
                                        .withHeader("X-Message-Id", "sg-msg-001")));

        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_CASE_ID, 91001L);
        meta.put(StepCommand.META_SCRIPT_SLOT, "S0_DUE_TODAY_EMAIL");
        meta.put(
                StepCommand.META_DYNAMIC_TEMPLATE_DATA,
                new HashMap<String, Object>() {
                    {
                        put("payment_link", "https://app.mocasa/mock/repay/91001");
                    }
                });

        StepCommand command =
                StepCommand.builder()
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

    /** 自持邮箱隔离：EMAIL 此前是四渠道里唯一既无 sandbox 也无改投出口的。 */
    @Test
    void testRecipientOverridesTarget() {
        properties.getSendgrid().setTestRecipient("wzynju@126.com");
        stubFor(
                post(urlEqualTo("/v3/mail/send"))
                        .willReturn(
                                aResponse()
                                        .withStatus(202)
                                        .withHeader("X-Message-Id", "sg-msg-002")));

        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_CASE_ID, 91001L);
        meta.put(StepCommand.META_SCRIPT_SLOT, "S0_DUE_TODAY_EMAIL");

        StepCommand command =
                StepCommand.builder()
                        .channelType(ChannelType.EMAIL)
                        .targetAddress("borrower@real.test")
                        .templateId("201")
                        .idempotencyKey("1:2:0")
                        .metadata(meta)
                        .build();

        assertTrue(adapter.send(command).isSuccess());

        verify(
                postRequestedFor(urlEqualTo("/v3/mail/send"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.personalizations[0].to[0].email",
                                        equalTo("wzynju@126.com"))));
    }

    /** 改投必须在地址校验之后，否则「上游没给邮箱」会被掩盖成发送成功。 */
    @Test
    void testRecipientDoesNotMaskMissingTarget() {
        properties.getSendgrid().setTestRecipient("wzynju@126.com");

        StepCommand command =
                StepCommand.builder()
                        .channelType(ChannelType.EMAIL)
                        .targetAddress("")
                        .templateId("201")
                        .idempotencyKey("1:2:0")
                        .build();

        StepResult result = adapter.send(command);
        assertFalse(result.isSuccess());
        assertEquals("NO_EMAIL", result.getErrorCode());
        verify(0, postRequestedFor(urlEqualTo("/v3/mail/send")));
    }

    @Test
    void noEmailPermanentFailure() {
        StepCommand command =
                StepCommand.builder()
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
    void resolvesTemplateIdFromCodeConstants() {
        stubFor(
                post(urlEqualTo("/v3/mail/send"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.template_id",
                                        equalTo("d-9b485bfd24e14950a7811faf33c2b22f")))
                        .willReturn(
                                aResponse()
                                        .withStatus(202)
                                        .withHeader("X-Message-Id", "sg-slot-001")));

        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_SCRIPT_SLOT, "S0_DUE_TODAY_EMAIL");

        StepCommand command =
                StepCommand.builder()
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
        StepCommand command =
                StepCommand.builder()
                        .channelType(ChannelType.EMAIL)
                        .targetAddress("user@mocasa.test")
                        .templateId("201")
                        .idempotencyKey("1:2:0")
                        .metadata(
                                new HashMap<String, Object>() {
                                    {
                                        put(StepCommand.META_SCRIPT_SLOT, "UNKNOWN_SLOT");
                                    }
                                })
                        .build();
        StepResult result = adapter.send(command);
        assertFalse(result.isSuccess());
        assertEquals("SENDGRID_NO_TEMPLATE", result.getErrorCode());
    }

    @Test
    void notConfiguredWhenMissingApiKey() {
        properties.getSendgrid().setApiKey("");
        StepCommand command =
                StepCommand.builder()
                        .channelType(ChannelType.EMAIL)
                        .targetAddress("user@mocasa.test")
                        .templateId("d-xxx")
                        .idempotencyKey("1:2:0")
                        .build();
        StepResult result = adapter.send(command);
        assertEquals("SENDGRID_NOT_CONFIGURED", result.getErrorCode());
    }
}
