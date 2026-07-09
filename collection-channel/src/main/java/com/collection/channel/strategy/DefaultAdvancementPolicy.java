package com.collection.channel.strategy;

import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.AdvancementDecision;
import com.collection.common.spi.AdvancementPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Phase 1 简化版 AdvancementPolicy —— 按步序 + 成功/失败做推进决策。
 *
 * <p>主架构临时代写，推进 L4a-全测试。编排同事回来后替换为按 contactResult 细分的生产实现。
 *
 * <p>决策逻辑：
 *
 * <ul>
 *   <li>非末步 → ADVANCE_NEXT（不管成功失败，继续走下一步）
 *   <li>末步 + success → PLAN_COMPLETED
 *   <li>末步 + !success → PLAN_EXHAUSTED（触发 ExhaustionPolicy）
 * </ul>
 */
@Primary
@Component
public class DefaultAdvancementPolicy implements AdvancementPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdvancementPolicy.class);

    @Override
    public AdvancementDecision decide(ExecutionContext context, StepResult stepResult) {
        int currentOrder = context.getCurrentStep().getStepOrder();
        int total = context.getPlan().getTotalSteps();

        if (currentOrder < total) {
            return AdvancementDecision.ADVANCE_NEXT;
        }

        if (stepResult.isSuccess()) {
            log.info("[DefaultAdvancementPolicy] last step success → PLAN_COMPLETED");
            return AdvancementDecision.PLAN_COMPLETED;
        }

        log.info("[DefaultAdvancementPolicy] last step failed → PLAN_EXHAUSTED");
        return AdvancementDecision.PLAN_EXHAUSTED;
    }
}
