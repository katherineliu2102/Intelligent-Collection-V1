package com.collection.engine.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.collection.channel.adapter.NotificationPushAdapter;
import com.collection.channel.adapter.NotificationSmsAdapter;
import com.collection.channel.adapter.SendGridEmailAdapter;
import com.collection.channel.client.NotificationClient;
import com.collection.channel.config.ChannelProperties;
import com.collection.channel.gateway.ChannelGatewayImpl;
import com.collection.channel.gateway.MockChannelGateway;
import com.collection.channel.strategy.ConfigurableExecutionGuard;
import com.collection.channel.strategy.DefaultPlanFactory;
import com.collection.channel.strategy.DefaultStepResolver;
import com.collection.channel.strategy.ScriptLibrary;
import com.collection.common.dto.ExhaustionResult;
import com.collection.common.enums.AdvancementDecision;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.EventType;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactHistory;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContactRecord;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.repository.DecisionLogRepository;
import com.collection.common.service.CaseService;
import com.collection.common.service.PredictiveDialerService;
import com.collection.common.spi.AdvancementPolicy;
import com.collection.common.spi.ExhaustionPolicy;
import com.collection.engine.bus.InMemoryIdempotencyService;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.lifecycle.ContextAssembler;
import com.collection.engine.lifecycle.DeliveryAuditMetadata;
import com.collection.engine.lifecycle.EventConsumerDispatcher;
import com.collection.engine.lifecycle.PlanLifecycleManager;
import com.collection.engine.lifecycle.PreFlightChecker;
import com.collection.engine.lifecycle.StepExecutionOrchestrator;
import com.collection.engine.lifecycle.StepOutcomeRecorder;
import com.collection.engine.spi.SpiInvoker;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L2 引擎↔渠道执行契约测试（C1–C7）——<b>真实 SPI 版</b>。
 *
 * <p>与 {@link ChannelContractL2Test}（契约替身基线）互补：本类用编排同事的真实实现 {@code
 * DefaultPlanFactory / ConfigurableExecutionGuard / DefaultStepResolver / ChannelGatewayImpl +
 * Adapter} 驱动真实引擎组件，只把供应商 HTTP（Notification / SendGrid）换成 WireMock，因此断言覆盖
 * “真实渠道决策 → 引擎状态推进 → timeline 落库”的完整链路。
 *
 * <p>与 {@code ProductionChannelContractL2Test}（collection-channel）的差别：那里只断言渠道子图产出的
 * StepCommand/StepResult，本类断言引擎侧的步骤/计划状态机与 timeline 溯源字段。
 */
@WireMockTest
class ChannelContractL2RealSpiTest {

    private static final long CASE_ID = 1002L;
    private static final long USER_ID = 9001L;
    private static final String PHONE = "+639170000001";
    private static final String EMAIL = "juan@example.com";
    private static final String JPUSH = "jpush-rid-abc";
    private static final String EMAIL_SLOT = "S1_EMAIL_OVERDUE_NOTICE";
    private static final String SMS_PATH = "/v1/sms/send";
    private static final String PUSH_PATH = "/v1/app_notification/sync/send";
    private static final String SENDGRID_PATH = "/v3/mail/send";

    private ChannelProperties channelProperties;
    private ChannelContractL2Test.SyncEventBus bus;
    private ChannelContractL2Test.InMemoryPlanRepository planRepo;
    private ChannelContractL2Test.InMemoryTimelineRepository timelineRepo;
    private MutableCaseService caseService;

