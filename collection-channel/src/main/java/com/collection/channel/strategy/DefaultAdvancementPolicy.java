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
 * Phase 1 AdvancementPolicy —— 按步序推进。
 *
 * <p>真人接通后取消当日 AI 补呼不在本 SPI 内改步骤（SPI 只读、不得写 plan）。由引擎在 {@code
 * ADVANCE_NEXT} 之后把同日未执行的 {@code AI_CALL} 标为 SKIPPED（CONNECT_AND_STOP）。
 *
 * <p>决策逻辑：
 *
 * <ul>
 *   <li>非末步 → ADVANCE_NEXT
 *   <li>末步 + success → PLAN_COMPLETED
 *   <li>末步 + !success → PLAN_EXHAUSTED
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
