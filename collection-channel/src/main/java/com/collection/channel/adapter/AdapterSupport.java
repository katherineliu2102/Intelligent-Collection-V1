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

    static String metadataString(StepCommand command, String key) {
        if (command.getMetadata() == null || !command.getMetadata().containsKey(key)) {
            return null;
        }
        Object v = command.getMetadata().get(key);
        return v == null ? null : String.valueOf(v);
    }
}
