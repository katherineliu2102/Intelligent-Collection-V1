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
            "INSERT INTO t_ai_owner_reconcile (reconcile_date, completed_at, owner_case_count) "
                    + "VALUES (#{date}, #{completedAt}, #{ownerCaseCount}) "
                    + "ON DUPLICATE KEY UPDATE completed_at = VALUES(completed_at), "
                    + "owner_case_count = VALUES(owner_case_count)")
    int upsertCompleted(
            @Param("date") LocalDate date,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("ownerCaseCount") int ownerCaseCount);

    /**
     * 零收检测：当日刷新过归属日的案件数，走 idx_ai_collection_owner_date。
     *
     * <p>不数 inbox：每条 caseEvent 无论指纹是否相同都会刷新 {@code owner_date}（含 updateOwnerDate 路径）， 因此「owner_date
     * = 当日」的行数即「当日收到 caseEvent 的案件数」，且时区口径与投影写入一致 （都经 CasePayloadMapper 按 PHT 日历日转换）。对 inbox
     * payload 做 LEFT(occurredAt,10) 的字符串取前缀， 在上游发 UTC 时间戳时会与 PHT 日历日分叉（UTC 16:00 后 = PHT
     * 次日），导致每日误判零收。
     */
    @Select("SELECT COUNT(*) FROM t_ai_collection WHERE owner_date = #{date}")
    int countOwnerDateCases(@Param("date") LocalDate date);

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
