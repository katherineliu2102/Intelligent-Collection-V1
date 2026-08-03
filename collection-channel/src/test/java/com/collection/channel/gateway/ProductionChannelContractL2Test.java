package com.collection.channel.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.collection.channel.adapter.NotificationPushAdapter;
import com.collection.channel.adapter.NotificationSmsAdapter;
import com.collection.channel.adapter.SendGridEmailAdapter;
import com.collection.channel.client.NotificationClient;
import com.collection.channel.config.ChannelProperties;
import com.collection.channel.strategy.ConfigurableExecutionGuard;
import com.collection.channel.strategy.DefaultPlanFactory;
import com.collection.channel.strategy.DefaultStepResolver;
import com.collection.channel.strategy.ScriptLibrary;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.service.IdempotencyService;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * L2 production-path contract: real PlanFactory/Guard/Resolver/Gateway/Adapters, with WireMock
 * replacing only Notification/SendGrid HTTP.
 */
@WireMockTest
class ProductionChannelContractL2Test {

    private ChannelProperties properties;
    private DefaultPlanFactory planFactory;
    private DefaultStepResolver resolver;
    private ConfigurableExecutionGuard guard;
    private ChannelGatewayImpl gateway;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        properties = new ChannelProperties();
        properties.setFallbackToMock(false);
        properties.getCompliance().setQuietHoursStart("00:00");
        properties.getCompliance().setQuietHoursEnd("00:00");
        properties.getCompliance().setDailyLimit(Collections.emptyMap());
        properties.getCompliance().setDailyTotalLimit(0);
        properties.getNotification().setBaseUrl(wm.getHttpBaseUrl());
        properties.getNotification().setAppCode("mocasa-test");
        properties.getNotification().setAppKey("test-key");
        properties.getScripts().setSmsDefaultRepaymentLink("https://test.example/pay");
        properties.getSendgrid().setApiKey("test-sendgrid-key");
        properties.getSendgrid().setFromEmail("collections@example.test");
        properties.getSendgrid().setApiUrl(wm.getHttpBaseUrl() + "/v3/mail/send");
        properties
                .getSendgrid()
                .getTemplates()
                .put("S1_EMAIL_OVERDUE_NOTICE", "d-contract-template");
        properties
                .getScripts()
                .getSms()
                .put(
                        "S1_SMS_STANDARD",
                        "MOCASA: {name}, PHP {amount} is {dpd} days overdue. Pay: {repaymentUrl}");

        ScriptLibrary scriptLibrary = new ScriptLibrary();
        ReflectionTestUtils.setField(scriptLibrary, "channelProperties", properties);

        resolver = new DefaultStepResolver();
        ReflectionTestUtils.setField(resolver, "channelProperties", properties);
        ReflectionTestUtils.setField(resolver, "scriptLibrary", scriptLibrary);

        planFactory = new DefaultPlanFactory();
        ReflectionTestUtils.setField(planFactory, "channelProperties", properties);

        guard = new ConfigurableExecutionGuard();
        ReflectionTestUtils.setField(guard, "channelProperties", properties);

        NotificationClient notificationClient = new NotificationClient();
        ReflectionTestUtils.setField(notificationClient, "properties", properties);
        ReflectionTestUtils.setField(
                notificationClient,
                "channelRestTemplate",
                new RestTemplate(new SimpleClientHttpRequestFactory()));

        NotificationSmsAdapter smsAdapter = new NotificationSmsAdapter();
        ReflectionTestUtils.setField(smsAdapter, "properties", properties);
        ReflectionTestUtils.setField(smsAdapter, "notificationClient", notificationClient);

        NotificationPushAdapter pushAdapter = new NotificationPushAdapter();
        ReflectionTestUtils.setField(pushAdapter, "properties", properties);
        ReflectionTestUtils.setField(pushAdapter, "notificationClient", notificationClient);
        ReflectionTestUtils.setField(pushAdapter, "notificationSmsAdapter", smsAdapter);

