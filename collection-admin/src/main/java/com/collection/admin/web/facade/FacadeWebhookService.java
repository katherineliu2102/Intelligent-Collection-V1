package com.collection.admin.web.facade;

import com.collection.admin.web.WebhookSecurityProperties;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.EventType;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ChannelCallbackAudit;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ChannelCallbackAuditRepository;
import com.collection.common.repository.ContactPlanRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Facade {@code POST /webhook/facade-callback}。验签与映射按入站交接 + 手册 §11.3； 事件 {@code disposition}
 * 留空以免引擎把原生词当成 ANSWERED。
 */
@Service
public class FacadeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(FacadeWebhookService.class);

    @Resource private CollectionEventBus eventBus;
    @Resource private ChannelCallbackAuditRepository callbackAuditRepository;
    @Resource private ContactPlanRepository planRepository;
    @Resource private ChannelProperties channelProperties;
    @Resource private WebhookSecurityProperties webhookSecurityProperties;

    public Map<String, Object> handle(JsonNode root, String signature) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            writeAudit(null, null, null, null, null, null, "null", signature, false);
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "invalid callback signature");
        }

        String canonical = FacadeCanonicalJson.dumps(root);
        boolean signatureValid = signatureValid(canonical, signature);
        FacadeCallbackMapper.Identity identity = FacadeCallbackMapper.identity(root);
        ContactResult mapped =
                FacadeCallbackMapper.isSessionCompleted(root)
                        ? FacadeCallbackMapper.mapResult(root)
                        : null;

        if (!signatureValid) {
            writeAudit(
                    identity.planId,
                    identity.stepId,
                    identity.caseId,
                    identity.sessionId,
                    mapped == null ? null : mapped.name(),
                    null,
                    canonical,
                    signature,
                    false);
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "invalid callback signature");
        }

        Map<String, Object> ok = new HashMap<String, Object>();
        ok.put("ok", true);
        if (!FacadeCallbackMapper.isSessionCompleted(root)) {
            writeAudit(
                    identity.planId,
                    identity.stepId,
                    identity.caseId,
                    identity.sessionId,
                    null,
                    null,
                    canonical,
                    signature,
                    true);
            return ok;
        }

        boolean alreadyPublished =
                StringUtils.isNotBlank(identity.sessionId)
                        && callbackAuditRepository.existsValidByProviderMsgId(identity.sessionId);
        if (alreadyPublished) {
            writeAudit(
                    identity.planId,
                    identity.stepId,
                    identity.caseId,
                    identity.sessionId,
                    mapped.name(),
                    null,
                    canonical,
                    signature,
                    true);
            log.info(
                    "[facade-callback] duplicate session_id={}, skip CHANNEL_CALLBACK",
                    identity.sessionId);
            return ok;
        }

        Long planId = identity.planId;
        Long stepId = identity.stepId;
        if (!identity.hasPlanAndStep()) {
            ContactPlanStep found = findUniqueExecutingAiCall(identity.caseId);
            if (found == null) {
                writeAudit(
                        null,
                        null,
                        identity.caseId,
                        identity.sessionId,
                        mapped.name(),
                        null,
                        canonical,
                        signature,
                        true);
                log.warn(
                        "[facade-callback] cannot resolve unique EXECUTING AI_CALL session={} caseId={}",
                        identity.sessionId,
                        identity.caseId);
                return ok;
            }
            planId = found.getPlanId();
            stepId = found.getId();
        }

        writeAudit(
                planId,
                stepId,
                identity.caseId,
                identity.sessionId,
                mapped.name(),
                null,
                canonical,
                signature,
                true);

        if (mapped == ContactResult.FAILED) {
            log.warn(
                    "[facade-callback] unmapped/failed reason session={} reason={}",
                    identity.sessionId,
                    root.path("line_outcome").path("reason").asText());
        }

        eventBus.publish(
                CollectionEvent.of(EventType.CHANNEL_CALLBACK)
                        .with(CollectionEvent.PLAN_ID, planId)
                        .with(CollectionEvent.STEP_ID, stepId)
                        .with(CollectionEvent.CASE_ID, identity.caseId)
                        .with(CollectionEvent.RESULT, mapped.name())
                        .with(CollectionEvent.PROVIDER_MSG_ID, identity.batchId));
        return ok;
    }

    private ContactPlanStep findUniqueExecutingAiCall(Long caseId) {
        if (caseId == null) {
            return null;
        }
        List<ContactPlan> plans = planRepository.findActivePlansByCase(caseId);
        if (plans == null || plans.isEmpty()) {
            return null;
        }
        List<ContactPlanStep> hits = new ArrayList<ContactPlanStep>();
        for (ContactPlan plan : plans) {
            if (plan.getStatus() != PlanStatus.STEP_EXECUTING
                    && plan.getStatus() != PlanStatus.STEP_WAITING) {
                continue;
            }
            List<ContactPlanStep> steps = planRepository.findStepsByPlan(plan.getId());
            if (steps == null) {
                continue;
            }
            for (ContactPlanStep step : steps) {
                if (step.getChannelType() == ChannelType.AI_CALL
                        && step.getStatus() == StepStatus.EXECUTING) {
                    hits.add(step);
                }
            }
        }
        if (hits.size() != 1) {
            log.warn(
                    "[facade-callback] EXECUTING AI_CALL count={} for caseId={}",
                    hits.size(),
                    caseId);
            return null;
        }
        return hits.get(0);
    }

    private boolean signatureValid(String canonical, String signature) {
        if (!webhookSecurityProperties.isSignatureRequired()) {
            return true;
        }
        String secret = channelProperties.getFacade().getCallbackSecret();
        if (signature == null || StringUtils.isBlank(secret)) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, hexToBytes(signature.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] hexToBytes(String value) {
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