    @BeforeEach
    void wire(WireMockRuntimeInfo wm) {
        bus = new ChannelContractL2Test.SyncEventBus();
        planRepo = new ChannelContractL2Test.InMemoryPlanRepository();
        timelineRepo = new ChannelContractL2Test.InMemoryTimelineRepository();
        caseService = new MutableCaseService();

        channelProperties = channelProperties(wm);

        // ── 真实渠道 SPI（编排同事实现） ──
        ScriptLibrary scriptLibrary = new ScriptLibrary();
        inject(scriptLibrary, "channelProperties", channelProperties);

        DefaultPlanFactory planFactory = new DefaultPlanFactory();
        inject(planFactory, "channelProperties", channelProperties);

        ConfigurableExecutionGuard guard = new ConfigurableExecutionGuard();
        inject(guard, "channelProperties", channelProperties);

        DefaultStepResolver resolver = new DefaultStepResolver();
        inject(resolver, "channelProperties", channelProperties);
        inject(resolver, "scriptLibrary", scriptLibrary);

        NotificationClient notificationClient = new NotificationClient();
        inject(notificationClient, "properties", channelProperties);
        inject(notificationClient, "channelRestTemplate", restTemplate());

        NotificationSmsAdapter smsAdapter = new NotificationSmsAdapter();
        inject(smsAdapter, "properties", channelProperties);
        inject(smsAdapter, "notificationClient", notificationClient);

        NotificationPushAdapter pushAdapter = new NotificationPushAdapter();
        inject(pushAdapter, "properties", channelProperties);
        inject(pushAdapter, "notificationClient", notificationClient);
        inject(pushAdapter, "notificationSmsAdapter", smsAdapter);

        SendGridEmailAdapter emailAdapter = new SendGridEmailAdapter();
        inject(emailAdapter, "properties", channelProperties);
        inject(emailAdapter, "channelRestTemplate", restTemplate());

        ChannelGatewayImpl gateway = new ChannelGatewayImpl();
        inject(gateway, "adapters", Arrays.asList(smsAdapter, pushAdapter, emailAdapter));
        inject(gateway, "mockChannelGateway", new MockChannelGateway());
        inject(gateway, "idempotencyService", new InMemoryIdempotencyService());
        inject(gateway, "channelProperties", channelProperties);
        invokeInit(gateway);

        // ── 真实引擎组件 ──
        EngineProperties props = new EngineProperties();
        props.getDeliveryAudit().setHmacKey("l2-contract-audit-key");
        props.getDeliveryAudit().setContentKeyId("l2-key-v1");

        DeliveryAuditMetadata auditMetadata = new DeliveryAuditMetadata();
        inject(auditMetadata, "properties", props);

        ContextAssembler contextAssembler = new ContextAssembler();
        inject(contextAssembler, "timelineRepository", timelineRepo);
        inject(contextAssembler, "props", props);

        PreFlightChecker preFlight = new PreFlightChecker();
        inject(preFlight, "caseService", caseService);

        StepOutcomeRecorder outcomeRecorder = new StepOutcomeRecorder();
        inject(outcomeRecorder, "planRepository", planRepo);
        inject(outcomeRecorder, "timelineRepository", timelineRepo);
        inject(outcomeRecorder, "deliveryAuditMetadata", auditMetadata);

        StepExecutionOrchestrator orchestrator = new StepExecutionOrchestrator();
        inject(orchestrator, "idempotencyService", new InMemoryIdempotencyService());
        inject(orchestrator, "preFlightChecker", preFlight);
        inject(orchestrator, "executionGuard", guard);
        inject(orchestrator, "stepResolver", resolver);
        inject(orchestrator, "channelGateway", gateway);
        inject(orchestrator, "contextAssembler", contextAssembler);
        inject(orchestrator, "planRepository", planRepo);
        inject(orchestrator, "timelineRepository", timelineRepo);
        inject(orchestrator, "stepOutcomeRecorder", outcomeRecorder);
        inject(orchestrator, "deliveryAuditMetadata", auditMetadata);
        inject(orchestrator, "decisionLogRepository", (DecisionLogRepository) dl -> {});
        inject(orchestrator, "eventBus", bus);
        inject(orchestrator, "spiInvoker", SpiInvoker.direct());
        inject(orchestrator, "props", props);

        PlanLifecycleManager manager = new PlanLifecycleManager();
        inject(manager, "planRepository", planRepo);
        inject(manager, "stepOutcomeRecorder", outcomeRecorder);
        inject(manager, "caseService", caseService);
        inject(manager, "planFactory", planFactory);
        inject(
                manager,
                "advancementPolicy",
                (AdvancementPolicy) (ctx, r) -> AdvancementDecision.ADVANCE_NEXT);
        inject(
                manager,
                "exhaustionPolicy",
                (ExhaustionPolicy) (plan, info, snap) -> ExhaustionResult.complete("done"));
        inject(manager, "predictiveDialerService", (PredictiveDialerService) userId -> {});
        inject(manager, "spiInvoker", SpiInvoker.direct());

        EventConsumerDispatcher dispatcher = new EventConsumerDispatcher();
        inject(dispatcher, "eventBus", bus);
        inject(dispatcher, "manager", manager);
        inject(dispatcher, "orchestrator", orchestrator);
        dispatcher.registerHandlers();
    }

    // ───────────────────────── C1 ─────────────────────────

