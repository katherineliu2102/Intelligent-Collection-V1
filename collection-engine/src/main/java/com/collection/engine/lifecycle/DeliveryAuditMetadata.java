package com.collection.engine.lifecycle;

import com.collection.common.dto.StepCommand;
import com.collection.common.model.ContactRecord;
import com.collection.engine.config.EngineProperties;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** 将已解析命令转换为不含 PII 正文的触达审计元数据。 */
@Component
public class DeliveryAuditMetadata {

    @Resource private EngineProperties properties;

    public void apply(ContactRecord record, StepCommand command) {
        if (command == null) {
            return;
        }
        String scriptSlot = stringMetadata(command, StepCommand.META_SCRIPT_SLOT);
        String templateVersion = stringMetadata(command, StepCommand.META_TEMPLATE_VERSION);
        Long configVersion = longMetadata(command, StepCommand.META_CONFIG_VERSION);
        record.setConfigVersion(configVersion);
        record.setScriptSlot(scriptSlot);
        record.setTemplateVersion(templateVersion);
        record.setRenderedRef(
                command.getChannelType()
                        + ":"
                        + nullSafe(scriptSlot)
                        + "@"
                        + nullSafe(templateVersion));
        record.setContentSummary(
                "channel="
                        + command.getChannelType()
                        + ";slot="
                        + nullSafe(scriptSlot)
                        + ";templateVersion="
                        + nullSafe(templateVersion)
                        + ";fields="
                        + renderedFieldNames(command));

        String key = properties.getDeliveryAudit().getHmacKey();
        String keyId = properties.getDeliveryAudit().getContentKeyId();
        if (key == null || key.isEmpty() || keyId == null || keyId.isEmpty()) {
            return;
        }
        record.setContentHmac(hmacSha256(key, canonicalRenderedPayload(command)));
        record.setContentKeyId(keyId);
    }

    private String canonicalRenderedPayload(StepCommand command) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("channel", String.valueOf(command.getChannelType()));
        payload.put("templateId", command.getTemplateId());
        payload.put("scriptSlot", stringMetadata(command, StepCommand.META_SCRIPT_SLOT));
        payload.put("smsBody", command.getMetadata().get(StepCommand.META_SMS_BODY));
        payload.put("title", command.getMetadata().get(StepCommand.META_TITLE));
        payload.put("body", command.getMetadata().get(StepCommand.META_BODY));
        payload.put(
                "dynamicTemplateData",
                command.getMetadata().get(StepCommand.META_DYNAMIC_TEMPLATE_DATA));
        return payload.toString();
    }

    private String renderedFieldNames(StepCommand command) {
        Map<String, Object> fields = new TreeMap<>();
        for (String key :
                new String[] {
                    StepCommand.META_SMS_BODY,
                    StepCommand.META_TITLE,
                    StepCommand.META_BODY,
                    StepCommand.META_DYNAMIC_TEMPLATE_DATA
                }) {
            if (command.getMetadata().containsKey(key)) {
                fields.put(key, "");
            }
        }
        return fields.keySet().toString();
    }

    private static String hmacSha256(String key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute delivery content HMAC", e);
        }
    }

    private static String stringMetadata(StepCommand command, String key) {
        Object value = command.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longMetadata(StepCommand command, String key) {
        Object value = command.getMetadata().get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