        SendGridEmailAdapter emailAdapter = new SendGridEmailAdapter();
        ReflectionTestUtils.setField(emailAdapter, "properties", properties);
        ReflectionTestUtils.setField(
                emailAdapter,
                "channelRestTemplate",
                new RestTemplate(new SimpleClientHttpRequestFactory()));

        gateway = new ChannelGatewayImpl();
        ReflectionTestUtils.setField(
                gateway,
                "adapters",
                java.util.Arrays.asList(smsAdapter, pushAdapter, emailAdapter));
        ReflectionTestUtils.setField(gateway, "mockChannelGateway", new MockChannelGateway());
        ReflectionTestUtils.setField(gateway, "idempotencyService", new TestIdempotencyService());
        ReflectionTestUtils.setField(gateway, "channelProperties", properties);
        gateway.initAdapterMap();
    }

    @Test
    void c1_realPlanFactoryResolverAndGateway_deliverSmsWithProvenance() {
        properties.getDebug().setSingleStep("SMS");
        stubSmsAccepted("req-c1");

        ContactPlan plan =
                planFactory.create(caseInfo(), Stage.S1, snapshot("+639171234567", "token-1"));
        ContactPlanStep step = plan.getSteps().get(0);
        StepCommand command =
                resolver.resolve(context(plan, step, snapshot("+639171234567", "token-1")));
        StepResult result = gateway.dispatch(command);

        assertEquals(ChannelType.SMS, step.getChannelType());
        assertEquals(ChannelType.SMS, command.getChannelType());
        assertEquals("101", command.getTemplateId());
        assertEquals("S1_SMS_STANDARD", command.getMetadata().get(StepCommand.META_SCRIPT_SLOT));
        assertEquals("req-c1", result.getProviderMsgId());
        assertTrue(result.isSuccess());
        verify(
                postRequestedFor(urlEqualTo("/v1/sms/send"))
                        .withRequestBody(matchingJsonPath("$.content")));
    }

    @Test
    void c1_realPlanFactoryResolverAndGateway_deliverEmailWithTemplateProvenance() {
        properties.getDebug().setSingleStep("EMAIL");
        stubFor(
                post(urlEqualTo("/v3/mail/send"))
                        .willReturn(
                                aResponse().withStatus(202).withHeader("X-Message-Id", "sg-c1")));

        ContextSnapshot emailSnapshot = snapshot("+639171234567", "token-1");
        emailSnapshot.getCaseContext().setEmailScriptSlot("S1_EMAIL_OVERDUE_NOTICE");
        ContactPlan plan = planFactory.create(caseInfo(), Stage.S1, emailSnapshot);
        StepCommand command =
                resolver.resolve(context(plan, plan.getSteps().get(0), emailSnapshot));
        StepResult result = gateway.dispatch(command);

        assertEquals(ChannelType.EMAIL, command.getChannelType());
        assertEquals(
                "S1_EMAIL_OVERDUE_NOTICE", command.getMetadata().get(StepCommand.META_SCRIPT_SLOT));
        assertEquals("sg-c1", result.getProviderMsgId());
        assertTrue(result.isSuccess());
        verify(
                postRequestedFor(urlEqualTo("/v3/mail/send"))
                        .withRequestBody(
                                matchingJsonPath("$.template_id", equalTo("d-contract-template"))));
    }

    @Test
    void c3_realGuard_blocksSmsWithoutPhone() {
        properties.getDebug().setSingleStep("SMS");
        ContextSnapshot snapshot = snapshot(null, "token-1");
        ContactPlan plan = planFactory.create(caseInfo(), Stage.S1, snapshot);

        GuardVerdict verdict = guard.evaluate(context(plan, plan.getSteps().get(0), snapshot));

        assertFalse(verdict.isAllowed());
        assertEquals("NO_PHONE", verdict.getBlockedRuleType());
    }

    @Test
    void c4_realGateway_mapsTransportFailureToRetryable() {
        properties.getDebug().setSingleStep("SMS");
        stubFor(post(urlEqualTo("/v1/sms/send")).willReturn(aResponse().withStatus(503)));

        StepResult result = gateway.dispatch(resolvedSmsCommand("+639171234567"));

        assertFalse(result.isSuccess());
        assertTrue(result.isRetryable());
        assertEquals("NOTIFICATION_TIMEOUT", result.getErrorCode());
    }

    @Test
    void c5_realGateway_mapsBusinessRejectionToPermanentFailure() {
        properties.getDebug().setSingleStep("SMS");
        stubFor(
                post(urlEqualTo("/v1/sms/send"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"code\":2001,\"msg\":\"no account\"}")));

        StepResult result = gateway.dispatch(resolvedSmsCommand("+639171234567"));

        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertEquals("NOTIFICATION_NO_ACCOUNT", result.getErrorCode());
    }

    @Test
    void c6_realPushAdapter_fallsBackToSmsWhenSnapshotHasNoToken() {
        properties.getDebug().setSingleStep("PUSH");
        stubSmsAccepted("req-fallback");

        ContactPlan plan =
                planFactory.create(caseInfo(), Stage.S1, snapshot("+639171234567", null));
        StepCommand command =
                resolver.resolve(
                        context(plan, plan.getSteps().get(0), snapshot("+639171234567", null)));
        StepResult result = gateway.dispatch(command);

        assertEquals(ChannelType.PUSH, command.getChannelType());
        assertEquals("req-fallback", result.getProviderMsgId());
        assertTrue(result.isSuccess());
        verify(postRequestedFor(urlEqualTo("/v1/sms/send")));
    }

    @Test
    void c7_realGateway_returnsCachedResultForDuplicateDispatch() {
        properties.getDebug().setSingleStep("SMS");
        stubSmsAccepted("req-once");
        StepCommand command = resolvedSmsCommand("+639171234567");

        StepResult first = gateway.dispatch(command);
        StepResult duplicate = gateway.dispatch(command);

        assertTrue(first.isSuccess());
        assertEquals(first.getProviderMsgId(), duplicate.getProviderMsgId());
        verify(1, postRequestedFor(urlEqualTo("/v1/sms/send")));
    }

    private StepCommand resolvedSmsCommand(String phone) {
        ContactPlan plan = planFactory.create(caseInfo(), Stage.S1, snapshot(phone, "token-1"));
        return resolver.resolve(context(plan, plan.getSteps().get(0), snapshot(phone, "token-1")));
    }

    private void stubSmsAccepted(String requestId) {
        stubFor(
                post(urlEqualTo("/v1/sms/send"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"code\":0,\"data\":{\"requestSuccess\":true,\"requestId\":\""
                                                        + requestId
                                                        + "\"}}")));
    }

    private static CaseInfo caseInfo() {
        CaseInfo info = new CaseInfo();
        info.setCaseId(90001L);
        info.setUserId(80001L);
        info.setStage(Stage.S1);
        return info;
    }

    private static ExecutionContext context(
            ContactPlan plan, ContactPlanStep step, ContextSnapshot snapshot) {
        return ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(snapshot)
                .recentTimeline(Collections.emptyList())
                .build();
    }

    private static ContextSnapshot snapshot(String phone, String jpushToken) {
        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setName("Contract Test");
        basic.setPrimaryPhone(phone);
        basic.setEmail("contract@example.test");
        UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();
        device.setJpushToken(jpushToken);
        UserProfile profile = new UserProfile();
        profile.setUserId(80001L);
        profile.setBasic(basic);
        profile.setDevice(device);
        CaseContext caseContext = new CaseContext();
        caseContext.setDpd(12);
        caseContext.setTotalOutstanding(new BigDecimal("1234.56"));
        caseContext.setRepaymentUrl("https://test.example/pay");
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setUserProfile(profile);
        snapshot.setCaseContext(caseContext);
        return snapshot;
    }

    private static class TestIdempotencyService implements IdempotencyService {
        private final Map<String, Boolean> keys = new HashMap<>();

        @Override
        public boolean acquire(String key, int ttlMinutes) {
            return keys.putIfAbsent(key, Boolean.TRUE) == null;
        }
    }
}
