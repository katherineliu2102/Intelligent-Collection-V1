package com.collection.channel.adapter;

import com.alibaba.fastjson.JSON;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * SendGrid Email 同步渠道 Adapter。
 *
 * <p>HTTP 202 → DELIVERED + STEP_COMPLETED；Event Webhook 不完成 step。 无邮箱由 ExecutionGuard 拦截，Adapter
 * 侧仍校验 targetAddress。
 *
 * @see docs/MOCASA催收系统升级_Phase1_SendGrid_Email对接说明.md
 */
@Component
public class SendGridEmailAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailAdapter.class);
    private static final String DEFAULT_SEND_URL = "https://api.sendgrid.com/v3/mail/send";

    @Resource private ChannelProperties properties;

    @Resource private RestTemplate channelRestTemplate;

    @Override
    public ChannelType channelType() {
        return ChannelType.EMAIL;
    }

    @Override
    public StepResult send(StepCommand command) {
        if (!properties.isSendGridConfigured()) {
            log.error("[SendGridEmailAdapter] channel.sendgrid not configured");
            return AdapterSupport.notConfigured("SENDGRID");
        }

        String email = command.getTargetAddress();
        if (StringUtils.isBlank(email) || !email.contains("@")) {
            return AdapterSupport.permanentFailure("NO_EMAIL");
        }

        String templateId = resolveTemplateId(command);
        if (StringUtils.isBlank(templateId)) {
            return AdapterSupport.permanentFailure("SENDGRID_NO_TEMPLATE");
        }

        Map<String, Object> payload = buildPayload(command, email, templateId);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(properties.getSendgrid().getApiKey());
            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(payload), headers);

            ResponseEntity<String> response =
                    channelRestTemplate.postForEntity(resolveSendUrl(), entity, String.class);
            // SendGrid 正常受理固定 202 Accepted；其余 2xx 不视为成功，避免误报 DELIVERED
            int status = response.getStatusCodeValue();
            if (status != 202) {
                log.warn(
                        "[SendGridEmailAdapter] unexpected status={} email={} (expected 202)",
                        status,
                        maskEmail(email));
                return AdapterSupport.permanentFailure("SENDGRID_UNEXPECTED_STATUS_" + status);
            }
            String msgId = response.getHeaders().getFirst("X-Message-Id");
            if (StringUtils.isBlank(msgId)) {
                msgId = "sg-" + command.getIdempotencyKey();
            }
            log.info("[SendGridEmailAdapter] accepted email={} msgId={}", maskEmail(email), msgId);
            return AdapterSupport.delivered(msgId);
        } catch (Exception e) {
            log.warn(
                    "[SendGridEmailAdapter] send failed email={}: {}",
                    maskEmail(email),
                    e.getMessage());
            return AdapterSupport.mapHttpException("SENDGRID", e);
        }
    }

    private String resolveTemplateId(StepCommand command) {
        String tid = command.getTemplateId();
        if (StringUtils.isNotBlank(tid) && tid.startsWith("d-")) {
            return tid;
        }
        String scriptSlot = AdapterSupport.metadataString(command, StepCommand.META_SCRIPT_SLOT);
        if (StringUtils.isBlank(scriptSlot) && StringUtils.isNotBlank(tid)) {
            scriptSlot = tid;
        }
        if (StringUtils.isNotBlank(scriptSlot)) {
            Map<String, String> templates = properties.getSendgrid().getTemplates();
            if (templates != null) {
                String mapped = templates.get(scriptSlot);
                if (StringUtils.isNotBlank(mapped)) {
                    return mapped;
                }
            }
        }
        log.error(
                "[SendGridEmailAdapter] no template mapping for scriptSlot={} tid={}",
                scriptSlot,
                tid);
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPayload(StepCommand command, String email, String templateId) {
        Map<String, Object> dynamicData = new HashMap<>();
        Object raw = command.getMetadata().get(StepCommand.META_DYNAMIC_TEMPLATE_DATA);
        if (raw instanceof Map) {
            dynamicData.putAll((Map<String, Object>) raw);
        }

        Map<String, String> customArgs = new HashMap<>();
        Object caseId = command.getMetadata().get(StepCommand.META_CASE_ID);
        if (caseId != null) {
            customArgs.put("case_id", String.valueOf(caseId));
        }
        customArgs.put("idempotency_key", command.getIdempotencyKey());
        String scriptSlot = AdapterSupport.metadataString(command, StepCommand.META_SCRIPT_SLOT);
        if (scriptSlot != null) {
            customArgs.put("script_slot", scriptSlot);
        }

        Map<String, Object> personalization = new HashMap<>();
        personalization.put(
                "to", Collections.singletonList(Collections.singletonMap("email", email)));
        personalization.put("dynamic_template_data", dynamicData);
        personalization.put("custom_args", customArgs);

        ChannelProperties.SendGrid sg = properties.getSendgrid();
        Map<String, Object> payload = new HashMap<>();
        payload.put("personalizations", Collections.singletonList(personalization));
        payload.put("from", buildFrom(sg));
        payload.put("template_id", templateId);
        if (sg.getUnsubscribeGroupId() > 0) {
            payload.put("asm", Collections.singletonMap("group_id", sg.getUnsubscribeGroupId()));
        }
        return payload;
    }

    private static Map<String, String> buildFrom(ChannelProperties.SendGrid sg) {
        Map<String, String> from = new HashMap<>();
        from.put("email", sg.getFromEmail());
        if (StringUtils.isNotBlank(sg.getFromName())) {
            from.put("name", sg.getFromName());
        }
        return from;
    }

    private String resolveSendUrl() {
        String url = properties.getSendgrid().getApiUrl();
        return url != null && !url.isEmpty() ? url : DEFAULT_SEND_URL;
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "****";
        }
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }
}
