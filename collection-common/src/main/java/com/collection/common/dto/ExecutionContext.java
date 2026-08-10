package com.collection.common.dto;

import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContactRecord;
import com.collection.common.model.ContextSnapshot;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 执行上下文。SPI 调用的统一输入。对应领域模型 §5.2、核心引擎规格 §6。
 *
 * <p>步骤执行期由 {@code ContextAssembler} 提供实时 {@code recentTimeline}；计划推进发生在计划行锁事务内， {@code
 * AdvancementPolicy} 收到轻量上下文，其 {@code recentTimeline} 固定为空，不得依赖该字段。
 *
 * <p>⚠ 约束：SPI 实现方<b>只读</b>，不得调用 plan / currentStep 的任何 setter。 违反此约束会导致引擎状态不可预期。Phase 2 考虑替换为不可变
 * View 对象。
 */
@Getter
@Builder
@AllArgsConstructor
public class ExecutionContext {

    private final ContactPlan plan;
    private final ContactPlanStep currentStep;
    private final ContextSnapshot contextSnapshot;
    /** 近期触达历史。步骤执行期按时间窗口取数；计划推进期固定为空。 */
    private final List<ContactRecord> recentTimeline;
}
