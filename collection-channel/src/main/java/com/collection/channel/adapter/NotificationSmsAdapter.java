package com.collection.channel.adapter;

import com.collection.channel.client.NotificationClient;
import com.collection.channel.client.NotificationResponse;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * 通知中心 SMS 同步渠道 Adapter（{@code POST /v1/sms/send}，{@code contentType=collection}）。
 *
 * <p>结果映射见 Notification 对接说明 §1.3 / §9.1： {@code code=0 && requestSuccess=true} →
 * DELIVERED（providerMsgId=requestId）； 业务码失败 → FAILED 非 retryable；连接超时/5xx → retryable（{@link
 * NotificationClient} 已短重试）。
 *
 * @see docs/channel/MOCASA催收系统升级_Phase1_Notification对接说明.md
 */
@Component
public class NotificationSmsAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(NotificationSmsAdapter.class);
    private static final String SMS_PATH = "/v1/sms/send";
    private static final String SMS_TEST_PATH = "/v1/sms/testSend";

    @Resource private ChannelProperties properties;

    @Resource private NotificationClient notificationClient;

    @Override
    public ChannelType channelType() {
        return ChannelType.SMS;
    }

    @Override
    public StepResult send(StepCommand command) {
        ChannelProperties.Notification cfg = properties.getNotification();
        boolean testMode = cfg.isSmsTestMode();
        boolean configured =
                testMode
                        ? properties.isNotificationTestConfigured()
                        : notificationClient.isConfigured();
        if (!configured) {
            log.error(
                    "[NotificationSmsAdapter] channel.notification not configured (testMode={})",
                    testMode);
            return AdapterSupport.notConfigured("NOTIFICATION");
        }

        String mobile = normalizeMobile(command.getTargetAddress());
        if (StringUtils.isBlank(mobile)) {
            return AdapterSupport.permanentFailure("INVALID_MSISDN");
        }

        String content = AdapterSupport.metadataString(command, StepCommand.META_SMS_BODY);
        if (StringUtils.isBlank(content)) {
            return AdapterSupport.permanentFailure("NOTIFICATION_EMPTY_CONTENT");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("mobile", mobile);
        payload.put("content", content);
        payload.put("contentType", cfg.getSmsContentType());

        boolean fallback =
                Boolean.TRUE.equals(command.getMetadata().get(StepCommand.META_FALLBACK_SMS));
        try {
            NotificationResponse resp;
            if (testMode) {
                if (StringUtils.isNotBlank(cfg.getSmsTestAccountName())) {
                    payload.put("accountName", cfg.getSmsTestAccountName());
                }
                log.info(
                        "[NotificationSmsAdapter] TEST mode → {} mobile={}",
                        SMS_TEST_PATH,
                        maskPhone(mobile));
                resp = notificationClient.postNoAuth(SMS_TEST_PATH, payload);
            } else {
                resp = notificationClient.post(SMS_PATH, payload);
            }
            return mapResult(command, mobile, fallback, resp);
        } catch (RestClientException e) {
            log.warn(
                    "[NotificationSmsAdapter] transient failure mobile={} fallback={}: {}",
                    maskPhone(mobile),
                    fallback,
                    e.getMessage());
            return AdapterSupport.notificationTimeout();
        }
    }

    private StepResult mapResult(
            StepCommand command, String mobile, boolean fallback, NotificationResponse resp) {
        Object caseId = command.getMetadata().get(StepCommand.META_CASE_ID);
        if (resp.isCodeSuccess() && resp.isRequestSuccess()) {
            log.info(
                    "[NotificationSmsAdapter] accepted caseId={} mobile={} requestId={} channel={} fallback={}",
                    caseId,
                    maskPhone(mobile),
                    resp.getRequestId(),
                    resp.getChannel(),
                    fallback);
            return AdapterSupport.delivered(resp.getRequestId());
        }
        if (resp.isCodeSuccess()) {
            log.warn(
                    "[NotificationSmsAdapter] rejected caseId={} mobile={} channel={}",
                    caseId,
                    maskPhone(mobile),
                    resp.getChannel());
            return AdapterSupport.permanentFailure("NOTIFICATION_SMS_REJECTED");
        }
        String errorCode = AdapterSupport.notificationErrorCode(resp.getCode());
        log.warn(
                "[NotificationSmsAdapter] failed caseId={} mobile={} code={} msg={} errorCode={}",
                caseId,
                maskPhone(mobile),
                resp.getCode(),
                resp.getMsg(),
                errorCode);
        return AdapterSupport.permanentFailure(errorCode);
    }

    /** 构建 Push fallback 用的 SMS 命令（同槽，独立幂等键）。 */
    public StepCommand buildFallbackCommand(StepCommand pushCommand, String phone) {
        Map<String, Object> meta = new HashMap<>(pushCommand.getMetadata());
        meta.put(StepCommand.META_FALLBACK_SMS, true);
        if (!meta.containsKey(StepCommand.META_SMS_BODY)) {
            Object fallbackBody = meta.get(StepCommand.META_FALLBACK_SMS_BODY);
            meta.put(
                    StepCommand.META_SMS_BODY,
                    fallbackBody != null
                            ? fallbackBody
                            : "[Fallback SMS] "
                                    + meta.getOrDefault(
                                            StepCommand.META_SCRIPT_SLOT, "PUSH_FALLBACK"));
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

    /** 快照为 E.164（+639...）；去前导 + 后交通知中心 BizHelper 补区号。 */
    private static String normalizeMobile(String raw) {
        if (StringUtils.isBlank(raw)) {
            return raw;
        }
        String trimmed = raw.trim();
        return trimmed.startsWith("+") ? trimmed.substring(1) : trimmed;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "****" + phone.substring(phone.length() - 4);
    }
}