    @Test
    @DisplayName("C1 真实 PlanFactory 三步编排 → SMS/PUSH/EMAIL 顺序执行、全部 COMPLETED、timeline 带溯源")
    void c1_realPlanFactory_threeStepOrderCompletesWithProvenance() {
        channelProperties.getDebug().setLegacyThreeStep(true);
        stubSmsAccepted("sms-c1");
        stubPushAccepted("push-c1");
        stubSendGridAccepted("sg-c1");

        drive(5);

        List<ContactPlanStep> steps = planRepo.stepsOf(onlyPlan().getId());
        assertThat(steps).hasSize(3);
        assertThat(steps)
                .extracting(ContactPlanStep::getChannelType)
                .containsExactly(ChannelType.SMS, ChannelType.PUSH, ChannelType.EMAIL);
        assertThat(steps)
                .extracting(ContactPlanStep::getStatus)
                .containsOnly(StepStatus.COMPLETED);
        assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED);

        assertThat(timelineRepo.records).hasSize(3);
        assertThat(timelineRepo.records)
                .extracting(ContactRecord::getProviderMsgId)
                .containsExactly("sms-c1", "push-c1", "sg-c1");

        ContactRecord sms = timelineRepo.records.get(0);
        assertThat(sms.getScriptSlot()).isEqualTo("S1_SMS_STANDARD");
        assertThat(sms.getTemplateVersion()).isEqualTo("nacos:l2-contract-test");
        assertThat(sms.getContentKeyId()).isEqualTo("l2-key-v1");
        assertThat(sms.getContentHmac()).hasSize(64);
        assertThat(sms.getContentSummary()).doesNotContain("Juan").doesNotContain(PHONE);
        assertThat(timelineRepo.records.get(2).getScriptSlot()).isEqualTo(EMAIL_SLOT);
    }

    // ───────────────────────── C2 ─────────────────────────

    @Test
    @DisplayName("C2 真实 PushAdapter 无 token → 同槽 fallback SMS，引擎仅一次 dispatch 即 COMPLETED")
    void c2_realPushAdapter_fallbackSmsIsTransparentToEngine() {
        channelProperties.getDebug().setSingleStep("PUSH");
        caseService.jpushToken = null;
        stubSmsAccepted("sms-fallback-c2");

        drive(0);

        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(timelineRepo.records).hasSize(1);
        assertThat(timelineRepo.records.get(0).getChannel()).isEqualTo(ChannelType.PUSH);
        assertThat(timelineRepo.records.get(0).getProviderMsgId()).isEqualTo("sms-fallback-c2");
        verify(1, postRequestedFor(urlEqualTo(SMS_PATH)));
        verify(0, postRequestedFor(urlEqualTo(PUSH_PATH)));
    }

    // ───────────────────────── C3 ─────────────────────────

    @Test
    @DisplayName("C3 真实 Guard 无手机号 → NO_PHONE block → 步骤 SKIPPED + COMPLIANCE_BLOCKED，不发 HTTP")
    void c3_realGuard_blocksWithoutPhone_stepSkippedWithoutDispatch() {
        channelProperties.getDebug().setSingleStep("SMS");
        caseService.phone = null;

        drive(0);

        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(timelineRepo.records).hasSize(1);
        assertThat(timelineRepo.records.get(0).getResult())
                .isEqualTo(ContactResult.COMPLIANCE_BLOCKED);
        verify(0, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    // ───────────────────────── C4 ─────────────────────────

    @Test
    @DisplayName("C4 真实 Adapter 传输故障 → NOTIFICATION_TIMEOUT retryable → 步骤退避重试，不落 timeline")
    void c4_realAdapterTransportFailure_schedulesBackoffRetry() {
        channelProperties.getDebug().setSingleStep("SMS");
        stubFor(post(urlEqualTo(SMS_PATH)).willReturn(aResponse().withStatus(503)));

        drive(0);

        ContactPlanStep step = onlyStep();
        assertThat(step.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(step.getRetryCount()).isEqualTo(1);
        assertThat(step.getTriggerTime()).isAfter(LocalDateTime.now());
        assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.STEP_EXECUTING);
        assertThat(timelineRepo.records).isEmpty();
    }

    // ───────────────────────── C5 ─────────────────────────

    @Test
    @DisplayName("C5 真实 Adapter 业务拒绝 → 不可重试 → 步骤 FAILED 且 timeline 记录终态")
    void c5_realAdapterBusinessRejection_failsStepWithoutRetry() {
        channelProperties.getDebug().setSingleStep("SMS");
        stubFor(
                post(urlEqualTo(SMS_PATH))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"code\":2001,\"msg\":\"no account\"}")));

        drive(0);

        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(onlyStep().getRetryCount()).isZero();
        assertThat(timelineRepo.records).hasSize(1);
        assertThat(timelineRepo.records.get(0).getResult()).isEqualTo(ContactResult.FAILED);
        verify(1, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    // ───────────────────────── C6 ─────────────────────────

    @Test
    @DisplayName("C6 真实 EMAIL 链路同步完成 → 不进 WAITING，步骤 COMPLETED 且计划推进到终态")
    void c6_realEmailChannel_completesWithoutObservationWaiting() {
        channelProperties.getDebug().setSingleStep("EMAIL");
        stubSendGridAccepted("sg-c6");

        drive(0);

        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(onlyPlan().getStatus())
                .isNotEqualTo(PlanStatus.STEP_WAITING)
                .isEqualTo(PlanStatus.PLAN_COMPLETED);
        assertThat(timelineRepo.records).hasSize(1);
        assertThat(timelineRepo.records.get(0).getChannel()).isEqualTo(ChannelType.EMAIL);
        assertThat(timelineRepo.records.get(0).getProviderMsgId()).isEqualTo("sg-c6");
    }

    // ───────────────────────── C7 ─────────────────────────

    @Test
    @DisplayName("C7 同一步骤重复 due → 供应商仅一次请求，且已完成步骤不被回退为 EXECUTING")
    void c7_duplicateDueEvent_dispatchesProviderOnceAndKeepsTerminalStatus() {
        channelProperties.getDebug().setSingleStep("SMS");
        stubSmsAccepted("sms-c7");

        bus.publish(caseIngested());
        bus.drainAll();
        ContactPlanStep step = onlyStep();
        bus.publish(stepDue(step.getPlanId(), step.getId()));
        bus.publish(stepDue(step.getPlanId(), step.getId()));
        bus.drainAll();

        assertThat(timelineRepo.records).hasSize(1);
        assertThat(timelineRepo.records.get(0).getProviderMsgId()).isEqualTo("sms-c7");
        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(1, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    @Test
    @DisplayName("C7b 步骤终结后迟到的 due → 完全 no-op，不改状态也不再次触达")
    void c7b_lateDueAfterTerminalStep_isNoop() {
        channelProperties.getDebug().setSingleStep("SMS");
        stubSmsAccepted("sms-c7b");

        drive(0);
        ContactPlanStep completed = onlyStep();
        assertThat(completed.getStatus()).isEqualTo(StepStatus.COMPLETED);

        bus.publish(stepDue(completed.getPlanId(), completed.getId()));
        bus.drainAll();

        assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(timelineRepo.records).hasSize(1);
        verify(1, postRequestedFor(urlEqualTo(SMS_PATH)));
    }

    // ───────────────────────── 驱动 & 装配 ─────────────────────────

    /** 发布入案事件并反复扫描到期步骤；{@code lookaheadMinutes} 用于跨过 plan 内的 delayMinutes。 */
    private void drive(long lookaheadMinutes) {
        bus.publish(caseIngested());
        bus.drainAll();
        for (int round = 0; round < 10; round++) {
            List<ContactPlanStep> due =
                    planRepo.findDueSteps(LocalDateTime.now().plusMinutes(lookaheadMinutes), 100);
            if (due.isEmpty()) {
                return;
            }
            for (ContactPlanStep step : due) {
                bus.publish(stepDue(step.getPlanId(), step.getId()));
            }
            bus.drainAll();
        }
    }

    private CollectionEvent caseIngested() {
        return CollectionEvent.of(EventType.CASE_INGESTED)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.USER_ID, USER_ID)
                .with(CollectionEvent.STAGE, Stage.S1.name());
    }

    private CollectionEvent stepDue(Long planId, Long stepId) {
        return CollectionEvent.of(EventType.PLAN_STEP_DUE)
                .with(CollectionEvent.PLAN_ID, planId)
                .with(CollectionEvent.STEP_ID, stepId);
    }

    private ContactPlan onlyPlan() {
        return planRepo.plans.values().iterator().next();
    }

    private ContactPlanStep onlyStep() {
        return planRepo.stepsOf(onlyPlan().getId()).get(0);
    }

    private static ChannelProperties channelProperties(WireMockRuntimeInfo wm) {
        ChannelProperties props = new ChannelProperties();
        props.setFallbackToMock(false);
        props.getCompliance().setQuietHoursStart("00:00");
        props.getCompliance().setQuietHoursEnd("00:00");
        props.getCompliance().setDailyLimit(Collections.emptyMap());
        props.getCompliance().setDailyTotalLimit(0);

        props.getNotification().setBaseUrl(wm.getHttpBaseUrl());
        props.getNotification().setAppCode("mocasa-test");
        props.getNotification().setAppKey("test-key");
        props.getNotification().setPushSyncMode(true);

        props.getScripts().setReleaseVersion("l2-contract-test");
        props.getScripts().setSmsDefaultRepaymentLink("https://test.example/pay");
        props.getScripts()
                .getSms()
                .put(
                        "S1_SMS_STANDARD",
                        "MOCASA: {name}, PHP {amount} is {dpd} days overdue. Pay: {repaymentUrl}");

        props.getSendgrid().setApiKey("test-sendgrid-key");
        props.getSendgrid().setFromEmail("collections@example.test");
        props.getSendgrid().setApiUrl(wm.getHttpBaseUrl() + SENDGRID_PATH);
        props.getSendgrid().getTemplates().put(EMAIL_SLOT, "d-contract-template");
        return props;
    }

    private static org.springframework.web.client.RestTemplate restTemplate() {
        return new org.springframework.web.client.RestTemplate(
                new org.springframework.http.client.SimpleClientHttpRequestFactory());
    }

    private void stubSmsAccepted(String requestId) {
        stubFor(
                post(urlEqualTo(SMS_PATH))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(notificationAccepted(requestId))));
    }

    private void stubPushAccepted(String requestId) {
        stubFor(
                post(urlEqualTo(PUSH_PATH))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(notificationAccepted(requestId))));
    }

    private void stubSendGridAccepted(String messageId) {
        stubFor(
                post(urlEqualTo(SENDGRID_PATH))
                        .willReturn(
                                aResponse().withStatus(202).withHeader("X-Message-Id", messageId)));
    }

    private static String notificationAccepted(String requestId) {
        return "{\"code\":0,\"data\":{\"requestSuccess\":true,\"requestId\":\""
                + requestId
                + "\"}}";
    }

    private static void invokeInit(ChannelGatewayImpl gateway) {
        try {
            java.lang.reflect.Method init =
                    ChannelGatewayImpl.class.getDeclaredMethod("initAdapterMap");
            init.setAccessible(true);
            init.invoke(gateway);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void inject(Object target, String field, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field f = type.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("no field " + field + " on " + target.getClass());
    }

    /** 快照地址可变的 CaseService，用于构造空地址/缺 token 场景。 */
    private static class MutableCaseService implements CaseService {
        String phone = PHONE;
        String email = EMAIL;
        String jpushToken = JPUSH;

        @Override
        public CaseInfo getCaseInfo(Long caseId) {
            CaseInfo info = new CaseInfo();
            info.setCaseId(caseId);
            info.setUserId(USER_ID);
            info.setStage(Stage.S1);
            info.setRepaid(false);
            info.setFrozen(false);
            return info;
        }

        @Override
        public CaseContext buildContext(Long caseId) {
            return null;
        }

        @Override
        public ContactHistory buildContactHistory(Long userId, Long caseId) {
            return null;
        }

        @Override
        public ContextSnapshot getContextSnapshot(Long caseId) {
            UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
            basic.setName("Juan Dela Cruz");
            basic.setPrimaryPhone(phone);
            basic.setEmail(email);
            UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();
            device.setJpushToken(jpushToken);
            UserProfile profile = new UserProfile();
            profile.setUserId(USER_ID);
            profile.setBasic(basic);
            profile.setDevice(device);

            CaseContext caseContext = new CaseContext();
            caseContext.setCaseId(caseId);
            caseContext.setStage(Stage.S1);
            caseContext.setDpd(12);
            caseContext.setTotalOutstanding(new BigDecimal("1234.56"));
            caseContext.setRepaymentUrl("https://test.example/pay");
            caseContext.setEmailScriptSlot(EMAIL_SLOT);

            ContextSnapshot snapshot = new ContextSnapshot();
            snapshot.setUserProfile(profile);
            snapshot.setCaseContext(caseContext);
            snapshot.setSnapshotVersion("l2-real-spi");
            return snapshot;
        }

        @Override
        public boolean isRepaid(Long caseId) {
            return false;
        }
    }
}
