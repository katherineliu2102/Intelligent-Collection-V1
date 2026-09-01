package com.collection.service.mapper;

import com.collection.common.enums.ContactResult;
import com.collection.common.enums.StepStatus;
import com.collection.common.model.ContactPlanStep;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/**
 * t_contact_plan_step 持久化。
 *
 * <p>时间列一律由调用方以 {@code Asia/Manila} 传入（{@link com.collection.service.support.ServiceClock}）， 不使用
 * {@code NOW()}：库端会话时区会让同一行的 {@code executed_at} 与 {@code updated_at} 落在不同时区， 使取证与停摆巡检失真。{@code
 * trigger_time} / {@code timeout_time} 本来就由应用侧传入，与此保持一致。
 */
@Mapper
public interface ContactPlanStepMapper {

    @Insert(
            "INSERT INTO t_contact_plan_step "
                    + "(plan_id, step_order, channel_type, template_id, delay_minutes, trigger_time, "
                    + " original_trigger_time, timeout_time, "
                    + " trigger_condition, status, observation_minutes, retry_count, result, idempotency_key, "
                    + " executed_at, completed_at, created_at, updated_at) "
                    + "VALUES "
                    + "(#{planId}, #{stepOrder}, #{channelType}, #{templateId}, #{delayMinutes}, #{triggerTime}, "
                    + " #{triggerTime}, #{timeoutTime}, "
                    + " #{triggerCondition}, #{status}, #{observationMinutes}, #{retryCount}, #{result}, #{idempotencyKey}, "
                    + " #{executedAt}, #{completedAt}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContactPlanStep step);

    @Select("SELECT * FROM t_contact_plan_step WHERE id = #{stepId}")
    ContactPlanStep selectById(@Param("stepId") Long stepId);

    @Select("SELECT * FROM t_contact_plan_step WHERE plan_id = #{planId} ORDER BY step_order ASC")
    List<ContactPlanStep> selectByPlan(@Param("planId") Long planId);

    /**
     * 取「下一个还能执行的步骤」：序号更大且尚未终结的第一条。
     *
     * <p>不能只取 {@code step_order = 当前+1}。步骤会乱序完成——退避重试的步骤晚于其后继落地，
     * 于是后继完成时推进一次，重试步骤完成时又推进一次，第二次取到的正是那个已终结的后继。 调用方随后按「无 trigger_time 就排期」把它改回
     * PENDING，已完成的步骤被复活成待执行， 只剩幂等锁挡在真实触达前（台账 F12）。
     *
     * <p>{@code status NOT IN (终态)} 同时兼顾停摆一侧：跳过已终结步骤后继续往后找， 否则计划会停在一个永远不会再到期的步骤上。全部后继都已终结时返回
     * null，由调用方判 PLAN_EXHAUSTED。
     */
    @Select(
            "SELECT * FROM t_contact_plan_step "
                    + "WHERE plan_id = #{planId} AND step_order >= #{stepOrder} "
                    + "AND status NOT IN ('COMPLETED','SKIPPED','FAILED') "
                    + "ORDER BY step_order ASC LIMIT 1")
    ContactPlanStep selectByPlanAndOrder(
            @Param("planId") Long planId, @Param("stepOrder") int stepOrder);

    @Update(
            "<script>UPDATE t_contact_plan_step SET status = #{status}, result = #{result}, "
                    + "completed_at = CASE WHEN #{status} IN ('COMPLETED','SKIPPED','FAILED') THEN #{now} ELSE completed_at END, "
                    + "updated_at = #{now} WHERE id = #{stepId}</script>")
    int updateStatus(
            @Param("stepId") Long stepId,
            @Param("status") StepStatus status,
            @Param("result") ContactResult result,
            @Param("now") LocalDateTime now);

    @Update(
            "<script>UPDATE t_contact_plan_step SET status = #{targetStatus}, result = #{result}, "
                    + "completed_at = CASE WHEN #{targetStatus} IN ('COMPLETED','SKIPPED','FAILED') THEN #{now} ELSE completed_at END, "
                    + "updated_at = #{now} WHERE id = #{stepId} AND status IN "
                    + "<foreach collection='expectedStatuses' item='item' open='(' separator=',' close=')'>#{item}</foreach></script>")
    int transitionStatus(
            @Param("stepId") Long stepId,
            @Param("expectedStatuses") java.util.List<StepStatus> expectedStatuses,
            @Param("targetStatus") StepStatus targetStatus,
            @Param("result") ContactResult result,
            @Param("now") LocalDateTime now);

    /**
     * 抢占步骤执行权。{@code status NOT IN (终态)} 是承重谓词，不得删：本语句会清空 {@code trigger_time}， 若允许它把已终结的步骤改回
     * EXECUTING，该行就同时失去 {@code trigger_time} 与 {@code timeout_time}， 到期扫描与超时扫描都摸不到，永久悬挂。生产 Pub/Sub
     * 为 at-least-once，重复投递必然发生； 调用方的「先读后写」终态检查不具原子性（终态写入发生在计划行锁之外），只有本谓词能在写时刻判定。
     *
     * @return 1 表示抢到，0 表示步骤已终结或已被并发抢占，调用方必须当作 no-op 放弃执行
     */
    @Update(
            "UPDATE t_contact_plan_step SET status = 'EXECUTING', trigger_time = NULL, "
                    + "executed_at = COALESCE(executed_at, #{now}), updated_at = #{now} "
                    + "WHERE id = #{stepId} AND status NOT IN ('COMPLETED','SKIPPED','FAILED')")
    int markExecuting(@Param("stepId") Long stepId, @Param("now") LocalDateTime now);

    @Update(
            "UPDATE t_contact_plan_step SET result = #{result}, updated_at = #{now} WHERE id = #{stepId}")
    int updateResult(
            @Param("stepId") Long stepId,
            @Param("result") ContactResult result,
            @Param("now") LocalDateTime now);

    /** 渠道受理时点。重试会复用同一行，只记首次受理，避免覆盖真实发出时间。 */
    @Update(
            "UPDATE t_contact_plan_step SET dispatched_at = COALESCE(dispatched_at, #{now}), "
                    + "updated_at = #{now} WHERE id = #{stepId}")
    int markDispatched(@Param("stepId") Long stepId, @Param("now") LocalDateTime now);

    /**
     * 排期/退避改写。{@code status NOT IN (终态)} 与 {@link #markExecuting} 同源，是写时刻的最后防线： 本语句会把 {@code
     * status} 改成入参值（PENDING / EXECUTING），一旦允许它命中已终结的行， 该步骤就被复活成待执行，而 {@code result} 与 {@code
     * completed_at} 仍留着上一次的终态值。
     *
     * <p>调用方的「先读后写」不具原子性——读到的状态与写入之间隔着 SPI 调用与渠道 I/O， 期间回调或超时随时可能把步骤终结，所以判定必须落在这一条 UPDATE 上（台账
     * F12）。
     */
    @Update(
            "UPDATE t_contact_plan_step SET trigger_time = #{triggerTime}, status = #{status}, "
                    + "updated_at = #{now} WHERE id = #{stepId} "
                    + "AND status NOT IN ('COMPLETED','SKIPPED','FAILED')")
    int updateTriggerTime(
            @Param("stepId") Long stepId,
            @Param("triggerTime") LocalDateTime triggerTime,
            @Param("status") StepStatus status,
            @Param("now") LocalDateTime now);

    /** 异步步骤挂回调超时窗。同样带终态谓词：回调若已抢先落地，不得把收敛完的步骤拖回 EXECUTING。 */
    @Update(
            "UPDATE t_contact_plan_step SET timeout_time = #{timeoutTime}, trigger_time = NULL, "
                    + "executed_at = COALESCE(executed_at, #{now}), status = 'EXECUTING', updated_at = #{now} "
                    + "WHERE id = #{stepId} AND status NOT IN ('COMPLETED','SKIPPED','FAILED')")
    int updateTimeoutTime(
            @Param("stepId") Long stepId,
            @Param("timeoutTime") LocalDateTime timeoutTime,
            @Param("now") LocalDateTime now);

    @Update(
            "UPDATE t_contact_plan_step SET retry_count = retry_count + 1, updated_at = #{now} WHERE id = #{stepId}")
    int incrementRetryCount(@Param("stepId") Long stepId, @Param("now") LocalDateTime now);

    /**
     * Cron：到期待触发步骤（关联计划非终态）。
     *
     * <p>{@code caseIds} 非空时收窄到名单内案件。扫描本身没有租户维度，共享库上任何实例都会捞走全库到期 步骤并发起触达，故该 {@code IN}
     * 是承重条件，不得为了「简化 SQL」去掉。
     */
    @Select(
            "<script>SELECT s.* FROM t_contact_plan_step s "
                    + "JOIN t_contact_plan p ON p.id = s.plan_id "
                    + "WHERE s.trigger_time &lt;= #{now} "
                    + "AND s.status IN ('PENDING','EXECUTING') "
                    + "AND p.renewal_pending = 0 "
                    + "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
                    + "<if test='caseIds != null and caseIds.size() > 0'>"
                    + "AND p.case_id IN <foreach collection='caseIds' item='c' open='(' separator=',' close=')'>#{c}</foreach>"
                    + "</if> "
                    + "ORDER BY s.trigger_time ASC LIMIT #{limit}</script>")
    List<ContactPlanStep> selectDueSteps(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit,
            @Param("caseIds") List<Long> caseIds);

    /** Cron：回调超时步骤。{@code caseIds} 语义同 {@link #selectDueSteps}。 */
    @Select(
            "<script>SELECT s.* FROM t_contact_plan_step s "
                    + "JOIN t_contact_plan p ON p.id = s.plan_id "
                    + "WHERE s.timeout_time &lt;= #{now} AND s.status = 'EXECUTING' "
                    + "AND p.renewal_pending = 0 "
                    + "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
                    + "<if test='caseIds != null and caseIds.size() > 0'>"
                    + "AND p.case_id IN <foreach collection='caseIds' item='c' open='(' separator=',' close=')'>#{c}</foreach>"
                    + "</if> "
                    + "ORDER BY s.timeout_time ASC LIMIT #{limit}</script>")
    List<ContactPlanStep> selectTimeoutSteps(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit,
            @Param("caseIds") List<Long> caseIds);
}
