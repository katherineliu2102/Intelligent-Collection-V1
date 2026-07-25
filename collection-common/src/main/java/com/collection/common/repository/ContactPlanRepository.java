package com.collection.common.repository;

import com.collection.common.enums.CancelReason;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.StepStatus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/** 核心引擎持久层接口。对应基础设施规范 §5 Repository 接口清单。 实现位于 collection-service（MyBatis）。 */
public interface ContactPlanRepository {

    // ── 计划读取 ──
    ContactPlan findById(Long planId);

    /** SELECT FOR UPDATE 获取单计划行锁（必须在事务内）。 */
    ContactPlan findPlanWithLock(Long planId);

    /** 用户所有非终态计划（只读）。 */
    List<ContactPlan> findActivePlansByUser(Long userId);

    /** 案件所有非终态计划（只读）。 */
    List<ContactPlan> findActivePlansByCase(Long caseId);

    /** 案件指定阶段的活跃计划（单活跃计划约束）。 */
    ContactPlan findActivePlanByCaseAndStage(Long caseId, com.collection.common.enums.Stage stage);

    /** 最近完成/穷尽的计划。 */
    ContactPlan getLastCompletedPlan(Long caseId);

    /** 案件最近计划（含终态），按 id 降序。供 L4a 断言 cancelReason / 幂等。 */
    List<ContactPlan> findRecentPlansByCase(Long caseId, int limit);

    // ── 计划/步骤写入 ──
    /** 持久化计划 + 步骤序列（事务）。回写生成的 id。 */
    void savePlan(ContactPlan plan);

    void updatePlanStatus(Long planId, PlanStatus status, CancelReason reason);

    /** 标记 REBUILD 中的旧计划。该标记只应在包含新计划插入与旧计划终态化的同一事务内短暂存在。 */
    default void markRenewalPending(Long planId) {}

    /** 首步进入 EXECUTING 时写 startedAt（IF NULL THEN SET）。 */
    void markStarted(Long planId);

    void markCompleted(Long planId);

    void updateCurrentStep(Long planId, int currentStep);

    // ── 步骤 ──
    ContactPlanStep findStepById(Long stepId);

    List<ContactPlanStep> findStepsByPlan(Long planId);

    ContactPlanStep getNextStep(Long planId, int currentStepOrder);

    void updateStepStatus(Long stepId, StepStatus status, ContactResult result);

    /** 条件状态迁移。返回 false 表示步骤已由回调、超时或取消路径处理，应作为幂等 no-op。 */
    default boolean transitionStepStatus(
            Long stepId,
            List<StepStatus> expectedStatuses,
            StepStatus targetStatus,
            ContactResult result) {
        updateStepStatus(stepId, targetStatus, result);
        return true;
    }

    default boolean transitionStepStatus(
            Long stepId, StepStatus expectedStatus, StepStatus targetStatus, ContactResult result) {
        return transitionStepStatus(stepId, Arrays.asList(expectedStatus), targetStatus, result);
    }

    /** 首次开始执行只写 executed_at，绝不写 completed_at。 */
    default void markStepExecuting(Long stepId) {
        updateStepStatus(stepId, StepStatus.EXECUTING, null);
    }

    /** 观察期的预置最终结果，不改变步骤状态或完成时间。 */
    default void updateStepResult(Long stepId, ContactResult result) {}

    void updateStepTriggerTime(Long stepId, LocalDateTime triggerTime, StepStatus status);

    void updateStepTimeoutTime(Long stepId, LocalDateTime timeoutTime);

    void incrementRetryCount(Long stepId);

    // ── Cron 扫描（基础设施规范 §4，Trigger-to-Event） ──
    /** trigger_time <= now 且步骤待触发、关联计划非终态。带 LIMIT。 */
    List<ContactPlanStep> findDueSteps(LocalDateTime now, int limit);

    /** timeout_time <= now 且 status=EXECUTING、关联计划非终态。带 LIMIT。 */
    List<ContactPlanStep> findTimeoutSteps(LocalDateTime now, int limit);
}
