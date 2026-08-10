package com.collection.engine.lifecycle;

import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;

/**
 * 引擎派生事件的唯一构造点。
 *
 * <p>这些事件的 {@code eventId} 是<b>确定性</b>的，不是随机 UUID。原因有两条，缺一不可：
 *
 * <ul>
 *   <li><b>发件箱销账</b>：事件在事务内入箱、提交后即时发布，两处必须落到同一个 id， 否则轮询器无法判断这条记录已经投递成功，会把每个正常事件都重发一遍。
 *   <li><b>消费侧去重</b>：一次步骤尝试只应完成一次，一个计划只应穷尽一次。 id 由业务身份推导后，重投与兜底重发天然收敛到同一条，不依赖调用方的自觉。
 * </ul>
 */
final class EngineEvents {

    private EngineEvents() {}

    /** 一次步骤尝试完成。身份即触达尝试键（planId:stepOrder:retryCount）。 */
    static CollectionEvent stepCompleted(ContactPlan plan, ContactPlanStep step) {
        CollectionEvent event =
                CollectionEvent.of(EventType.STEP_COMPLETED)
                        .with(CollectionEvent.CASE_ID, plan.getCaseId())
                        .with(CollectionEvent.USER_ID, plan.getUserId())
                        .with(CollectionEvent.PLAN_ID, plan.getId())
                        .with(CollectionEvent.STEP_ID, step.getId());
        event.setEventId(
                "STEP_COMPLETED:"
                        + plan.getId()
                        + ":"
                        + step.getStepOrder()
                        + ":"
                        + step.getRetryCount());
        return event;
    }

    /** 计划步骤序列走完。计划穷尽后即转终态或被续建成新计划，故 planId 已足够唯一。 */
    static CollectionEvent planExhausted(ContactPlan plan) {
        CollectionEvent event =
                CollectionEvent.of(EventType.PLAN_EXHAUSTED)
                        .with(CollectionEvent.CASE_ID, plan.getCaseId())
                        .with(CollectionEvent.PLAN_ID, plan.getId());
        event.setEventId("PLAN_EXHAUSTED:" + plan.getId());
        return event;
    }

    /** 穷尽策略判定升级阶段。 */
    static CollectionEvent stageEscalated(ContactPlan plan, String targetStage) {
        CollectionEvent event =
                CollectionEvent.of(EventType.STAGE_CHANGED)
                        .with(CollectionEvent.CASE_ID, plan.getCaseId())
                        .with(CollectionEvent.PLAN_ID, plan.getId())
                        .with(CollectionEvent.STAGE, targetStage);
        event.setEventId("STAGE_CHANGED:" + plan.getId() + ":" + targetStage);
        return event;
    }
}
