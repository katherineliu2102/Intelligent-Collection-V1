package com.collection.common.dto;

import com.collection.common.enums.ChannelType;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
<<<<<<< HEAD
 * 步骤命令。StepResolver.resolve() 的输出，同时作为 ChannelGateway.dispatch() 的输入。 对应领域模型 §4.3。
=======
 * 步骤命令。StepResolver.resolve() 的输出，同时作为 ChannelGateway.dispatch() 的输入。
 * 对应领域模型 §5.4。
>>>>>>> origin/ca_branch
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
    private final String idempotencyKey;
    @Builder.Default private final Map<String, Object> metadata = new HashMap<>();
}
