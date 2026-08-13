package com.collection.service.mapper;

import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.model.ContactPlan;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** t_contact_plan 持久化（注解式 MyBatis）。 依赖 application 配置 map-underscore-to-camel-case=true 完成列名映射。 */
@Mapper
public interface ContactPlanMapper {

    @Insert(
            "INSERT INTO t_contact_plan "
                    + "(case_id, user_id, stage, plan_template_id, status, current_step, total_steps, "
                    + " cancel_reason, context_snapshot, idempotency_key, renewal_pending, version, "
                    + " started_at, completed_at, created_at, updated_at) "
                    + "VALUES "
                    + "(#{caseId}, #{userId}, #{stage}, #{planTemplateId}, #{status}, #{currentStep}, #{totalSteps}, "
                    + " #{cancelReason}, #{contextSnapshot}, #{idempotencyKey}, #{renewalPending}, #{version}, "
                    + " #{startedAt}, #{completedAt}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContactPlan plan);

    @Select("SELECT * FROM t_contact_plan WHERE id = #{planId}")
    ContactPlan selectById(@Param("planId") Long planId);

    @Select("SELECT * FROM t_contact_plan WHERE id = #{planId} FOR UPDATE")
    ContactPlan selectByIdForUpdate(@Param("planId") Long planId);

    @Select(
            "SELECT * FROM t_contact_plan "
                    + "WHERE user_id = #{userId} AND renewal_pending = 0 "
                    + "AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
                    + "ORDER BY id ASC")
    List<ContactPlan> selectActiveByUser(@Param("userId") Long userId);

    @Select(
            "SELECT * FROM t_contact_plan "
                    + "WHERE case_id = #{caseId} AND renewal_pending = 0 "
                    + "AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
                    + "ORDER BY id ASC")
    List<ContactPlan> selectActiveByCase(@Param("caseId") Long caseId);

    @Update(
            "UPDATE t_contact_plan SET context_snapshot = #{contextSnapshot}, updated_at = NOW() "
                    + "WHERE id = #{planId} AND renewal_pending = 0 "
                    + "AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')")
    int updateActiveContextSnapshot(
            @Param("planId") Long planId, @Param("contextSnapshot") String contextSnapshot);

    @Select(
            "SELECT * FROM t_contact_plan "
                    + "WHERE case_id = #{caseId} AND stage = #{stage} "
                    + "AND renewal_pending = 0 "
                    + "AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
                    + "ORDER BY id DESC LIMIT 1")
    ContactPlan selectActiveByCaseAndStage(
            @Param("caseId") Long caseId, @Param("stage") Stage stage);

    @Select(
            "SELECT * FROM t_contact_plan "
                    + "WHERE case_id = #{caseId} AND status = 'PLAN_COMPLETED' "
                    + "ORDER BY completed_at DESC LIMIT 1")
    ContactPlan selectLastCompleted(@Param("caseId") Long caseId);

    @Select(
            "SELECT * FROM t_contact_plan "
                    + "WHERE case_id = #{caseId} ORDER BY id DESC LIMIT #{limit}")
    List<ContactPlan> selectRecentByCase(@Param("caseId") Long caseId, @Param("limit") int limit);

    /**
     * 停摆计划：非终态，但没有任何步骤还能被 due / timeout 扫描拾取。 条件与 {@code
     * ContactPlanStepMapper.selectDueSteps/selectTimeoutSteps} 严格互补——那两个扫描是计划推进的唯一驱动源，
     * 都捞不到即意味着不会再动。
     */
    @Select(
            "SELECT p.id FROM t_contact_plan p "
                    + "WHERE p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
                    + "AND p.renewal_pending = 0 "
                    + "AND p.updated_at <= #{idleBefore} "
                    + "AND NOT EXISTS ("
                    + "  SELECT 1 FROM t_contact_plan_step s WHERE s.plan_id = p.id AND ("
                    + "    (s.trigger_time IS NOT NULL AND s.status IN ('PENDING','EXECUTING')) "
                    + "    OR (s.timeout_time IS NOT NULL AND s.status = 'EXECUTING')"
                    + "  )) "
                    + "AND NOT EXISTS ("
                    + "  SELECT 1 FROM t_event_outbox o WHERE o.plan_id = p.id "
                    + "  AND o.status IN ('PENDING','PROCESSING')"
                    + ") "
                    + "ORDER BY p.id ASC LIMIT #{limit}")
    List<Long> selectStuckPlanIds(
            @Param("idleBefore") LocalDateTime idleBefore, @Param("limit") int limit);

    @Update(
            "UPDATE t_contact_plan SET status = #{status}, cancel_reason = #{cancelReason}, "
                    + "version = version + 1, updated_at = NOW() WHERE id = #{planId} "
                    + "AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')")
    int updateStatus(
            @Param("planId") Long planId,
            @Param("status") PlanStatus status,
            @Param("cancelReason") com.collection.common.enums.CancelReason cancelReason);

    @Update(
            "UPDATE t_contact_plan SET renewal_pending = 1, version = version + 1, updated_at = NOW() "
                    + "WHERE id = #{planId} AND renewal_pending = 0 "
                    + "AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')")
    int markRenewalPending(@Param("planId") Long planId);

    @Update(
            "UPDATE t_contact_plan SET started_at = NOW(), version = version + 1, updated_at = NOW() "
                    + "WHERE id = #{planId} AND started_at IS NULL")
    int markStarted(@Param("planId") Long planId);

    @Update(
            "UPDATE t_contact_plan SET completed_at = NOW(), version = version + 1, updated_at = NOW() "
                    + "WHERE id = #{planId}")
    int markCompleted(@Param("planId") Long planId);

    @Update(
            "UPDATE t_contact_plan SET current_step = #{currentStep}, version = version + 1, updated_at = NOW() "
                    + "WHERE id = #{planId}")
    int updateCurrentStep(@Param("planId") Long planId, @Param("currentStep") int currentStep);
}
