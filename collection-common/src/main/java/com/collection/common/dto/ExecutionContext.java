package com.collection.common.dto;

import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContactRecord;
import com.collection.common.model.ContextSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 执行上下文。所有 SPI 调用的统一输入。对应领域模型 §5.2、核心引擎规格 §4.2。
 *
 * <p>⚠ 约束：SPI 实现方<b>只读</b>，不得调用 plan / currentStep 的任何 setter。
 * 违反此约束会导致引擎状态不可预期。Phase 2 考虑替换为不可变 View 对象。
 */
@Getter
@Builder
@AllArgsConstructor
public class ExecutionContext {

    private final ContactPlan plan;
    private final ContactPlanStep currentStep;
    private final ContextSnapshot contextSnapshot;
    /** 近期触达历史，默认最近 50 条（engine.context.history_max_records）。 */
    private final List<ContactRecord> recentTimeline;
}
