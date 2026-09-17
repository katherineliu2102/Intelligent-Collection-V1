package com.collection.admin.alert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** A3 必须入异常队列，不能只通知（§5.5.4）。同 step 已有 OPEN 行则只更新计数文案。 */
@Repository
public class OpsExceptionWriter {

    private final JdbcTemplate jdbc;

    public OpsExceptionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertOpen(
            String type,
            String channel,
            String errorCode,
            Long caseId,
            Long planId,
            Long stepId,
            String severity,
            String message,
            String clusterKey) {
        Integer open =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_ops_exception WHERE cluster_key=? AND status='OPEN'",
                        Integer.class,
                        clusterKey);
        if (open != null && open > 0) {
            jdbc.update(
                    "UPDATE t_ops_exception SET message=?, updated_at=NOW() "
                            + "WHERE cluster_key=? AND status='OPEN'",
                    message,
                    clusterKey);
            return;
        }
        jdbc.update(
                "INSERT INTO t_ops_exception(exception_type, channel, error_code, case_id, plan_id, "
                        + "step_id, severity, message, status, cluster_key, created_at, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,'OPEN',?,NOW(),NOW())",
                type,
                channel,
                errorCode,
                caseId,
                planId,
                stepId,
                severity,
                message,
                clusterKey);
    }
}
