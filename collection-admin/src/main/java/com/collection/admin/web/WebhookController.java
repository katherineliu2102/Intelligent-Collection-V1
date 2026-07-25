package com.collection.admin.web;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ChannelCallbackAudit;
import com.collection.common.repository.ChannelCallbackAuditRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.web.bind.annotation.*;

/**
 * 渠道回调入口。对应架构设计文档 §1.7：统一接收外部供应商回调，鉴权后发布 CHANNEL_CALLBACK。
 *
 * <p>生产必须校验供应商 HMAC 签名后才转内部事件；local/test 可显式关闭验签供链路测试。
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    @Resource private CollectionEventBus eventBus;
    @Resource private WebhookSecurityProperties securityProperties;
    @Resource private ChannelCallbackAuditRepository callbackAuditRepository;

    /**
     * 渠道供应商回调 → 发布 CHANNEL_CALLBACK。
     *
     * @param result ContactResult 名（ANSWERED / NO_ANSWER / DELIVERED 等）
     */
    @PostMapping("/channel-callback")
    public Map<String, Object> channelCallback(
            @RequestParam Long planId,
            @RequestParam Long stepId,
            @RequestParam(required = false) Long caseId,
            @RequestParam(defaultValue = "ANSWERED") String result,
            @RequestParam(required = false) String providerMsgId,
            @RequestParam(required = false) String disposition,
            @RequestHeader(value = "X-Callback-Signature", required = false) String signature) {
        String canonical = canonicalPayload(planId, stepId, result, providerMsgId, disposition);
        boolean signatureValid = signatureValid(canonical, signature);
        writeAudit(
                planId,
                stepId,
                caseId,
                providerMsgId,
                result,
                disposition,
                canonical,
                signature,
                signatureValid);
        if (!signatureValid) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid callback signature");
        }
        eventBus.publish(
                CollectionEvent.of(EventType.CHANNEL_CALLBACK)
                        .with(CollectionEvent.PLAN_ID, planId)
                        .with(CollectionEvent.STEP_ID, stepId)
                        .with(CollectionEvent.CASE_ID, caseId)
                        .with(CollectionEvent.RESULT, result)
                        .with(CollectionEvent.PROVIDER_MSG_ID, providerMsgId)
                        .with(CollectionEvent.DISPOSITION, disposition));
        Map<String, Object> m = new HashMap<>();
        m.put("ok", true);
        m.put("message", "CHANNEL_CALLBACK published, planId=" + planId + " stepId=" + stepId);
        return m;
    }

    private boolean signatureValid(String canonical, String signature) {
        if (!securityProperties.isSignatureRequired()) {
            return true;
        }
        if (signature == null || securityProperties.getHmacSecret() == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            securityProperties.getHmacSecret().getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, hexToBytes(signature));
        } catch (Exception e) {
            return false;
        }
    }

    private String canonicalPayload(
            Long planId, Long stepId, String result, String providerMsgId, String disposition) {
        return planId
                + ":"
                + stepId
                + ":"
                + result
                + ":"
                + String.valueOf(providerMsgId)
                + ":"
                + String.valueOf(disposition);
    }

    private byte[] hexToBytes(String value) {
        if (value.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                return new byte[0];
            }
            bytes[i / 2] = (byte) ((high << 4) + low);
        }
        return bytes;
    }

    private void writeAudit(
            Long planId,
            Long stepId,
            Long caseId,
            String providerMsgId,
            String result,
            String disposition,
            String canonical,
            String signature,
            boolean signatureValid) {
        ChannelCallbackAudit audit = new ChannelCallbackAudit();
        audit.setPlanId(planId);
        audit.setStepId(stepId);
        audit.setCaseId(caseId);
        audit.setProviderMsgId(providerMsgId);
        audit.setResult(result);
        audit.setDisposition(disposition);
        audit.setCanonicalPayload(canonical);
        audit.setSignature(signature);
        audit.setSignatureValid(signatureValid);
        callbackAuditRepository.save(audit);
    }
}
