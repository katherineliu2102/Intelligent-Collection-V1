package com.collection.admin.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 开发自测辅助接口：写入最小测试数据。 */
@RestController
@RequestMapping("/mock/admin")
public class AdminMockDataController {

    private final JdbcTemplate jdbcTemplate;

    public AdminMockDataController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/seed-case")
    public Map<String, Object> seedCase(@RequestBody(required = false) Map<String, Object> body) {
        long caseId =
                body == null || body.get("caseId") == null
                        ? 92002L
                        : Long.parseLong(String.valueOf(body.get("caseId")));
        long userId =
                body == null || body.get("userId") == null
                        ? caseId
                        : Long.parseLong(String.valueOf(body.get("userId")));
        String rowId = "ADMIN_TEST_" + caseId;
        jdbcTemplate.update("DELETE FROM t_collection WHERE id = ?", rowId);
        jdbcTemplate.update(
                "INSERT INTO t_collection "
                        + "(id, loan_id, user_id, loan_no, colleciton_status, real_name, phone, email, "
                        + "app_name, platform_name, apply_amount, apply_time, disburse_amount, disburse_date, deadline, "
                        + "repayment_date, overdue_days, principal, interest, overdue, total_not_paid, pay_count, create_time) "
                        + "VALUES (?, ?, ?, ?, '3', ?, ?, ?, 'QuickLoan', 'Android', "
                        + "5000.00, NOW(), 5000.00, DATE_SUB(CURDATE(), INTERVAL 31 DAY), 30, "
                        + "DATE_SUB(CURDATE(), INTERVAL 4 DAY), 4, 5000.00, 150.00, 100.00, 5250.00, 0, NOW())",
                rowId,
                String.valueOf(caseId),
                String.valueOf(userId),
                "LN" + caseId,
                "Admin Test Case " + caseId,
                "+639000000001",
                "admin-test@example.com");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseId", caseId);
        data.put("userId", userId);
        data.put("rowId", rowId);
        return ApiResponse.success(data);
    }

    @PostMapping("/seed-config")
    public Map<String, Object> seedConfig() {
        long cfgVer = 1L;
        jdbcTemplate.update(
                "INSERT INTO t_script_template(id, tenant_id, script_slot, channel, locale, content_json, status, config_version, version, updated_by) "
                        + "VALUES (101, 'mocasa-ph', 'S1_SMS_STANDARD', 'SMS', 'en', JSON_OBJECT('body', 'MOCASA Collections: {name}, overdue PHP {amount}. Pay: {repaymentUrl}'), 'ACTIVE', ?, 0, 'seed-config') "
                        + "ON DUPLICATE KEY UPDATE content_json = VALUES(content_json), config_version = VALUES(config_version), updated_by = VALUES(updated_by)",
                cfgVer);
        jdbcTemplate.update(
                "INSERT INTO t_contact_plan_template(tenant_id, template_code, stage, tone, plan_json, status, config_version, version, created_by, updated_by) "
                        + "VALUES ('mocasa-ph', 'SEED_S0_STANDARD', 'S0', 'STANDARD', "
                        + "JSON_OBJECT('steps', JSON_ARRAY(JSON_OBJECT('channel', 'SMS', 'delayMin', 0, 'observeMin', 0, 'templateId', 101))), "
                        + "'ACTIVE', ?, 0, 'seed-config', 'seed-config') "
                        + "ON DUPLICATE KEY UPDATE plan_json = VALUES(plan_json), config_version = VALUES(config_version), updated_by = VALUES(updated_by)",
                cfgVer);
        jdbcTemplate.update(
                "UPDATE t_config_version_seq SET current_version = GREATEST(current_version, ?), updated_at = NOW() WHERE id = 1",
                cfgVer);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configVersion", cfgVer);
        data.put("scriptTemplateId", 101);
        data.put("planTemplateCode", "SEED_S0_STANDARD");
        data.put("note", "Minimal seed only; run db/seed-admin-config.sql for full import");
        return ApiResponse.success(data);
    }

    @PostMapping("/seed-exception")
    public Map<String, Object> seedException(
            @RequestBody(required = false) Map<String, Object> body) {
        long caseId =
                body == null || body.get("caseId") == null
                        ? 92002L
                        : Long.parseLong(String.valueOf(body.get("caseId")));
        Long planId =
                body == null || body.get("planId") == null
                        ? null
                        : Long.parseLong(String.valueOf(body.get("planId")));
        Long stepId =
                body == null || body.get("stepId") == null
                        ? null
                        : Long.parseLong(String.valueOf(body.get("stepId")));
        String type =
                body == null || body.get("type") == null
                        ? "CALLBACK_TIMEOUT"
                        : String.valueOf(body.get("type"));
        String channel =
                body == null || body.get("channel") == null
                        ? "SMS"
                        : String.valueOf(body.get("channel"));
        String errorCode =
                body == null || body.get("errorCode") == null
                        ? "TIMEOUT"
                        : String.valueOf(body.get("errorCode"));
        String message =
                body == null || body.get("message") == null
                        ? "Generated by admin self-test script"
                        : String.valueOf(body.get("message"));
        String clusterKey = type + ":" + channel + ":" + errorCode;
        jdbcTemplate.update(
                "INSERT INTO t_ops_exception(exception_type, channel, error_code, case_id, plan_id, step_id, severity, message, detail_json, status, cluster_key, created_at, updated_at) "
                        + "VALUES(?, ?, ?, ?, ?, ?, 'WARN', ?, JSON_OBJECT('seed', true), 'OPEN', ?, NOW(), NOW())",
                type,
                channel,
                errorCode,
                caseId,
                planId,
                stepId,
                message,
                clusterKey);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("clusterKey", clusterKey);
        data.put("status", "OPEN");
        return ApiResponse.success(data);
    }
}
