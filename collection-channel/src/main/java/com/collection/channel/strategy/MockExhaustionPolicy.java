package com.collection.channel.strategy;

import com.collection.common.dto.ExhaustionResult;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.spi.ExhaustionPolicy;
import org.springframework.stereotype.Component;

/**
 * Phase 1 Mock 实现 —— 默认穷尽策略占位。对应 SPI {@link ExhaustionPolicy}。
 *
 * <p>真实实现：渠道编排负责人按续建次数上限（engine.plan.max_rebuild_count）+ 阶段规则
 * 判定 REBUILD / ESCALATE / COMPLETE。本 Mock 恒返回 COMPLETE，使链路干净终止、不无限续建。
 */
@Component
public class MockExhaustionPolicy implements ExhaustionPolicy {

    @Override
    public ExhaustionResult handle(ContactPlan plan, CaseInfo caseInfo, ContextSnapshot snapshot) {
        return ExhaustionResult.complete("Phase 1 mock: stop after exhaustion");
    }
}
