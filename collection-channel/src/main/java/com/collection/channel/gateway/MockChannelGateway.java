package com.collection.channel.gateway;

import com.collection.common.channel.ChannelGateway;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ContactResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase 1 Mock 实现 —— 执行子层 ChannelGateway 的占位。对应 {@link ChannelGateway}。
 *
 * <p>真实实现：渠道编排负责人做模板渲染 → 幂等校验 → 熔断校验 → ChannelAdapter.send()
 * （SMS/Push/AI_Call/TTS/Email/Viber/WhatsApp），熔断/fallback 对引擎透明。
 * 本 Mock 直接"发送成功"：
 * <ul>
 *   <li>消息类 → success=true, DELIVERED</li>
 *   <li>异步类 → success=true, DELIVERED（仅表示已受理，真实结果由 Webhook 回调 CHANNEL_CALLBACK）</li>
 * </ul>
 */
@Component
public class MockChannelGateway implements ChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(MockChannelGateway.class);

    @Override
    public StepResult dispatch(StepCommand command) {
        String providerMsgId = "mock-" + UUID.randomUUID();
        log.info("[MockChannelGateway] dispatch {} → {} (template={}, msgId={})",
                command.getChannelType(), command.getTargetAddress(), command.getTemplateId(), providerMsgId);
        return StepResult.builder()
                .success(true)
                .contactResult(ContactResult.DELIVERED)
                .retryable(false)
                .providerMsgId(providerMsgId)
                .build();
    }
}
