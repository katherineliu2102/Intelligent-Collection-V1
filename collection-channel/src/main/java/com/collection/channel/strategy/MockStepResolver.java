package com.collection.channel.strategy;

import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.StepCommand;
import com.collection.common.enums.ChannelType;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.spi.StepResolver;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase 1 Mock 实现 —— DefaultStepResolver 的占位。对应 SPI {@link StepResolver}。
 *
 * <p>真实实现：渠道编排负责人基于 context_snapshot 做渠道选择 + 模板匹配 + 命令组装（零 DB I/O）。
 * 本 Mock 直接按步骤声明的 channelType / templateId 组装命令，目标地址取快照主手机号。
 * <p>约束遵循：永不输出 HUMAN_CALL（对齐待办 E4）。
 */
@Component
public class MockStepResolver implements StepResolver {

    @Override
    public StepCommand resolve(ExecutionContext context) {
        ContactPlanStep step = context.getCurrentStep();
        if (step.getChannelType() == ChannelType.HUMAN_CALL) {
            throw new IllegalStateException("Phase 1 禁止 plan 内 HUMAN_CALL（对齐待办 E4）");
        }

        Map<String, Object> metadata = new HashMap<>();
        if (context.getPlan().getStage() != null) {
            metadata.put(StepCommand.META_STAGE, context.getPlan().getStage().name());
        }
        metadata.put(StepCommand.META_LANGUAGE, "en");
        if (step.getChannelType().isAsyncChannel()) {
            metadata.put(StepCommand.META_CALLBACK_URL, "/webhook/channel-callback");
            metadata.put(StepCommand.META_TIMEOUT_MINUTES, 60);
        }

        return StepCommand.builder()
                .channelType(step.getChannelType())
                .targetAddress(resolveAddress(context))
                .templateId(step.getTemplateId() == null ? "default" : String.valueOf(step.getTemplateId()))
                .idempotencyKey(context.getPlan().getId() + ":" + step.getStepOrder() + ":" + step.getRetryCount())
                .metadata(metadata)
                .build();
    }

    private String resolveAddress(ExecutionContext context) {
        ContextSnapshot snapshot = context.getContextSnapshot();
        if (snapshot != null && snapshot.getUserProfile() != null
                && snapshot.getUserProfile().getBasic() != null
                && snapshot.getUserProfile().getBasic().getPrimaryPhone() != null) {
            return snapshot.getUserProfile().getBasic().getPrimaryPhone();
        }
        return "mock-address";
    }
}
