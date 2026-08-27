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
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@WireMockTest
class FacadeAiCallAdapterTest {

    private FacadeAiCallAdapter adapter;
    private ChannelProperties properties;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        properties = new ChannelProperties();
        properties.getFacade().setBaseUrl(wireMock.getHttpBaseUrl() + "/api/v1/facade");
        properties.getFacade().setApiKey("sk_test_xxx");
        FacadeBatchClient batchClient = new FacadeBatchClient();
        ReflectionTestUtils.setField(batchClient, "properties", properties);
        ReflectionTestUtils.setField(
                batchClient,
                "facadeRestTemplate",
                new RestTemplate(new SimpleClientHttpRequestFactory()));
        adapter = new FacadeAiCallAdapter();
        ReflectionTestUtils.setField(adapter, "properties", properties);
        ReflectionTestUtils.setField(adapter, "batchClient", batchClient);
    }

    @Test
    void normalizesPhilippineE164() {
        assertEquals("+639451373897", FacadeAiCallAdapter.normalizeE164("639451373897"));
        assertEquals("+639451373897", FacadeAiCallAdapter.normalizeE164("+639451373897"));
        assertEquals("+639451373897", FacadeAiCallAdapter.normalizeE164("09451373897"));
        assertNull(FacadeAiCallAdapter.normalizeE164("123"));
    }

    @Test
    void rejectsMissingConfigurationOrRequiredContext() {
        properties.getFacade().setApiKey("");
        assertEquals(
                "AI_CALL_NOT_CONFIGURED", adapter.send(command("+639451373897")).getErrorCode());

        properties.getFacade().setApiKey("sk_test_xxx");
        StepCommand missingAmount =
                StepCommand.builder()
                        .channelType(ChannelType.AI_CALL)
                        .targetAddress("+639451373897")
                        .metadata(new HashMap<String, Object>())
                        .build();
        assertEquals("MISSING_BORROWER_NAME", adapter.send(missingAmount).getErrorCode());
    }

    @Test
    void testCalleeOverridesBorrowerNumber() {
        // 演练期唯一的不触达客户的出口：配了就必须改投，否则 pilot 一开调度就是真拨借款人。
        stubFacadeHappyPath();
        properties.getFacade().setTestCallee("09451373898");

        StepResult result = adapter.send(command("+639998887777"));

        assertTrue(result.isSuccess());
        verify(
                postRequestedFor(urlEqualTo("/api/v1/facade/batches/batch-1/cases"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.cases[0].callee_e164", equalTo("+639451373898")))
                        .withRequestBody(notContaining("+639998887777")));
    }

    @Test
    void startsOneCaseBatchWithoutLanguage() {
        stubFacadeHappyPath();

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
                                        "$.cases[0].business_context.debt.overdue_amount",
                                        equalTo("1000")))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.cases[0].business_context.debt.product_type",
                                        equalTo("Quick Loan"))));
    }

    /**
     * 建批请求不得携带回调地址或已取消的拨号字段。
     *
     * <p>依据 Facade 2026-08-20 修订说明：callback 是账户级配置（所有批次共用，由对方控制台登记），随请求下发无意义； 且 {@code dial_policy}
     * 只允许 {@code timezone} / {@code windows} / {@code weekdays}，带上 {@code retry}、 {@code
     * ring_timeout_sec}、{@code predictive}、{@code terminal_sip_codes} 或顶层 {@code prepare_mode} 会被判
     * HTTP 422 建批失败——旧版是静默丢弃，这类回归在真实联调前不会暴露，故由用例钉住。
     */
    @Test
    void batchRequestOmitsCallbackUrlAndCancelledDialPolicyFields() {
        stubFacadeHappyPath();

        StepCommand command = command("639451373897");
        command.getMetadata()
                .put(
                        StepCommand.META_CALLBACK_URL,
                        "https://pilot.example.com/webhook/channel-callback");

        assertTrue(adapter.send(command).isSuccess());

        verify(
                postRequestedFor(urlEqualTo("/api/v1/facade/batches"))
                        .withRequestBody(notContaining("callback_url"))
                        .withRequestBody(notContaining("ring_timeout_sec"))
                        .withRequestBody(notContaining("retry"))
                        .withRequestBody(notContaining("predictive"))
                        .withRequestBody(notContaining("terminal_sip_codes"))
                        .withRequestBody(notContaining("prepare_mode"))
                        .withRequestBody(matchingJsonPath("$.dial_policy.timezone"))
                        .withRequestBody(matchingJsonPath("$.dial_policy.windows[0].start_time"))
                        .withRequestBody(matchingJsonPath("$.dial_policy.weekdays")));
    }

    /**
     * 聚合开启后，单个步骤只入波次缓冲，绝不能自己再建一个批次——否则「一批多案」会退化成「一批多案 + 一堆单案批」，
     * 每个批次各占一套 Facade 并发，正好是聚合要消除的问题。
     */
    @Test
    void waveAggregationEnrollsWithoutCallingFacade() {
        stubFacadeHappyPath();
        FacadeBatchCoordinator coordinator = Mockito.mock(FacadeBatchCoordinator.class);
        Mockito.when(coordinator.isEnabled()).thenReturn(true);
        Mockito.when(
                        coordinator.enroll(
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn("mocasa-20260827-0915-1");
        ReflectionTestUtils.setField(adapter, "batchCoordinator", coordinator);

        StepResult result = adapter.send(command("639451373897"));

        assertTrue(result.isSuccess());
        assertEquals("mocasa-20260827-0915-1", result.getProviderMsgId());
        verify(0, postRequestedFor(urlEqualTo("/api/v1/facade/batches")));
    }

    @Test
    void fallsBackToOneCaseBatchWhenEnrollFails() {
        stubFacadeHappyPath();
        FacadeBatchCoordinator coordinator = Mockito.mock(FacadeBatchCoordinator.class);
        Mockito.when(coordinator.isEnabled()).thenReturn(true);
        Mockito.when(
                        coordinator.enroll(
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.any(),
                                ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(null);
        ReflectionTestUtils.setField(adapter, "batchCoordinator", coordinator);

        StepResult result = adapter.send(command("639451373897"));

        assertTrue(result.isSuccess());
        assertEquals("batch-1", result.getProviderMsgId());
        verify(postRequestedFor(urlEqualTo("/api/v1/facade/batches/batch-1/start")));
    }

    @Test
    void rejectsInvalidPhone() {
        StepResult result = adapter.send(command("12345"));
        assertFalse(result.isSuccess());
        assertEquals("INVALID_E164", result.getErrorCode());
        assertFalse(result.isRetryable());
    }

    private static void stubFacadeHappyPath() {
        stubFor(
                post(urlEqualTo("/api/v1/facade/batches"))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"success\":true,\"data\":{\"batch_id\":\"batch-1\"}}")));
        stubFor(
                post(urlEqualTo("/api/v1/facade/batches/batch-1/cases"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"success\":true,\"data\":{\"accepted\":1,\"rejected\":0}}")));
        stubFor(
                post(urlEqualTo("/api/v1/facade/batches/batch-1/start"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"success\":true,\"data\":{\"status\":\"running\"}}")));
    }

    private static StepCommand command(String phone) {
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put(StepCommand.META_CASE_ID, 90001L);
        metadata.put(FacadeAiCallAdapter.META_BORROWER_NAME, "Test Borrower");
        metadata.put(FacadeAiCallAdapter.META_OVERDUE_AMOUNT, "1000");
        metadata.put(FacadeAiCallAdapter.META_DPD, "5");
        metadata.put(FacadeAiCallAdapter.META_DUE_DATE, "2026-08-14");
        return StepCommand.builder()
                .channelType(ChannelType.AI_CALL)
                .targetAddress(phone)
                .idempotencyKey("90001:1:0")
                .providerIdempotencyKey("90001:1")
                .metadata(metadata)
                .build();
    }
}
