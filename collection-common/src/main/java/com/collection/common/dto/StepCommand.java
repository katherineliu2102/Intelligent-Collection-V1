package com.collection.common.dto;

import com.collection.common.enums.ChannelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 步骤命令。StepResolver.resolve() 的输出，同时作为 ChannelGateway.dispatch() 的输入。
 * 对应领域模型 §4.3。
 *
 * <p>metadata 已知 key（Phase 1）：stage / language / callbackUrl / timeoutMinutes。
 */
@Getter
@Builder
@AllArgsConstructor
public class StepCommand {

    public static final String META_STAGE = "stage";
    public static final String META_LANGUAGE = "language";
    public static final String META_CALLBACK_URL = "callbackUrl";
    public static final String META_TIMEOUT_MINUTES = "timeoutMinutes";

    private final ChannelType channelType;
    private final String targetAddress;
    private final String templateId;
    private final String idempotencyKey;
    @Builder.Default
    private final Map<String, Object> metadata = new HashMap<>();
}
