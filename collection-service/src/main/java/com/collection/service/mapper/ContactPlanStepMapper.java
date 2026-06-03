package com.collection.service.mapper;

import com.collection.common.enums.ContactResult;
import com.collection.common.enums.StepStatus;
import com.collection.common.model.ContactPlanStep;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * t_contact_plan_step 持久化。
 */
@Mapper
public interface ContactPlanStepMapper {

    @Insert("INSERT INTO t_contact_plan_step " +
            "(plan_id, step_order, channel_type, template_id, delay_minutes, trigger_time, timeout_time, " +
            " trigger_condition, status, observation_minutes, retry_count, result, idempotency_key, " +
            " executed_at, completed_at, created_at, updated_at) " +
            "VALUES " +
            "(#{planId}, #{stepOrder}, #{channelType}, #{templateId}, #{delayMinutes}, #{triggerTime}, #{timeoutTime}, " +
            " #{triggerCondition}, #{status}, #{observationMinutes}, #{retryCount}, #{result}, #{idempotencyKey}, " +
            " #{executedAt}, #{completedAt}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContactPlanStep step);

    @Select("SELECT * FROM t_contact_plan_step WHERE id = #{stepId}")
    ContactPlanStep selectById(@Param("stepId") Long stepId);

    @Select("SELECT * FROM t_contact_plan_step WHERE plan_id = #{planId} ORDER BY step_order ASC")
    List<ContactPlanStep> selectByPlan(@Param("planId") Long planId);

    @Select("SELECT * FROM t_contact_plan_step " +
            "WHERE plan_id = #{planId} AND step_order = #{stepOrder} LIMIT 1")
    ContactPlanStep selectByPlanAndOrder(@Param("planId") Long planId, @Param("stepOrder") int stepOrder);

    @Update("UPDATE t_contact_plan_step SET status = #{status}, result = #{result}, " +
            "completed_at = NOW(), updated_at = NOW() WHERE id = #{stepId}")
    int updateStatus(@Param("stepId") Long stepId,
                     @Param("status") StepStatus status,
                     @Param("result") ContactResult result);

    @Update("UPDATE t_contact_plan_step SET trigger_time = #{triggerTime}, status = #{status}, " +
            "updated_at = NOW() WHERE id = #{stepId}")
    int updateTriggerTime(@Param("stepId") Long stepId,
                          @Param("triggerTime") LocalDateTime triggerTime,
                          @Param("status") StepStatus status);

    @Update("UPDATE t_contact_plan_step SET timeout_time = #{timeoutTime}, trigger_time = NULL, " +
            "executed_at = NOW(), status = 'EXECUTING', updated_at = NOW() WHERE id = #{stepId}")
    int updateTimeoutTime(@Param("stepId") Long stepId, @Param("timeoutTime") LocalDateTime timeoutTime);

    @Update("UPDATE t_contact_plan_step SET retry_count = retry_count + 1, updated_at = NOW() WHERE id = #{stepId}")
    int incrementRetryCount(@Param("stepId") Long stepId);

    /** Cron：到期待触发步骤（关联计划非终态）。 */
    @Select("SELECT s.* FROM t_contact_plan_step s " +
            "JOIN t_contact_plan p ON p.id = s.plan_id " +
            "WHERE s.trigger_time <= #{now} " +
            "AND s.status IN ('PENDING','EXECUTING') " +
            "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') " +
            "ORDER BY s.trigger_time ASC LIMIT #{limit}")
    List<ContactPlanStep> selectDueSteps(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** Cron：回调超时步骤。 */
    @Select("SELECT s.* FROM t_contact_plan_step s " +
            "JOIN t_contact_plan p ON p.id = s.plan_id " +
            "WHERE s.timeout_time <= #{now} AND s.status = 'EXECUTING' " +
            "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') " +
            "ORDER BY s.timeout_time ASC LIMIT #{limit}")
    List<ContactPlanStep> selectTimeoutSteps(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
