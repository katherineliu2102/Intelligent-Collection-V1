package com.collection.admin.web.sendgrid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.admin.web.WebhookSecurityProperties;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.enums.ContactResult;
import com.collection.common.model.ChannelCallbackAudit;
import com.collection.common.model.EmailSuppression;
import com.collection.common.repository.ChannelCallbackAuditRepository;
import com.collection.common.repository.EmailSuppressionRepository;
import com.collection.common.repository.TimelineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/** 事件批处理、幂等语义与验签失败留痕。验签本身由 {@link SendGridEventVerifierTest} 覆盖，这里默认关闭。 */
class SendGridWebhookServiceTest {

    private TimelineRepository timelineRepository;
    private EmailSuppressionRepository suppressionRepository;
    private ChannelCallbackAuditRepository auditRepository;
    private WebhookSecurityProperties security;
    private ChannelProperties channelProperties;
    private SendGridWebhookService service;

    @BeforeEach
    void setUp() {
        timelineRepository = mock(TimelineRepository.class);
        suppressionRepository = mock(EmailSuppressionRepository.class);
        auditRepository = mock(ChannelCallbackAuditRepository.class);
        security = new WebhookSecurityProperties();
        security.setSignatureRequired(false);
        channelProperties = new ChannelProperties();
        service =
                new SendGridWebhookService(
                        new ObjectMapper(),
                        channelProperties,
                        security,
                        timelineRepository,
                        suppressionRepository,
                        auditRepository);
    }

    private Map<String, Object> post(String body) {
        return service.handle(body.getBytes(StandardCharsets.UTF_8), null, null);
    }

    @Test
    void openGoesThroughUpgradeChain() {
        when(timelineRepository.upgradeResult(
                        eq("7:1:0"), eq(ContactResult.READ), anyString(), anyString()))
                .thenReturn(1);

        Map<String, Object> result =
                post(
                        "[{\"event\":\"open\",\"email\":\"a@b.com\",\"idempotency_key\":\"7:1:0\","
                                + "\"sg_message_id\":\"msg-1\"}]");

        assertThat(result.get("applied")).isEqualTo(1);
        verify(timelineRepository)
                .upgradeResult(eq("7:1:0"), eq(ContactResult.READ), eq("msg-1"), anyString());
        verify(timelineRepository, never()).overrideResult(anyString(), any(), any(), anyString());
    }

