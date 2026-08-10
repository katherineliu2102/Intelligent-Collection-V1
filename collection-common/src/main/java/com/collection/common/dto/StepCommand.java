package com.collection.common.dto;

import com.collection.common.enums.ChannelType;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 步骤命令。StepResolver.resolve() 的输出，同时作为 ChannelGateway.dispatch() 的输入。 对应领域模型 §5.4。
 *
 * <p>metadata 已知 key（Phase 1）：stage / language / callbackUrl / timeoutMinutes / scriptSlot /
 * sms_body / title / body / pushData / dynamicTemplateData / case_id / fallback_sms。
 */
@Getter
@Builder
@AllArgsConstructor
public class StepCommand {

    public static final String META_STAGE = "stage";
    public static final String META_LANGUAGE = "language";
    public static final String META_CALLBACK_URL = "callbackUrl";
    public static final String META_TIMEOUT_MINUTES = "timeoutMinutes";
    public static final String META_SCRIPT_SLOT = "scriptSlot";
    public static final String META_TEMPLATE_VERSION = "templateVersion";
    public static final String META_CONFIG_VERSION = "configVersion";
    public static final String META_SMS_BODY = "sms_body";
    public static final String META_FALLBACK_SMS_BODY = "fallback_sms_body";
    public static final String META_TITLE = "title";
    public static final String META_BODY = "body";
    public static final String META_PUSH_DATA = "pushData";
    public static final String META_DYNAMIC_TEMPLATE_DATA = "dynamicTemplateData";
    public static final String META_CASE_ID = "case_id";
    public static final String META_FALLBACK_SMS = "fallback_sms";

    private final ChannelType channelType;
    private final String targetAddress;
    private final String templateId;
    /** 尝试级键 {@code {planId}:{stepOrder}:{retryCount}}：渠道内去重、timeline 审计。每次重试都变。 */
    private final String idempotencyKey;
    /**
     * 供应商侧去重键 {@code {planId}:{stepOrder}}：同一逻辑触达内稳定，跨引擎重试不变。
     *
     * <p>Phase 1 只建键、不据此放开「结果未知后重试」——供应商去重能力未确认前，重试仍等于重复发送 （见 {@link
     * StepResult#isRetryable()}）。渠道侧确认供应商支持后传给供应商，才可评估放开。
     */
    private final String providerIdempotencyKey;

    @Builder.Default private final Map<String, Object> metadata = new HashMap<>();
}
