package com.collection.channel.adapter;

import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ContactResult;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Adapter 公共工具：HTTP 异常 → {@link StepResult}（§3.1 基础设施 vs 业务失败）。
 */
final class AdapterSupport {

    private AdapterSupport() {
    }

    static StepResult channelDown(String errorCode, String detail) {
        return StepResult.builder()
                .success(false)
                .contactResult(ContactResult.CHANNEL_DOWN)
                .errorCode(errorCode)
                .retryable(true)
                .build();
    }

    static StepResult notConfigured(String channel) {
        return channelDown(channel + "_NOT_CONFIGURED", "Missing Nacos channel." + channel.toLowerCase() + " credentials");
    }

    static StepResult permanentFailure(String errorCode) {
        return StepResult.builder()
                .success(false)
                .contactResult(ContactResult.FAILED)
                .errorCode(errorCode)
                .retryable(false)
                .build();
    }

    static StepResult delivered(String providerMsgId) {
        return StepResult.builder()
                .success(true)
                .contactResult(ContactResult.DELIVERED)
                .retryable(false)
                .providerMsgId(providerMsgId)
                .build();
    }

    static StepResult mapHttpException(String prefix, Exception e) {
        if (e instanceof ResourceAccessException) {
            return channelDown(prefix + "_TIMEOUT", e.getMessage());
        }
        if (e instanceof HttpStatusCodeException) {
            HttpStatusCodeException ex = (HttpStatusCodeException) e;
            int code = ex.getRawStatusCode();
            if (code >= 500 || code == 429) {
                return channelDown(prefix + "_" + code, ex.getStatusText());
            }
            return permanentFailure(prefix + "_" + code);
        }
        return channelDown(prefix + "_ERROR", e.getMessage());
    }

    /** 通知中心瞬时故障（连接超时 / 5xx / 429，重试耗尽）→ retryable，交引擎降级。 */
    static StepResult notificationTimeout() {
        return channelDown("NOTIFICATION_TIMEOUT", "transient HTTP failure after retry");
    }

    /**
     * 通知中心业务码 → 建议 errorCode（对接说明 §9；待编排最终拍板，仅落 timeline）。
     */
    static String notificationErrorCode(Integer code) {
        if (code == null) {
            return "NOTIFICATION_UNKNOWN";
        }
        switch (code) {
            case 81:
                return "NOTIFICATION_PARAM_ERROR";
            case 1000:
                return "NOTIFICATION_INVALID_SIGN";
            case 2001:
                return "NOTIFICATION_NO_ACCOUNT";
            case 2003:
                return "NOTIFICATION_APPCODE_NOT_FOUND";
            case 3001:
                return "NOTIFICATION_INVALID_PROVIDER";
            default:
                return "NOTIFICATION_CODE_" + code;
        }
    }

    static String metadataString(StepCommand command, String key) {
        if (command.getMetadata() == null || !command.getMetadata().containsKey(key)) {
            return null;
        }
        Object v = command.getMetadata().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
