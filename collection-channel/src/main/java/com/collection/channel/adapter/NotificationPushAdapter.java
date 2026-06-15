package com.collection.channel.adapter;

import com.collection.channel.client.NotificationClient;
import com.collection.channel.client.NotificationResponse;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 通知中心 App Push 异步渠道 Adapter（{@code POST /v1/app_notification/send}，底层极光 JPush）。
 *
 * <p>入队即受理（{@code code=0} → DELIVERED，无 providerMsgId，类比 SendGrid 202）；
 * 无 {@code jpushToken} → 同槽 fallback 走 {@link NotificationSmsAdapter}（对引擎一次 dispatch）。
 * 结果映射见 Notification 对接说明 §2.4 / §9.2。
 *
 * <p>Phase 1：业务码失败（含 81 参数错误）直接 FAILED，不自动 fallback；送达/卸载不回传、不改 StepResult。
 *
 * @see docs/channel/MOCASA催收系统升级_Phase1_Notification对接说明.md
 */
@Component
public class NotificationPushAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(NotificationPushAdapter.class);
    private static final String PUSH_PATH = "/v1/app_notification/send";
    private static final String PUSH_SYNC_PATH = "/v1/app_notification/sync/send";

    @Resource
    private ChannelProperties properties;

    @Resource
    private NotificationClient notificationClient;

    @Resource
    private NotificationSmsAdapter notificationSmsAdapter;

    @Override
    public ChannelType channelType() {
        return ChannelType.PUSH;
    }

    @Override
    public StepResult send(StepCommand command) {
        String token = command.getTargetAddress();
        String fallbackPhone = AdapterSupport.metadataString(command, "fallbackPhone");

        // 无有效 jpushToken（StepResolver 用手机号占位并写 fallbackPhone）→ 同槽改 SMS
        if (StringUtils.isBlank(token) || token.equals(fallbackPhone)) {
            log.info("[NotificationPushAdapter] no jpushToken, fallback SMS phone={}",
                    fallbackPhone != null ? "****" : null);
            return fallbackSms(command, fallbackPhone);
        }

        if (!notificationClient.isConfigured()) {
            log.error("[NotificationPushAdapter] channel.notification not configured");
            return AdapterSupport.notConfigured("NOTIFICATION");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("token", token);
        String title = AdapterSupport.metadataString(command, StepCommand.META_TITLE);
        String body = AdapterSupport.metadataString(command, StepCommand.META_BODY);
        if (StringUtils.isNotBlank(title)) {
            payload.put("title", title);
        }
        if (StringUtils.isNotBlank(body)) {
            payload.put("body", body);
        }
        String data = AdapterSupport.metadataString(command, StepCommand.META_PUSH_DATA);
        if (StringUtils.isNotBlank(data)) {
            payload.put("data", data);
        }

        Object caseId = command.getMetadata().get(StepCommand.META_CASE_ID);
        boolean syncMode = properties.getNotification().isPushSyncMode();
        String path = syncMode ? PUSH_SYNC_PATH : PUSH_PATH;
        try {
            NotificationResponse resp = notificationClient.post(path, payload);
            if (resp.isCodeSuccess()) {
                if (syncMode) {
                    // 同步：可见极光真实受理结果（requestSuccess/requestId）
                    if (resp.isRequestSuccess()) {
                        log.info("[NotificationPushAdapter] sync accepted caseId={} requestId={}",
                                caseId, resp.getRequestId());
                        return AdapterSupport.delivered(resp.getRequestId());
                    }
                    log.warn("[NotificationPushAdapter] sync rejected caseId={} (token invalid/uninstalled?)", caseId);
                    return AdapterSupport.permanentFailure("NOTIFICATION_PUSH_REJECTED");
                }
                // 异步：入队即受理，无 providerMsgId（类比 SendGrid 202）
                log.info("[NotificationPushAdapter] enqueued caseId={} token=*** (no providerMsgId)", caseId);
                return AdapterSupport.delivered(null);
            }
            String errorCode = AdapterSupport.notificationErrorCode(resp.getCode());
            log.warn("[NotificationPushAdapter] failed caseId={} code={} msg={} errorCode={}",
                    caseId, resp.getCode(), resp.getMsg(), errorCode);
            return AdapterSupport.permanentFailure(errorCode);
        } catch (RestClientException e) {
            log.warn("[NotificationPushAdapter] transient failure caseId={}: {}", caseId, e.getMessage());
            return AdapterSupport.notificationTimeout();
        }
    }

    private StepResult fallbackSms(StepCommand pushCommand, String phone) {
        if (StringUtils.isBlank(phone)) {
            return AdapterSupport.permanentFailure("PUSH_NO_TOKEN_NO_PHONE");
        }
        StepCommand smsCommand = notificationSmsAdapter.buildFallbackCommand(pushCommand, phone);
        return notificationSmsAdapter.send(smsCommand);
    }
}
