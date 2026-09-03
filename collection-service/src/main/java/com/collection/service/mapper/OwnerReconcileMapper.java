package com.collection.service.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 当日 owner 对账水位与分页扫描。 */
@Mapper
public interface OwnerReconcileMapper {

    @Select("SELECT COUNT(*) FROM t_ai_owner_reconcile WHERE reconcile_date = #{date}")
    int countByDate(@Param("date") LocalDate date);

    @Insert(
            "INSERT INTO t_ai_owner_reconcile (reconcile_date, completed_at, inbox_case_event_count) "
                    + "VALUES (#{date}, #{completedAt}, #{inboxCount}) "
                    + "ON DUPLICATE KEY UPDATE completed_at = VALUES(completed_at), "
                    + "inbox_case_event_count = VALUES(inbox_case_event_count)")
    int upsertCompleted(
            @Param("date") LocalDate date,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("inboxCount") int inboxCount);

    @Select(
            "SELECT COUNT(*) FROM t_ai_collection_inbox "
                    + "WHERE message_type = 'caseEvent' "
                    + "AND LEFT(COALESCE("
                    + "JSON_UNQUOTE(JSON_EXTRACT(payload, '$.occurredAt')), "
                    + "JSON_UNQUOTE(JSON_EXTRACT(payload, '$.data.occurredAt'))"
                    + "), 10) = #{today}")
    int countCaseEventsOnOccurredAt(@Param("today") String today);

    @Select(
            "SELECT DISTINCT p.case_id FROM t_contact_plan p "
                    + "INNER JOIN t_ai_collection c ON c.case_id = p.case_id "
                    + "WHERE p.status NOT IN ('PLAN_COMPLETED', 'PLAN_CANCELLED') "
                    + "AND IFNULL(p.renewal_pending, 0) = 0 "
                    + "AND (c.owner_date IS NULL OR c.owner_date <> #{today}) "
                    + "AND p.case_id > #{afterCaseId} "
                    + "ORDER BY p.case_id LIMIT #{limit}")
    List<Long> selectLeaveCaseIdsAfter(
            @Param("today") LocalDate today,
            @Param("afterCaseId") long afterCaseId,
            @Param("limit") int limit);

    @Select(
            "SELECT c.case_id FROM t_ai_collection c "
                    + "WHERE c.owner_date = #{today} "
                    + "AND c.collection_status = 'IN_COLLECTION' "
                    + "AND c.stage IS NOT NULL AND c.stage <> '' "
                    + "AND c.case_id > #{afterCaseId} "
                    + "AND NOT EXISTS ("
                    + "  SELECT 1 FROM t_contact_plan p "
                    + "  WHERE p.case_id = c.case_id "
                    + "  AND p.status NOT IN ('PLAN_COMPLETED', 'PLAN_CANCELLED') "
                    + "  AND IFNULL(p.renewal_pending, 0) = 0"
                    + ") "
                    + "ORDER BY c.case_id LIMIT #{limit}")
    List<Long> selectEnterCaseIdsAfter(
            @Param("today") LocalDate today,
            @Param("afterCaseId") long afterCaseId,
            @Param("limit") int limit);
}
