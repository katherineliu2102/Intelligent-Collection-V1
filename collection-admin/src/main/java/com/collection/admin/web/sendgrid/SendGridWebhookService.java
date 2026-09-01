package com.collection.admin.web.sendgrid;

import com.collection.admin.web.WebhookSecurityProperties;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.enums.ContactResult;
import com.collection.common.model.ChannelCallbackAudit;
import com.collection.common.model.EmailSuppression;
import com.collection.common.repository.ChannelCallbackAuditRepository;
import com.collection.common.repository.EmailSuppressionRepository;
import com.collection.common.repository.TimelineRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * SendGrid Event Webhook：{@code POST /webhook/sendgrid}。
 *
 * <p>与 {@code channel-callback} / {@code facade-callback} 的关键区别是<b>不发 CHANNEL_CALLBACK</b>。 Email
 * 的步骤终态在 202 受理时就由 Adapter 定了，这里的事件只补充投递后的事实（打开、点击、退信）。 若发事件驱动引擎，一次 open 就会把已完成的 step 再推进一次。
 *
 * <p><b>幂等</b>：不另设去重表。升级链写入天然幂等（重放低阶事件不生效），未送达类改写是同值覆盖， 抑制名单是 INSERT
 * IGNORE——三条路径重复投递都收敛到同一状态，供应商重试无需额外拦截。
 */
@Service
public class SendGridWebhookService {

    private static final Logger log = LoggerFactory.getLogger(SendGridWebhookService.class);

    /** 审计表无渠道列，用 disposition 标出来源，便于与 Facade 的验签失败行区分。 */
    private static final String AUDIT_TAG = "SENDGRID_EVENT_WEBHOOK";

    private static final int AUDIT_PAYLOAD_MAX = 2000;

    private final ObjectMapper objectMapper;
    private final ChannelProperties channelProperties;
    private final WebhookSecurityProperties webhookSecurityProperties;
    private final TimelineRepository timelineRepository;
    private final EmailSuppressionRepository emailSuppressionRepository;
    private final ChannelCallbackAuditRepository callbackAuditRepository;

    public SendGridWebhookService(
            ObjectMapper objectMapper,
            ChannelProperties channelProperties,
            WebhookSecurityProperties webhookSecurityProperties,
            TimelineRepository timelineRepository,
            EmailSuppressionRepository emailSuppressionRepository,
            ChannelCallbackAuditRepository callbackAuditRepository) {
        this.objectMapper = objectMapper;
        this.channelProperties = channelProperties;
        this.webhookSecurityProperties = webhookSecurityProperties;
        this.timelineRepository = timelineRepository;
        this.emailSuppressionRepository = emailSuppressionRepository;
        this.callbackAuditRepository = callbackAuditRepository;
    }

