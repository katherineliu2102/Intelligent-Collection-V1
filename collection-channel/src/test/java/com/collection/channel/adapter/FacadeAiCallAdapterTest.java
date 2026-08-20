package com.collection.channel.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class FacadeAiCallAdapterTest {

    private FacadeAiCallAdapter adapter;
    private ChannelProperties properties;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        properties = new ChannelProperties();
        properties.getFacade().setBaseUrl(wm.getHttpBaseUrl() + "/api/v1/facade");
        properties.getFacade().setApiKey("sk_test_xxx");
        adapter = new FacadeAiCallAdapter();
        ReflectionTestUtils.setField(adapter, "properties", properties);
        ReflectionTestUtils.setField(
                adapter,
                "facadeRestTemplate",
                new RestTemplate(new SimpleClientHttpRequestFactory()));
    }

    @Test
    void normalizeE164() {
        assertEquals("+639451373897", FacadeAiCallAdapter.normalizeE164("639451373897"));
        assertEquals("+639451373897", FacadeAiCallAdapter.normalizeE164("+639451373897"));
        assertEquals("+639451373897", FacadeAiCallAdapter.normalizeE164("09451373897"));
        assertNull(FacadeAiCallAdapter.normalizeE164("123"));
    }

    @Test
    void notConfigured() {
        properties.getFacade().setApiKey("");
        StepResult result = adapter.send(command("+639451373897"));
        assertFalse(result.isSuccess());
        assertEquals("AI_CALL_NOT_CONFIGURED", result.getErrorCode());
    }

    @Test
    void invalidPhone() {
        StepResult result = adapter.send(command("12345"));
        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertEquals("INVALID_E164", result.getErrorCode());
    }

    @Test
    void startOneCaseBatchWithoutLanguage() {
        stubFor(
                post(urlEqualTo("/api/v1/facade/batches"))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"success\":true,\"data\":{\"batch_id\":\"batch-1\",\"status\":\"created\"}}")));
        stubFor(
                post(urlEqualTo("/api/v1/facade/batches/batch-1/cases"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"success\":true,\"data\":{\"accepted\":1,\"rejected\":0,\"errors\":[]}}")));
        stubFor(
                post(urlEqualTo("/api/v1/facade/batches/batch-1/start"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"success\":true,\"data\":{\"status\":\"running\"}}")));

        StepResult result = adapter.send(command("639451373897"));
        assertTrue(result.isSuccess());
        assertEquals("batch-1", result.getProviderMsgId());

        verify(
                postRequestedFor(urlEqualTo("/api/v1/facade/batches"))
                        .withHeader("Authorization", equalTo("Bearer sk_test_xxx"))
                        .withRequestBody(matchingJsonPath("$.script.domain", equalTo("collection")))
                        .withRequestBody(notContaining("language")));
        verify(
                postRequestedFor(urlEqualTo("/api/v1/facade/batches/batch-1/cases"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.cases[0].callee_e164", equalTo("+639451373897")))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.cases[0].business_context.debt.product_type",
                                        equalTo("Quick Loan"))));
    }

    private static StepCommand command(String phone) {
        Map<String, Object> meta = new HashMap<String, Object>();
        meta.put(StepCommand.META_CASE_ID, 90001L);
        meta.put(FacadeAiCallAdapter.META_BORROWER_NAME, "Test Borrower");
        meta.put(FacadeAiCallAdapter.META_OVERDUE_AMOUNT, "1000");
        meta.put(FacadeAiCallAdapter.META_DPD, "5");
        meta.put(FacadeAiCallAdapter.META_DUE_DATE, "2026-08-14");
        return StepCommand.builder()
                .channelType(ChannelType.AI_CALL)
                .targetAddress(phone)
                .templateId("S1_VOICE_PRIMARY")
                .idempotencyKey("0:1:0")
                .metadata(meta)
                .build();
    }
}
