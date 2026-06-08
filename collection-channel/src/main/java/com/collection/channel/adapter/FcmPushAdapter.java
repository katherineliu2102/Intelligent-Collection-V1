package com.collection.channel.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import com.google.auth.oauth2.GoogleCredentials;
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
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * FCM Push 同步渠道 Adapter。
 *
 * <p>无 fcmToken 或 FCM 永久失败时，同槽 fallback {@link LthSmsAdapter}。
 * Voice 类业务结果（NO_ANSWER/BUSY/REJECTED）不适用本 Adapter；见 §3.1。
 *
 * @see docs/MOCASA催收系统升级_Phase1_FCM_Push对接说明.md
 */
@Component
public class FcmPushAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(FcmPushAdapter.class);
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    @Resource
    private ChannelProperties properties;

    @Resource
    private RestTemplate channelRestTemplate;

    @Resource
    private LthSmsAdapter lthSmsAdapter;

    @Override
    public ChannelType channelType() {
        return ChannelType.PUSH;
    }

    @Override
    public StepResult send(StepCommand command) {
        String token = command.getTargetAddress();
        if (StringUtils.isBlank(token) || token.startsWith("639")) {
            log.info("[FcmPushAdapter] no valid fcmToken, fallback SMS");
            return fallbackSms(command, resolvePhone(command));
        }

        if (!properties.isFcmConfigured()) {
            log.error("[FcmPushAdapter] channel.fcm not configured");
            return AdapterSupport.notConfigured("FCM");
        }

        try {
            String accessToken = obtainAccessToken();
            String url = "https://fcm.googleapis.com/v1/projects/"
                    + properties.getFcm().getProjectId() + "/messages:send";

            Map<String, Object> data = buildDataPayload(command);
            Map<String, Object> message = new HashMap<>();
            message.put("token", token);
            message.put("data", data);
            Map<String, Object> body = Collections.singletonMap("message", message);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken);
            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);

            ResponseEntity<String> response = channelRestTemplate.postForEntity(url, entity, String.class);
            String msgId = extractFcmMsgId(response.getBody());
            log.info("[FcmPushAdapter] sent msgId={}", msgId);
            return AdapterSupport.delivered(msgId);
        } catch (Exception e) {
            log.warn("[FcmPushAdapter] send failed: {}", e.getMessage());
            StepResult mapped = AdapterSupport.mapHttpException("FCM", e);
            if (!mapped.isRetryable()) {
                return fallbackSms(command, resolvePhone(command));
            }
            return mapped;
        }
    }

    private StepResult fallbackSms(StepCommand pushCommand, String phone) {
        if (StringUtils.isBlank(phone)) {
            return AdapterSupport.permanentFailure("FCM_INVALID_TOKEN");
        }
        StepCommand smsCommand = lthSmsAdapter.buildFallbackCommand(pushCommand, phone);
        return lthSmsAdapter.send(smsCommand);
    }

    private Map<String, Object> buildDataPayload(StepCommand command) {
        Map<String, Object> data = new HashMap<>();
        String scriptSlot = AdapterSupport.metadataString(command, StepCommand.META_SCRIPT_SLOT);
        data.put("title", "MOCASA Payment Reminder");
        data.put("body", scriptSlot != null ? scriptSlot : "Please complete your payment");
        Object templateData = command.getMetadata().get(StepCommand.META_DYNAMIC_TEMPLATE_DATA);
        if (templateData instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) templateData;
            if (map.containsKey("payment_link")) {
                data.put("deep_link", String.valueOf(map.get("payment_link")));
            }
        }
        Object caseId = command.getMetadata().get(StepCommand.META_CASE_ID);
        if (caseId != null) {
            data.put("case_id", String.valueOf(caseId));
        }
        return data;
    }

    private String resolvePhone(StepCommand command) {
        Object phone = command.getMetadata().get("fallbackPhone");
        return phone == null ? null : String.valueOf(phone);
    }

    private String obtainAccessToken() throws Exception {
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new ByteArrayInputStream(
                        properties.getFcm().getServiceAccountJson().getBytes(StandardCharsets.UTF_8)))
                .createScoped(Collections.singleton(FCM_SCOPE));
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    private static String extractFcmMsgId(String body) {
        if (StringUtils.isBlank(body)) {
            return "fcm-unknown";
        }
        try {
            JSONObject json = JSON.parseObject(body);
            if (json.containsKey("name")) {
                return json.getString("name");
            }
        } catch (Exception ignored) {
            // ignore
        }
        return "fcm-" + body.hashCode();
    }
}
