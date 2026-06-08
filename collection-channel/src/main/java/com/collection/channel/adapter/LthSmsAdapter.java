package com.collection.channel.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * LTH SMS 同步渠道 Adapter。
 *
 * <p>读 {@link StepCommand#META_SMS_BODY}；HTTP 成功 → DELIVERED。
 * 5xx/超时 → retryable；非法号码 → 非 retryable。
 *
 * @see docs/MOCASA催收系统升级_Phase1_LTH_SMS对接说明.md
 */
@Component
public class LthSmsAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(LthSmsAdapter.class);

    @Resource
    private ChannelProperties properties;

    @Resource
    private RestTemplate channelRestTemplate;

    @Override
    public ChannelType channelType() {
        return ChannelType.SMS;
    }

    @Override
    public StepResult send(StepCommand command) {
        if (!properties.isLthSmsConfigured()) {
            log.error("[LthSmsAdapter] channel.lth.sms.url not configured");
            return AdapterSupport.notConfigured("LTH_SMS");
        }

        String phone = command.getTargetAddress();
        if (StringUtils.isBlank(phone)) {
            return AdapterSupport.permanentFailure("INVALID_MSISDN");
        }

        String body = AdapterSupport.metadataString(command, StepCommand.META_SMS_BODY);
        if (StringUtils.isBlank(body)) {
            body = "[MOCK] SMS from " + AdapterSupport.metadataString(command, StepCommand.META_SCRIPT_SLOT);
        }

        ChannelProperties.Lth.Sms smsCfg = properties.getLth().getSms();
        Map<String, Object> payload = new HashMap<>();
        payload.put("mobile", phone);
        payload.put("content", body);
        if (StringUtils.isNotBlank(smsCfg.getSenderId())) {
            payload.put("senderId", smsCfg.getSenderId());
        }
        if (StringUtils.isNotBlank(command.getIdempotencyKey())) {
            payload.put("idempotencyKey", command.getIdempotencyKey());
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(payload), headers);

            ResponseEntity<String> response = channelRestTemplate.postForEntity(
                    smsCfg.getUrl(), entity, String.class);

            String providerMsgId = extractProviderMsgId(response.getBody());
            log.info("[LthSmsAdapter] sent to {} msgId={} fallback={}",
                    maskPhone(phone), providerMsgId,
                    Boolean.TRUE.equals(command.getMetadata().get(StepCommand.META_FALLBACK_SMS)));
            return AdapterSupport.delivered(providerMsgId);
        } catch (Exception e) {
            log.warn("[LthSmsAdapter] send failed phone={}: {}", maskPhone(phone), e.getMessage());
            return AdapterSupport.mapHttpException("LTH", e);
        }
    }

    /** 构建 Push fallback 用的 SMS 命令（同槽，独立幂等键）。 */
    public StepCommand buildFallbackCommand(StepCommand pushCommand, String phone) {
        Map<String, Object> meta = new HashMap<>(pushCommand.getMetadata());
        meta.put(StepCommand.META_FALLBACK_SMS, true);
        if (!meta.containsKey(StepCommand.META_SMS_BODY)) {
            meta.put(StepCommand.META_SMS_BODY,
                    "[Fallback SMS] " + meta.getOrDefault(StepCommand.META_SCRIPT_SLOT, "PUSH_FALLBACK"));
        }
        String fallbackKey = pushCommand.getIdempotencyKey() + ":sms_fallback";
        return StepCommand.builder()
                .channelType(ChannelType.SMS)
                .targetAddress(phone)
                .templateId(pushCommand.getTemplateId())
                .idempotencyKey(fallbackKey)
                .metadata(meta)
                .build();
    }

    private static String extractProviderMsgId(String body) {
        if (StringUtils.isBlank(body)) {
            return "lth-" + UUID.randomUUID();
        }
        try {
            JSONObject json = JSON.parseObject(body);
            if (json.containsKey("msgId")) {
                return json.getString("msgId");
            }
            if (json.containsKey("messageId")) {
                return json.getString("messageId");
            }
        } catch (Exception ignored) {
            // 非 JSON 响应
        }
        return "lth-" + UUID.randomUUID();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "****" + phone.substring(phone.length() - 4);
    }
}