    @Test
    void hardBounceOverridesResultAndWritesSuppression() {
        when(timelineRepository.overrideResult(
                        eq("7:1:0"), eq(ContactResult.REJECTED), any(), anyString()))
                .thenReturn(1);

        Map<String, Object> result =
                post(
                        "[{\"event\":\"bounce\",\"type\":\"bounce\",\"email\":\"Dead@B.com\","
                                + "\"idempotency_key\":\"7:1:0\",\"case_id\":\"92001\","
                                + "\"reason\":\"550 no such user\"}]");

        assertThat(result.get("applied")).isEqualTo(1);
        assertThat(result.get("suppressed")).isEqualTo(1);

        ArgumentCaptor<EmailSuppression> captor = ArgumentCaptor.forClass(EmailSuppression.class);
        verify(suppressionRepository).suppress(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("HARD_BOUNCE");
        assertThat(captor.getValue().getCaseId()).isEqualTo(92001L);
        assertThat(captor.getValue().getDetail()).isEqualTo("550 no such user");
    }

    @Test
    void spamReportSuppressesWithoutTouchingTimeline() {
        Map<String, Object> result =
                post(
                        "[{\"event\":\"spamreport\",\"email\":\"a@b.com\","
                                + "\"idempotency_key\":\"7:1:0\"}]");

        assertThat(result.get("suppressed")).isEqualTo(1);
        verify(timelineRepository, never()).upgradeResult(anyString(), any(), any(), anyString());
        verify(timelineRepository, never()).overrideResult(anyString(), any(), any(), anyString());
    }

    @Test
    void countsBatchOfMixedEvents() {
        when(timelineRepository.upgradeResult(anyString(), any(), any(), anyString()))
                .thenReturn(1);

        Map<String, Object> result =
                post(
                        "[{\"event\":\"processed\",\"idempotency_key\":\"7:1:0\"},"
                                + "{\"event\":\"delivered\",\"idempotency_key\":\"7:1:0\"},"
                                + "{\"event\":\"open\",\"idempotency_key\":\"7:1:0\"}]");

        assertThat(result.get("applied")).isEqualTo(2);
        assertThat(result.get("skipped")).isEqualTo(0);
    }

    @Test
    void skipsWhenUpgradeChainRefusesTheWrite() {
        // 乱序抵达的低阶事件：SQL 谓词不匹配返回 0 行，不是故障，也不该让供应商重投。
        when(timelineRepository.upgradeResult(anyString(), any(), any(), anyString()))
                .thenReturn(0);

        Map<String, Object> result =
                post("[{\"event\":\"delivered\",\"idempotency_key\":\"7:1:0\"}]");

        assertThat(result.get("applied")).isEqualTo(0);
        assertThat(result.get("skipped")).isEqualTo(1);
        assertThat(result.get("failed")).isEqualTo(0);
    }

    @Test
    void skipsEventWithoutIdempotencyKey() {
        Map<String, Object> result = post("[{\"event\":\"open\",\"email\":\"a@b.com\"}]");

        assertThat(result.get("skipped")).isEqualTo(1);
        verify(timelineRepository, never()).upgradeResult(anyString(), any(), any(), anyString());
    }

    @Test
    void suppressionStillAppliedWhenAttemptKeyMissing() {
        // 无法定位 timeline 行不该连抑制一起丢：地址已废是地址级事实，与哪一步无关。
        Map<String, Object> result =
                post("[{\"event\":\"bounce\",\"type\":\"bounce\",\"email\":\"a@b.com\"}]");

        assertThat(result.get("suppressed")).isEqualTo(1);
        assertThat(result.get("skipped")).isEqualTo(1);
    }

    @Test
    void demandsRetryWhenPersistenceFails() {
        when(timelineRepository.upgradeResult(anyString(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> post("[{\"event\":\"open\",\"idempotency_key\":\"7:1:0\"}]"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void acceptsSingleObjectBody() {
        when(timelineRepository.upgradeResult(anyString(), any(), any(), anyString()))
                .thenReturn(1);

        assertThat(post("{\"event\":\"open\",\"idempotency_key\":\"7:1:0\"}").get("applied"))
                .isEqualTo(1);
    }

    @Test
    void swallowsSignedButUnparseableBody() {
        Map<String, Object> result = post("not json at all");

        assertThat(result.get("applied")).isEqualTo(0);
        assertThat(result.get("failed")).isEqualTo(0);
    }

    @Test
    void rejectsAndAuditsWhenPublicKeyMissing() {
        // 端点公网可达，无公钥时无法区分供应商事件与伪造事件，只能拒收并留痕。
        security.setSignatureRequired(true);
        channelProperties.getSendgrid().setEventWebhookPublicKey("");
        byte[] body = "[{\"event\":\"open\"}]".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(
                        () ->
                                service.handle(
                                        body,
                                        "MEUCIQ-looks-like-a-signature",
                                        String.valueOf(Instant.now().getEpochSecond())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");

        ArgumentCaptor<ChannelCallbackAudit> captor =
                ArgumentCaptor.forClass(ChannelCallbackAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().isSignatureValid()).isFalse();
        assertThat(captor.getValue().getDisposition()).isEqualTo("SENDGRID_EVENT_WEBHOOK");
        assertThat(captor.getValue().getResult()).isEqualTo("PUBLIC_KEY_NOT_CONFIGURED");
        assertThat(captor.getValue().getCanonicalPayload()).contains("open");
    }

    @Test
    void rejectsWhenSignatureHeadersAbsent() {
        security.setSignatureRequired(true);
        channelProperties.getSendgrid().setEventWebhookPublicKey("some-key");

        assertThatThrownBy(() -> post("[{\"event\":\"open\"}]"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");

        ArgumentCaptor<ChannelCallbackAudit> captor =
                ArgumentCaptor.forClass(ChannelCallbackAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("MISSING_HEADER");
    }
}
