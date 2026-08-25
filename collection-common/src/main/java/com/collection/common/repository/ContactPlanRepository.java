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

    /** 更新仍处于活跃态计划的快照余额。仅允许 {@code totalOutstanding} 这个明确的可变例外； 调用方必须先读取快照并保留其他决策字段。 */
    default boolean updateActivePlanContextSnapshot(Long planId, String contextSnapshot) {
        return false;
    }

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

    /**
     * 抢占步骤执行权：首次开始执行只写 executed_at，绝不写 completed_at。
     *
     * <p>实现方**必须**把「步骤已处于终态」表达为条件更新（而非先查后写）：本方法会清空 {@code trigger_time}， 若把已终结的步骤改回
     * EXECUTING，该行将同时失去 {@code trigger_time} 与 {@code timeout_time}， 到期与超时两个扫描都摸不到，计划永久停摆。生产
     * Pub/Sub 为 at-least-once，重复投递必然发生。
     *
     * @return false 表示步骤已终结或被并发抢占，调用方必须放弃执行（幂等 no-op）
     */
    default boolean markStepExecuting(Long stepId) {
        // 内存实现的兜底语义：先查后写只在单线程下正确，持久化实现必须换成条件更新。
        ContactPlanStep current = findStepById(stepId);
        if (current != null && current.getStatus() != null && current.getStatus().isTerminal()) {
            return false;
        }
        updateStepStatus(stepId, StepStatus.EXECUTING, null);
        return true;
    }

    /** 观察期的预置最终结果，不改变步骤状态或完成时间。 */
    default void updateStepResult(Long stepId, ContactResult result) {}

    /** 渠道受理成功后写 dispatched_at（IF NULL THEN SET），不改变步骤状态。 */
    default void markStepDispatched(Long stepId) {}

    void updateStepTriggerTime(Long stepId, LocalDateTime triggerTime, StepStatus status);

    void updateStepTimeoutTime(Long stepId, LocalDateTime timeoutTime);

    void incrementRetryCount(Long stepId);

    // ── Cron 扫描（基础设施规范 §4，Trigger-to-Event） ──
    /**
     * trigger_time <= now 且步骤待触发、关联计划非终态。带 LIMIT。
     *
     * <p>{@code caseIdFilter} 非空时只返回名单内案件的步骤。**该过滤是承重的**：扫描器没有任何租户维度，
     * 任何指向同一库的实例都会捞走全库到期步骤并对其发起触达。2026-08-21 已实测到共享库上的外来实例 抢走本机测试步骤（详见测试文档缺口表）。实现方不得把它降级为「尽力而为」——
     * 宁可返回空集，不可返回名单外的步骤。
     *
     * @param caseIdFilter 允许扫描的 case_id；null 或空表示不过滤（仅生产单实例场景可用）
     */
    List<ContactPlanStep> findDueSteps(LocalDateTime now, int limit, List<Long> caseIdFilter);

    /**
     * timeout_time <= now 且 status=EXECUTING、关联计划非终态。带 LIMIT。
     *
     * @param caseIdFilter 语义同 {@link #findDueSteps}，同样是承重过滤
     */
    List<ContactPlanStep> findTimeoutSteps(LocalDateTime now, int limit, List<Long> caseIdFilter);

    // ── 存活性巡检（安全网） ──
    /**
     * 停摆计划：非终态、没有任何步骤会被 due / timeout 扫描再次拾取，且没有仍在投递中的派生事件，因此不会自行推进。
     *
     * <p>只做检测与告警，不自动重发触达——触达是不可回滚的外部动作，误判的代价由用户承担。
     *
     * @param idleBefore 计划 updated_at 早于该时刻才纳入，避开正在处理中的计划
     */
    default List<Long> findStuckPlanIds(LocalDateTime idleBefore, int limit) {
        return java.util.Collections.emptyList();
    }
}
