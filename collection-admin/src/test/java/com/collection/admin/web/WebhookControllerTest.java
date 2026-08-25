package com.collection.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ChannelCallbackAudit;
import com.collection.common.repository.ChannelCallbackAuditRepository;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 回调入口验签与审计落库（测试 SSOT T1 · L0 admin 运行入口）。
 *
 * <p>三条不变量：验签失败一律 401 且不发内部事件；无论通过与否都留审计行并记录判定结果； 关闭验签只在 local/test 生效，且此时仍要写审计。
 */
class WebhookControllerTest {

    private static final String SECRET = "callback-secret";
    private static final long PLAN_ID = 100L;
    private static final long STEP_ID = 200L;
    private static final long CASE_ID = 525441L;

    private WebhookController controller;
    private CollectionEventBus eventBus;
    private ChannelCallbackAuditRepository auditRepository;
    private WebhookSecurityProperties security;

    @BeforeEach
    void setUp() {
        controller = new WebhookController();
        eventBus = mock(CollectionEventBus.class);
        auditRepository = mock(ChannelCallbackAuditRepository.class);
        security = new WebhookSecurityProperties();
        security.setSignatureRequired(true);
        security.setHmacSecret(SECRET);
        ReflectionTestUtils.setField(controller, "eventBus", eventBus);
        ReflectionTestUtils.setField(controller, "securityProperties", security);
        ReflectionTestUtils.setField(controller, "callbackAuditRepository", auditRepository);
    }

    @Test
    @DisplayName("签名正确 → 发布 CHANNEL_CALLBACK 并写审计（signatureValid=true）")
    void validSignaturePublishesCallbackAndAudits() {
        String signature = sign(canonical("DELIVERED", "msg-1", "OK"), SECRET);

        controller.channelCallback(
                PLAN_ID, STEP_ID, CASE_ID, "DELIVERED", "msg-1", "OK", signature);

        ArgumentCaptor<CollectionEvent> event = ArgumentCaptor.forClass(CollectionEvent.class);
        verify(eventBus).publish(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(EventType.CHANNEL_CALLBACK);
        assertThat(event.getValue().getLong(CollectionEvent.PLAN_ID)).isEqualTo(PLAN_ID);
        assertThat(event.getValue().getLong(CollectionEvent.STEP_ID)).isEqualTo(STEP_ID);
        assertThat(event.getValue().getString(CollectionEvent.PROVIDER_MSG_ID)).isEqualTo("msg-1");

        ChannelCallbackAudit audit = capturedAudit();
        assertThat(audit.isSignatureValid()).isTrue();
        assertThat(audit.getPlanId()).isEqualTo(PLAN_ID);
        assertThat(audit.getStepId()).isEqualTo(STEP_ID);
        assertThat(audit.getCaseId()).isEqualTo(CASE_ID);
        assertThat(audit.getProviderMsgId()).isEqualTo("msg-1");
        assertThat(audit.getSignature()).isEqualTo(signature);
        assertThat(audit.getCanonicalPayload()).isEqualTo(canonical("DELIVERED", "msg-1", "OK"));
    }

    @Test
    @DisplayName("伪造签名 → 401 且不发内部事件，但审计仍落库并标记 signatureValid=false")
    void forgedSignatureIsRejectedButStillAudited() {
        String forged = sign(canonical("DELIVERED", "msg-1", "OK"), "wrong-secret");

        assertThatThrownBy(
                        () ->
                                controller.channelCallback(
                                        PLAN_ID,
                                        STEP_ID,
                                        CASE_ID,
                                        "DELIVERED",
                                        "msg-1",
                                        "OK",
                                        forged))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid callback signature");

        verify(eventBus, never()).publish(org.mockito.ArgumentMatchers.any());
        assertThat(capturedAudit().isSignatureValid()).isFalse();
    }

    @Test
    @DisplayName("签名覆盖业务字段：篡改 result 后原签名失效")
    void signatureCoversBusinessFields() {
        String signature = sign(canonical("DELIVERED", "msg-1", "OK"), SECRET);

        assertThatThrownBy(
                        () ->
                                controller.channelCallback(
                                        PLAN_ID, STEP_ID, CASE_ID, "FAILED", "msg-1", "OK",
                                        signature))
                .isInstanceOf(ResponseStatusException.class);

        verify(eventBus, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("要求验签但请求缺签名头 → fail-closed 401")
    void missingSignatureHeaderIsRejected() {
        assertThatThrownBy(
                        () ->
                                controller.channelCallback(
                                        PLAN_ID,
                                        STEP_ID,
                                        CASE_ID,
                                        "DELIVERED",
                                        "msg-1",
                                        "OK",
                                        null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(eventBus, never()).publish(org.mockito.ArgumentMatchers.any());
        assertThat(capturedAudit().isSignatureValid()).isFalse();
    }

    @Test
    @DisplayName("要求验签但未配置密钥 → fail-closed，不因配置缺失放行")
    void missingSecretIsFailClosed() {
        security.setHmacSecret(null);
        String anySignature = sign(canonical("DELIVERED", "msg-1", "OK"), SECRET);

        assertThatThrownBy(
                        () ->
                                controller.channelCallback(
                                        PLAN_ID,
                                        STEP_ID,
                                        CASE_ID,
                                        "DELIVERED",
                                        "msg-1",
                                        "OK",
                                        anySignature))
                .isInstanceOf(ResponseStatusException.class);

        verify(eventBus, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("非法签名格式（非 hex / 奇数长度）→ 拒绝而非抛异常")
    void malformedSignatureIsRejected() {
        assertThatThrownBy(
                        () ->
                                controller.channelCallback(
                                        PLAN_ID,
                                        STEP_ID,
                                        CASE_ID,
                                        "DELIVERED",
                                        "msg-1",
                                        "OK",
                                        "zz1"))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(capturedAudit().isSignatureValid()).isFalse();
    }

    @Test
    @DisplayName("local/test 关闭验签 → 放行但仍写审计行")
    void signatureDisabledStillWritesAudit() {
        security.setSignatureRequired(false);
        security.setHmacSecret(null);

        controller.channelCallback(PLAN_ID, STEP_ID, CASE_ID, "DELIVERED", "msg-1", null, null);

        verify(eventBus).publish(org.mockito.ArgumentMatchers.any());
        ChannelCallbackAudit audit = capturedAudit();
        assertThat(audit.isSignatureValid()).isTrue();
        assertThat(audit.getSignature()).isNull();
    }

    private ChannelCallbackAudit capturedAudit() {
        ArgumentCaptor<ChannelCallbackAudit> audit =
                ArgumentCaptor.forClass(ChannelCallbackAudit.class);
        verify(auditRepository).save(audit.capture());
        return audit.getValue();
    }

    private String canonical(String result, String providerMsgId, String disposition) {
        return PLAN_ID + ":" + STEP_ID + ":" + result + ":" + providerMsgId + ":" + disposition;
    }

    private String sign(String canonical, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