    public Map<String, Object> handle(byte[] rawBody, String signature, String timestamp) {
        verifyOrReject(rawBody, signature, timestamp);

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody == null ? new byte[0] : rawBody);
        } catch (Exception e) {
            // 验签已过却解析不出 JSON：报文确实来自 SendGrid，重投同样解不开，按毒丸吞掉。
            log.error("[sendgrid-webhook] signed body is not valid JSON", e);
            return counters(0, 0, 0, 0);
        }

        Counters counters = new Counters();
        if (root.isArray()) {
            for (JsonNode event : root) {
                applyOne(event, counters);
            }
        } else if (root.isObject()) {
            applyOne(root, counters);
        }

        log.info(
                "[sendgrid-webhook] applied={} suppressed={} skipped={} failed={}",
                counters.applied,
                counters.suppressed,
                counters.skipped,
                counters.failed);

        if (counters.failed > 0) {
            // 让 SendGrid 重投整批：三条写入路径都幂等，已成功的事件重放不会产生副作用，
            // 而静默吞掉持久化故障会让退信永远不进抑制名单。
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "partial persistence failure, please retry");
        }
        return counters(counters.applied, counters.suppressed, counters.skipped, counters.failed);
    }

    private void applyOne(JsonNode event, Counters counters) {
        SendGridEventMapper.Action action = SendGridEventMapper.map(event);
        if (action.isIgnored()) {
            return;
        }
        String attemptKey = event.path("idempotency_key").asText("");
        String email = event.path("email").asText("");
        boolean writable = action.getResult() != null && StringUtils.isNotBlank(attemptKey);
        if (action.getResult() != null && !writable) {
            // 没有 custom_args 就无法定位 timeline 行。多为控制台手动测试事件或早于本次改动发出的信，
            // 重投也补不出关联键，故记 skipped 并返回 2xx，不让供应商无限重试。
            counters.skipped++;
            log.warn(
                    "[sendgrid-webhook] event without idempotency_key, event={} sgEventId={}",
                    event.path("event").asText(""),
                    event.path("sg_event_id").asText(""));
        }

        try {
            if (writable) {
                int updated = writeResult(event, action, attemptKey);
                if (updated > 0) {
                    counters.applied++;
                } else {
                    counters.skipped++;
                }
            }
            if (action.getSuppressionReason() != null) {
                if (suppress(event, action, email)) {
                    counters.suppressed++;
                }
            }
        } catch (RuntimeException e) {
            counters.failed++;
            log.error(
                    "[sendgrid-webhook] apply failed event={} attemptKey={} sgEventId={}",
                    event.path("event").asText(""),
                    attemptKey,
                    event.path("sg_event_id").asText(""),
                    e);
        }
    }

    private int writeResult(JsonNode event, SendGridEventMapper.Action action, String attemptKey) {
        ContactResult result = action.getResult();
        String providerMsgId = trimToNull(event.path("sg_message_id").asText(""));
        String callback = event.toString();
        return action.isOverride()
                ? timelineRepository.overrideResult(attemptKey, result, providerMsgId, callback)
                : timelineRepository.upgradeResult(attemptKey, result, providerMsgId, callback);
    }

    private boolean suppress(JsonNode event, SendGridEventMapper.Action action, String email) {
        if (StringUtils.isBlank(email)) {
            log.warn(
                    "[sendgrid-webhook] suppression event without email, sgEventId={}",
                    event.path("sg_event_id").asText(""));
            return false;
        }
        EmailSuppression suppression = new EmailSuppression();
        suppression.setEmail(email);
        suppression.setReason(action.getSuppressionReason());
        suppression.setDetail(SendGridEventMapper.detail(event));
        suppression.setCaseId(longOrNull(event.path("case_id").asText("")));
        emailSuppressionRepository.suppress(suppression);
        // 隔离期内所有 Email 改投自持地址，被抑制的就是自持地址本身：此后全部 EMAIL 步骤会拿
        // EMAIL_SUPPRESSED。这条日志是唯一的早期信号，缺了会误判成"计划不再排 Email"。
        log.warn(
                "[sendgrid-webhook] suppressed reason={} caseId={}",
                action.getSuppressionReason(),
                event.path("case_id").asText(""));
        return true;
    }

    private void verifyOrReject(byte[] rawBody, String signature, String timestamp) {
        if (!webhookSecurityProperties.isSignatureRequired()) {
            return;
        }
        ChannelProperties.SendGrid sg = channelProperties.getSendgrid();
        SendGridEventVerifier.Outcome outcome =
                SendGridEventVerifier.verify(
                        rawBody,
                        signature,
                        timestamp,
                        sg.getEventWebhookPublicKey(),
                        sg.getEventWebhookToleranceSeconds(),
                        Instant.now().getEpochSecond());
        if (outcome.isValid()) {
            return;
        }
        log.warn("[sendgrid-webhook] rejected reason={}", outcome.getReason());
        writeRejectionAudit(rawBody, signature, outcome.getReason());
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid webhook signature");
    }

    /** 验签失败的留痕。此时报文内容不可信，无法反查 planId/stepId，只能记原始片段—— 但「谁在什么时候用什么签名打过这个公网端点」本身就是要留的证据。 */
    private void writeRejectionAudit(byte[] rawBody, String signature, String reason) {
        ChannelCallbackAudit audit = new ChannelCallbackAudit();
        audit.setDisposition(AUDIT_TAG);
        audit.setResult(reason);
        audit.setSignature(signature);
        audit.setSignatureValid(false);
        audit.setCanonicalPayload(truncate(rawBody));
        try {
            callbackAuditRepository.save(audit);
        } catch (RuntimeException e) {
            log.error("[sendgrid-webhook] rejection audit insert failed reason={}", reason, e);
        }
    }

    private static String truncate(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return "";
        }
        String text = new String(rawBody, StandardCharsets.UTF_8);
        return text.length() > AUDIT_PAYLOAD_MAX ? text.substring(0, AUDIT_PAYLOAD_MAX) : text;
    }

    private static String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private static Long longOrNull(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> counters(
            int applied, int suppressed, int skipped, int failed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("applied", applied);
        body.put("suppressed", suppressed);
        body.put("skipped", skipped);
        body.put("failed", failed);
        return body;
    }

    private static final class Counters {
        private int applied;
        private int suppressed;
        private int skipped;
        private int failed;
    }
}
