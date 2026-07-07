package com.collection.admin.web;

import com.collection.admin.web.dto.ComplianceActionRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 基础合规操作（冻结/解冻/终态取消）。 */
@RestController
@RequestMapping("/compliance")
public class ComplianceOpsController {

    private final JdbcTemplate jdbcTemplate;

    public ComplianceOpsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/freeze")
    public Map<String, Object> freeze(
            HttpServletRequest request, @Valid @RequestBody ComplianceActionRequest body) {
        long caseId = body.getCaseId();
        Long userId = body.getUserId();
        String reason = body.getReason();
        String operator = currentUser(request);
        upsertFreeze(caseId, userId, "FROZEN", reason, operator);
        appendAuditLog("compliance", "case:" + caseId, reason, operator);
        return ApiResponse.success(statusView(caseId, "FROZEN"));
    }

    @PostMapping("/unfreeze")
    public Map<String, Object> unfreeze(
            HttpServletRequest request, @Valid @RequestBody ComplianceActionRequest body) {
        long caseId = body.getCaseId();
        String reason = body.getReason();
        String operator = currentUser(request);
        upsertFreeze(caseId, null, "RELEASED", reason, operator);
        appendAuditLog("compliance", "case:" + caseId, reason, operator);
        return ApiResponse.success(statusView(caseId, "RELEASED"));
    }

    @PostMapping("/escalate")
    public Map<String, Object> escalate(
            HttpServletRequest request, @Valid @RequestBody ComplianceActionRequest body) {
        long caseId = body.getCaseId();
        String reason = body.getReason();
        String operator = currentUser(request);
        upsertFreeze(caseId, null, "ESCALATED", reason, operator);
        jdbcTemplate.update(
                "UPDATE t_contact_plan SET status = 'PLAN_CANCELLED', cancel_reason = 'COMPLAINT', updated_at = NOW() "
                        + "WHERE case_id = ? AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')",
                caseId);
        appendAuditLog("compliance", "case:" + caseId, reason, operator);
        return ApiResponse.success(statusView(caseId, "ESCALATED"));
    }

    private void upsertFreeze(
            long caseId, Long userId, String status, String reason, String operator) {
        jdbcTemplate.update(
                "INSERT INTO t_admin_case_freeze(case_id, user_id, freeze_type, status, reason, operator, escalated_at, created_at, updated_at) "
                        + "VALUES(?, ?, 'COMPLAINT', ?, ?, ?, CASE WHEN ?='ESCALATED' THEN NOW() ELSE NULL END, NOW(), NOW()) "
                        + "ON DUPLICATE KEY UPDATE user_id = COALESCE(VALUES(user_id), user_id), status = VALUES(status), "
                        + "reason = VALUES(reason), operator = VALUES(operator), escalated_at = CASE WHEN VALUES(status)='ESCALATED' THEN NOW() ELSE escalated_at END, "
                        + "updated_at = NOW()",
                caseId,
                userId,
                status,
                reason,
                operator,
                status);
    }

    private void appendAuditLog(String configType, String key, String reason, String operator) {
        Long next =
                jdbcTemplate.queryForObject(
                        "SELECT current_version + 1 FROM t_config_version_seq WHERE id = 1",
                        Long.class);
        if (next == null) {
            next = 1L;
        }
        jdbcTemplate.update(
                "UPDATE t_config_version_seq SET current_version = ?, updated_at = NOW() WHERE id = 1",
                next);
        jdbcTemplate.update(
                "INSERT INTO t_config_change_log(tenant_id, config_type, config_key, from_version, to_version, diff_summary, operator, created_at) "
                        + "VALUES('mocasa-ph', ?, ?, ?, ?, JSON_OBJECT('reason', ?), ?, NOW())",
                configType,
                key,
                next - 1,
                next,
                reason,
                operator);
    }

    private static String currentUser(HttpServletRequest request) {
        Object u =
                request.getSession(false) == null
                        ? null
                        : request.getSession(false).getAttribute("ADMIN_USER");
        if (u instanceof Map) {
            Object name = ((Map<?, ?>) u).get("username");
            if (name != null) {
                return String.valueOf(name);
            }
        }
        return "system";
    }

    private static Map<String, Object> statusView(long caseId, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("status", status);
        return result;
    }
}
