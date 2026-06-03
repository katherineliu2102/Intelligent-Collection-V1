package com.collection.engine.lifecycle;

import com.collection.common.event.CollectionEvent;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * PLAN_STEP_DUE 短事务（lock → 校验 → 状态前置）的产出。
 *
 * <p>execute=true 时由 Dispatcher 在<b>事务提交后、非事务上下文</b>调用 Orchestrator.executeStep；
 * events 为需在提交后发布的事件（如观察期结转的 STEP_COMPLETED）。
 */
@Data
public class StepDuePreparation {

    private boolean execute;
    private ContactPlan plan;
    private ContactPlanStep step;
    private final List<CollectionEvent> events = new ArrayList<>();

    public static StepDuePreparation noop() {
        return new StepDuePreparation();
    }

    public static StepDuePreparation toExecute(ContactPlan plan, ContactPlanStep step) {
        StepDuePreparation p = new StepDuePreparation();
        p.execute = true;
        p.plan = plan;
        p.step = step;
        return p;
    }
}
