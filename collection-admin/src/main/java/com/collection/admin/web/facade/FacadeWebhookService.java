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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Resource private JdbcTemplate jdbcTemplate;

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

        writeAiCallSession(root, identity, planId, stepId);

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
        try {
            callbackAuditRepository.save(audit);
        } catch (RuntimeException e) {
            log.error(
                    "[facade-callback] audit insert failed session={} planId={} stepId={}",
                    providerMsgId,
                    planId,
                    stepId,
                    e);
        }
    }

    /** AI Call 会话底座写入：结构化提取 session.completed 回调原生词，落 t_ai_call_session（§5.1.4 看板聚合用）。 */
    private void writeAiCallSession(
            JsonNode root, FacadeCallbackMapper.Identity identity, Long planId, Long stepId) {
        if (identity.sessionId == null) {
            return;
        }
        JsonNode line = root.path("line_outcome");
        JsonNode aiResult = root.path("ai_result");
        JsonNode parties = root.path("parties");
        JsonNode dial = root.path("dial_timeline");
        JsonNode promises = aiResult.path("promises");
        Long resolvedPlanId = planId != null ? planId : identity.planId;
        Long resolvedStepId = stepId != null ? stepId : identity.stepId;
        Long caseId = identity.caseId;
        String stageSnapshot = null;
        Integer dpdSnapshot = null;
        if (resolvedPlanId != null) {
            try {
                ContactPlan plan = planRepository.findById(resolvedPlanId);
                if (plan != null) {
                    if (plan.getStage() != null) {
                        stageSnapshot = plan.getStage().name();
                    }
                    if (caseId == null) {
                        caseId = plan.getCaseId();
                    }
                }
            } catch (RuntimeException e) {
                log.warn(
                        "[facade-callback] stage snapshot lookup failed plan={}",
                        resolvedPlanId,
                        e);
            }
        }
        if (caseId != null) {
            try {
                Map<String, Object> projection =
                        jdbcTemplate.queryForMap(
                                "SELECT stage, dpd FROM t_ai_collection WHERE case_id = ?", caseId);
                if (stageSnapshot == null && projection.get("stage") != null) {
                    stageSnapshot = String.valueOf(projection.get("stage"));
                }
                if (projection.get("dpd") instanceof Number) {
                    dpdSnapshot = ((Number) projection.get("dpd")).intValue();
                }
            } catch (EmptyResultDataAccessException ignored) {
                // 投影尚未落库时快照留空，禁止用别的时点回填
            } catch (RuntimeException e) {
                log.warn("[facade-callback] stage/dpd snapshot lookup failed caseId={}", caseId, e);
            }
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_ai_call_session "
                            + "(session_id, batch_id, case_id, plan_id, step_id, event, "
                            + "was_ringing, was_answered, was_ai_connected, line_reason, sip_code, "
                            + "final_failure_reason, result_label, summary, promises_json, caller_cli, "
                            + "dialed_at, answered_at, ended_at, stage_snapshot, dpd_snapshot, received_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, NOW()) "
                            + "ON DUPLICATE KEY UPDATE "
                            + "was_ringing=VALUES(was_ringing), was_answered=VALUES(was_answered), "
                            + "was_ai_connected=VALUES(was_ai_connected), line_reason=VALUES(line_reason), "
                            + "sip_code=VALUES(sip_code), final_failure_reason=VALUES(final_failure_reason), "
                            + "result_label=VALUES(result_label), summary=VALUES(summary), "
                            + "promises_json=VALUES(promises_json), caller_cli=VALUES(caller_cli), "
                            + "dialed_at=VALUES(dialed_at), answered_at=VALUES(answered_at), ended_at=VALUES(ended_at), "
                            + "stage_snapshot=COALESCE(stage_snapshot, VALUES(stage_snapshot)), "
                            + "dpd_snapshot=COALESCE(dpd_snapshot, VALUES(dpd_snapshot))",
                    identity.sessionId,
                    identity.batchId,
                    caseId,
                    resolvedPlanId,
                    resolvedStepId,
                    textOf(root, "event"),
                    boolOf(line, "was_ringing"),
                    boolOf(line, "was_answered"),
                    boolOf(line, "was_ai_connected"),
                    textOf(line, "reason"),
                    textOf(line, "sip_code"),
                    textOf(root, "final_failure_reason"),
                    firstNonBlank(textOf(aiResult, "result_label")),
                    firstNonBlank(textOf(aiResult, "summary"), textOf(root, "summary")),
                    promises.isArray() ? promises.toString() : null,
                    textOf(parties, "caller_cli"),
                    tsOf(textOf(dial, "dialed_at")),
                    tsOf(textOf(dial, "answered_at")),
                    tsOf(textOf(dial, "ended_at")),
                    stageSnapshot,
                    dpdSnapshot);
        } catch (RuntimeException e) {
            log.warn(
                    "[facade-callback] ai_call_session upsert failed session={}",
                    identity.sessionId,
                    e);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (int i = 0; i < values.length; i++) {
            if (StringUtils.isNotBlank(values[i])) {
                return values[i].trim();
            }
        }
        return null;
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v == null || v.isNull() || v.isMissingNode()) ? null : v.asText();
    }

    private static Boolean boolOf(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v != null && v.isBoolean()) ? v.booleanValue() : null;
    }

    private static java.sql.Timestamp tsOf(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            if (raw.length() >= 19) {
                String head = raw.substring(0, 19).replace('T', ' ');
                return java.sql.Timestamp.valueOf(head);
            }
        } catch (IllegalArgumentException e) {
            // 非法时间格式按未回传处理
        }
        return null;
    }
}
