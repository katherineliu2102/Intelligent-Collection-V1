package com.collection.channel.strategy;

import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.AdvancementDecision;
import com.collection.common.spi.AdvancementPolicy;
import org.springframework.stereotype.Component;

/**
 * Phase 1 Mock 实现 —— 默认推进策略占位。对应 SPI {@link AdvancementPolicy}。
 *
 * <p>真实实现：渠道编排负责人按模板顺序 + 用户响应做推进/完成/穷尽决策。
 * 本 Mock 规则：当前步为最后一步 → PLAN_COMPLETED；否则 → ADVANCE_NEXT。
 */
@Component
public class MockAdvancementPolicy implements AdvancementPolicy {

    @Override
    public AdvancementDecision decide(ExecutionContext context, StepResult stepResult) {
        int currentOrder = context.getCurrentStep().getStepOrder();
        int total = context.getPlan().getTotalSteps();
        if (currentOrder >= total) {
            return AdvancementDecision.PLAN_COMPLETED;
        }
        return AdvancementDecision.ADVANCE_NEXT;
    }
}
