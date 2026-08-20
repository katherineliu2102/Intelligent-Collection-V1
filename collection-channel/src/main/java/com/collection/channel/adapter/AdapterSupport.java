package com.collection.channel.adapter;

import com.collection.channel.client.HttpFailureClassifier;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ContactResult;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Adapter 公共工具：HTTP 异常 → {@link StepResult}。
 *
 * <p>失败三分类：可证明未发出（{@link #notSent}，可重试）／结果未知（{@link #outcomeUnknown}，不重试）／确定性失败（{@link
 * #permanentFailure}，不重试）。判定依据见 {@link HttpFailureClassifier}。
 */
final class AdapterSupport {

    private AdapterSupport() {}

    /** 可证明未发出：请求根本没到供应商，引擎重试安全（`retryable=true`）。 */
    static StepResult notSent(String errorCode, String detail) {
        return StepResult.builder()
                .success(false)
                .contactResult(ContactResult.CHANNEL_DOWN)
                .errorCode(errorCode)
                .retryable(true)
                .build();
    }

    /** 结果未知：请求可能已被供应商受理，重试即可能重复触达，故不重试。 */
    static StepResult outcomeUnknown(String errorCode, String detail) {
        return StepResult.builder()
                .success(false)
                .contactResult(ContactResult.FAILED)
                .errorCode(errorCode)
                .retryable(false)
                .build();
    }

    static StepResult notConfigured(String channel) {
        return notSent(
                channel + "_NOT_CONFIGURED",
                "Missing Nacos channel." + channel.toLowerCase() + " credentials");
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
        if (e instanceof HttpStatusCodeException) {
            HttpStatusCodeException ex = (HttpStatusCodeException) e;
            int code = ex.getRawStatusCode();
            if (code == 429) {
                return notSent(prefix + "_429_NOT_SENT", ex.getStatusText());
            }
            if (code >= 500) {
                return outcomeUnknown(prefix + "_" + code + "_OUTCOME_UNKNOWN", ex.getStatusText());
            }
            return permanentFailure(prefix + "_" + code);
        }
        if (HttpFailureClassifier.provablyNotSent(e)) {
            return notSent(prefix + "_NOT_SENT", e.getMessage());
        }
        return outcomeUnknown(prefix + "_OUTCOME_UNKNOWN", e.getMessage());
    }

    /** 通知中心故障（渠道侧短重试耗尽后上抛）→ 按可否证明未发出分流。 */
    static StepResult notificationFailure(Exception e) {
        return mapHttpException("NOTIFICATION", e);
    }

    /** 通知中心业务码 → 建议 errorCode（对接说明 §9；待编排最终拍板，仅落 timeline）。 */
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
