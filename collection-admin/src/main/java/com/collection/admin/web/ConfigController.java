package com.collection.admin.web;

import com.collection.admin.web.dto.ConfigRollbackRequest;
import com.collection.admin.web.dto.EvaluationSettingsRequest;
import com.collection.admin.web.dto.PlanTemplateRequest;
import com.collection.admin.web.dto.ScriptTemplateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Phase 1.5 配置治理基础接口。 */
@RestController
@RequestMapping("/config")
public class ConfigController {

    private static final String TENANT_ID = "mocasa-ph";
    private static final String HOLDOUT_KEY = "HOLDOUT_RATIO";
    private static final BigDecimal DEFAULT_HOLDOUT_RATIO = new BigDecimal("0.10");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean schemaReady;

    public ConfigController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/evaluation-settings")
    public Map<String, Object> evaluationSettings() {
        ensureSchema();
        return ApiResponse.success(loadEvaluationSettings());
    }

    @PutMapping("/evaluation-settings")
    @Transactional
    public Map<String, Object> updateEvaluationSettings(
            HttpServletRequest request, @Valid @RequestBody EvaluationSettingsRequest body) {
        ensureSchema();
        String operator = currentUser(request);
        long nextConfigVersion = nextConfigVersion();
        int currentRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM t_evaluation_setting WHERE tenant_id = ? AND setting_key = ?",
                        Integer.class,
                        TENANT_ID,
                        HOLDOUT_KEY);

        if (currentRows == 0) {
            if (body.getVersion().intValue() != 0) {
                throw conflict();
            }
            jdbcTemplate.update(
                    "INSERT INTO t_evaluation_setting(tenant_id, setting_key, setting_value, status, config_version, version, updated_by, created_at, updated_at) "
                            + "VALUES(?, ?, JSON_OBJECT('holdoutRatio', ?), 'ACTIVE', ?, 1, ?, NOW(), NOW())",
                    TENANT_ID,
                    HOLDOUT_KEY,
                    body.getHoldoutRatio(),
                    nextConfigVersion,
                    operator);
        } else {
            int updated =
                    jdbcTemplate.update(
                            "UPDATE t_evaluation_setting SET setting_value = JSON_OBJECT('holdoutRatio', ?), "
                                    + "config_version = ?, version = version + 1, updated_by = ?, updated_at = NOW() "
                                    + "WHERE tenant_id = ? AND setting_key = ? AND version = ?",
                            body.getHoldoutRatio(),
                            nextConfigVersion,
                            operator,
                            TENANT_ID,
                            HOLDOUT_KEY,
                            body.getVersion());
            if (updated == 0) {
                throw conflict();
            }
        }

        publishConfigVersion(nextConfigVersion);
        insertEvaluationHistory(nextConfigVersion, body.getHoldoutRatio(), operator, body.getReason());
        appendChangeLog(
                "evaluation_setting",
                HOLDOUT_KEY,
                nextConfigVersion - 1,
                nextConfigVersion,
                null,
                body.getReason(),
                operator,
                "update holdout_ratio");
        return ApiResponse.success(loadEvaluationSettings());
    }

    @GetMapping("/versions")
    public Map<String, Object> versions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        ensureSchema();
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(100, pageSize));
        int offset = (p - 1) * size;

        Long total =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM t_config_change_log", Long.class);
        List<Map<String, Object>> items =
                jdbcTemplate.query(
                        "SELECT id, config_type, config_key, from_version, to_version, diff_summary, rollback_ref, reason, operator, created_at "
                                + "FROM t_config_change_log ORDER BY created_at DESC LIMIT ? OFFSET ?",
                        new Object[] {size, offset},
                        (rs, rowNum) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id", rs.getLong("id"));
                            row.put("configType", rs.getString("config_type"));
                            row.put("configKey", rs.getString("config_key"));
                            row.put("fromVersion", rs.getObject("from_version"));
                            row.put("toVersion", rs.getObject("to_version"));
                            row.put("diffSummary", rs.getString("diff_summary"));
                            row.put("rollbackRef", rs.getObject("rollback_ref"));
                            row.put("reason", rs.getString("reason"));
                            row.put("operator", rs.getString("operator"));
                            row.put("createdAt", rs.getTimestamp("created_at"));
                            return row;
                        });

        Map<String, Object> pageData = new LinkedHashMap<>();
        pageData.put("items", items);
        pageData.put("page", p);
        pageData.put("pageSize", size);
        pageData.put("total", total == null ? 0 : total);
        return ApiResponse.success(pageData);
    }

    @PostMapping("/rollback")
    @Transactional
    public Map<String, Object> rollback(
            HttpServletRequest request, @Valid @RequestBody ConfigRollbackRequest body) {
        ensureSchema();
        String operator = currentUser(request);
        BigDecimal restoredRatio = loadHoldoutRatioAtVersion(body.getTargetVersion());
        long nextConfigVersion = nextConfigVersion();
        upsertHoldoutRatio(nextConfigVersion, restoredRatio, operator);
        publishConfigVersion(nextConfigVersion);
        insertEvaluationHistory(nextConfigVersion, restoredRatio, operator, body.getReason());
        appendChangeLog(
                "rollback",
                "global",
                nextConfigVersion - 1,
                nextConfigVersion,
                body.getTargetVersion(),
                body.getReason(),
                operator,
                "rollback evaluation settings to version " + body.getTargetVersion());

        Map<String, Object> data = loadEvaluationSettings();
        data.put("rollbackRef", body.getTargetVersion());
        data.put("scope", "evaluation_settings");
        return ApiResponse.success(data);
    }

    // ===================== Script templates (SMS/Push/Email) =====================

    @GetMapping("/script-templates")
    public Map<String, Object> listScriptTemplates(@RequestParam(required = false) String channel) {
        ensureSchema();
        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, script_slot, channel, locale, "
                                + "JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.body')) AS body, "
                                + "JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.title')) AS title, "
                                + "external_template_id, status, config_version, version, updated_by, updated_at "
                                + "FROM t_script_template WHERE tenant_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(TENANT_ID);
        if (StringUtils.isNotBlank(channel)) {
            sql.append("AND channel = ? ");
            args.add(channel.trim().toUpperCase());
        }
        sql.append("ORDER BY channel, script_slot");
        List<Map<String, Object>> items =
                jdbcTemplate.query(sql.toString(), args.toArray(), scriptRowMapper());
        return ApiResponse.success(items);
    }

    @PutMapping("/script-templates")
    @Transactional
    public Map<String, Object> updateScriptTemplate(
            HttpServletRequest request, @Valid @RequestBody ScriptTemplateRequest body) {
        ensureSchema();
        String operator = currentUser(request);
        String slot = body.getScriptSlot().trim();
        String channel = body.getChannel().trim().toUpperCase();
        String locale = StringUtils.isBlank(body.getLocale()) ? "en" : body.getLocale().trim();
        String contentJson = buildScriptContentJson(body);
        long nextConfigVersion = nextConfigVersion();

        List<Integer> existing =
                jdbcTemplate.query(
                        "SELECT version FROM t_script_template WHERE tenant_id = ? AND script_slot = ? AND channel = ? AND locale = ?",
                        new Object[] {TENANT_ID, slot, channel, locale},
                        (rs, i) -> rs.getInt("version"));
        if (existing.isEmpty()) {
            if (body.getVersion() != 0) {
                throw conflict();
            }
            jdbcTemplate.update(
                    "INSERT INTO t_script_template(tenant_id, script_slot, channel, locale, content_json, external_template_id, status, config_version, version, updated_by, created_at, updated_at) "
                            + "VALUES(?, ?, ?, ?, CAST(? AS JSON), ?, 'ACTIVE', ?, 1, ?, NOW(), NOW())",
                    TENANT_ID,
                    slot,
                    channel,
                    locale,
                    contentJson,
                    body.getExternalTemplateId(),
                    nextConfigVersion,
                    operator);
        } else {
            int updated =
                    jdbcTemplate.update(
                            "UPDATE t_script_template SET content_json = CAST(? AS JSON), external_template_id = ?, "
                                    + "status = 'ACTIVE', config_version = ?, version = version + 1, updated_by = ?, updated_at = NOW() "
                                    + "WHERE tenant_id = ? AND script_slot = ? AND channel = ? AND locale = ? AND version = ?",
                            contentJson,
                            body.getExternalTemplateId(),
                            nextConfigVersion,
                            operator,
                            TENANT_ID,
                            slot,
                            channel,
                            locale,
                            body.getVersion());
            if (updated == 0) {
                throw conflict();
            }
        }

        publishConfigVersion(nextConfigVersion);
        appendChangeLog(
                "script",
                channel + "/" + slot,
                nextConfigVersion - 1,
                nextConfigVersion,
                null,
                body.getReason(),
                operator,
                "update script " + channel + "/" + slot);
        return ApiResponse.success(loadScriptTemplate(slot, channel, locale));
    }

    // ===================== Plan templates =====================

    @GetMapping("/plan-templates")
    public Map<String, Object> listPlanTemplates() {
        ensureSchema();
        List<Map<String, Object>> items =
                jdbcTemplate.query(
                        "SELECT id, template_code, stage, product_code, tone, plan_json, status, "
                                + "config_version, version, updated_by, updated_at "
                                + "FROM t_contact_plan_template WHERE tenant_id = ? ORDER BY stage, template_code",
                        new Object[] {TENANT_ID},
                        planRowMapper());
        return ApiResponse.success(items);
    }

    @PutMapping("/plan-templates")
    @Transactional
    public Map<String, Object> updatePlanTemplate(
            HttpServletRequest request, @Valid @RequestBody PlanTemplateRequest body) {
        ensureSchema();
        String operator = currentUser(request);
        String code = body.getTemplateCode().trim();
        String stage = body.getStage().trim().toUpperCase();
        String tone = StringUtils.isBlank(body.getTone()) ? "STANDARD" : body.getTone().trim();
        String planJson = buildPlanJson(body);
        long nextConfigVersion = nextConfigVersion();

        List<Integer> existing =
                jdbcTemplate.query(
                        "SELECT version FROM t_contact_plan_template WHERE tenant_id = ? AND template_code = ?",
                        new Object[] {TENANT_ID, code},
                        (rs, i) -> rs.getInt("version"));
        if (existing.isEmpty()) {
            if (body.getVersion() != 0) {
                throw conflict();
            }
            jdbcTemplate.update(
                    "INSERT INTO t_contact_plan_template(tenant_id, template_code, stage, product_code, tone, plan_json, status, config_version, version, created_by, updated_by, created_at, updated_at) "
                            + "VALUES(?, ?, ?, ?, ?, CAST(? AS JSON), 'ACTIVE', ?, 1, ?, ?, NOW(), NOW())",
                    TENANT_ID,
                    code,
                    stage,
                    body.getProductCode(),
                    tone,
                    planJson,
                    nextConfigVersion,
                    operator,
                    operator);
        } else {
            int updated =
                    jdbcTemplate.update(
                            "UPDATE t_contact_plan_template SET stage = ?, product_code = ?, tone = ?, plan_json = CAST(? AS JSON), "
                                    + "status = 'ACTIVE', config_version = ?, version = version + 1, updated_by = ?, updated_at = NOW() "
                                    + "WHERE tenant_id = ? AND template_code = ? AND version = ?",
                            stage,
                            body.getProductCode(),
                            tone,
                            planJson,
                            nextConfigVersion,
                            operator,
                            TENANT_ID,
                            code,
                            body.getVersion());
            if (updated == 0) {
                throw conflict();
            }
        }

        publishConfigVersion(nextConfigVersion);
        appendChangeLog(
                "plan_template",
                code,
                nextConfigVersion - 1,
                nextConfigVersion,
                null,
                body.getReason(),
                operator,
                "update plan template " + code + " (" + stage + ")");
        return ApiResponse.success(loadPlanTemplate(code));
    }

    @DeleteMapping("/script-templates")
    @Transactional
    public Map<String, Object> deactivateScriptTemplate(
            HttpServletRequest request,
            @RequestParam String scriptSlot,
            @RequestParam String channel,
            @RequestParam(required = false) String locale) {
        ensureSchema();
        String operator = currentUser(request);
        String ch = channel.trim().toUpperCase();
        String loc = StringUtils.isBlank(locale) ? "en" : locale.trim();
        long nextConfigVersion = nextConfigVersion();
        int updated =
                jdbcTemplate.update(
                        "UPDATE t_script_template SET status = 'INACTIVE', config_version = ?, version = version + 1, "
                                + "updated_by = ?, updated_at = NOW() "
                                + "WHERE tenant_id = ? AND script_slot = ? AND channel = ? AND locale = ? AND status = 'ACTIVE'",
                        nextConfigVersion,
                        operator,
                        TENANT_ID,
                        scriptSlot.trim(),
                        ch,
                        loc);
        if (updated > 0) {
            publishConfigVersion(nextConfigVersion);
            appendChangeLog(
                    "script",
                    ch + "/" + scriptSlot,
                    nextConfigVersion - 1,
                    nextConfigVersion,
                    null,
                    "deactivate",
                    operator,
                    "deactivate script " + ch + "/" + scriptSlot);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deactivated", updated);
        return ApiResponse.success(data);
    }

    @DeleteMapping("/plan-templates/{templateCode}")
    @Transactional
    public Map<String, Object> deactivatePlanTemplate(
            HttpServletRequest request, @PathVariable String templateCode) {
        ensureSchema();
        String operator = currentUser(request);
        long nextConfigVersion = nextConfigVersion();
        int updated =
                jdbcTemplate.update(
                        "UPDATE t_contact_plan_template SET status = 'INACTIVE', config_version = ?, version = version + 1, "
                                + "updated_by = ?, updated_at = NOW() "
                                + "WHERE tenant_id = ? AND template_code = ? AND status = 'ACTIVE'",
                        nextConfigVersion,
                        operator,
                        TENANT_ID,
                        templateCode.trim());
        if (updated > 0) {
            publishConfigVersion(nextConfigVersion);
            appendChangeLog(
                    "plan_template",
                    templateCode,
                    nextConfigVersion - 1,
                    nextConfigVersion,
                    null,
                    "deactivate",
                    operator,
                    "deactivate plan template " + templateCode);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deactivated", updated);
        return ApiResponse.success(data);
    }

    private org.springframework.jdbc.core.RowMapper<Map<String, Object>> scriptRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("scriptSlot", rs.getString("script_slot"));
            row.put("channel", rs.getString("channel"));
            row.put("locale", rs.getString("locale"));
            row.put("body", rs.getString("body"));
            row.put("title", rs.getString("title"));
            row.put("externalTemplateId", rs.getString("external_template_id"));
            row.put("status", rs.getString("status"));
            row.put("configVersion", rs.getObject("config_version"));
            row.put("version", rs.getInt("version"));
            row.put("updatedBy", rs.getString("updated_by"));
            row.put("updatedAt", rs.getTimestamp("updated_at"));
            return row;
        };
    }

    private org.springframework.jdbc.core.RowMapper<Map<String, Object>> planRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("templateCode", rs.getString("template_code"));
            row.put("stage", rs.getString("stage"));
            row.put("productCode", rs.getString("product_code"));
            row.put("tone", rs.getString("tone"));
            row.put("planJson", rs.getString("plan_json"));
            row.put("status", rs.getString("status"));
            row.put("configVersion", rs.getObject("config_version"));
            row.put("version", rs.getInt("version"));
            row.put("updatedBy", rs.getString("updated_by"));
            row.put("updatedAt", rs.getTimestamp("updated_at"));
            return row;
        };
    }

    private Map<String, Object> loadScriptTemplate(String slot, String channel, String locale) {
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT id, script_slot, channel, locale, "
                                + "JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.body')) AS body, "
                                + "JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.title')) AS title, "
                                + "external_template_id, status, config_version, version, updated_by, updated_at "
                                + "FROM t_script_template WHERE tenant_id = ? AND script_slot = ? AND channel = ? AND locale = ?",
                        new Object[] {TENANT_ID, slot, channel, locale},
                        scriptRowMapper());
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private Map<String, Object> loadPlanTemplate(String code) {
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT id, template_code, stage, product_code, tone, plan_json, status, "
                                + "config_version, version, updated_by, updated_at "
                                + "FROM t_contact_plan_template WHERE tenant_id = ? AND template_code = ?",
                        new Object[] {TENANT_ID, code},
                        planRowMapper());
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private String buildScriptContentJson(ScriptTemplateRequest body) {
        Map<String, Object> content = new LinkedHashMap<>();
        if (body.getBody() != null) {
            content.put("body", body.getBody());
        }
        if (body.getTitle() != null) {
            content.put("title", body.getTitle());
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid content");
        }
    }

    private String buildPlanJson(PlanTemplateRequest body) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PlanTemplateRequest.Step s : body.getSteps()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("channel", s.getChannel().trim().toUpperCase());
            step.put("delayMin", s.getDelayMin());
            step.put("observeMin", s.getObserveMin());
            step.put("templateId", s.getTemplateId());
            steps.add(step);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("steps", steps);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid steps");
        }
    }

    private Map<String, Object> loadEvaluationSettings() {
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT JSON_UNQUOTE(JSON_EXTRACT(setting_value, '$.holdoutRatio')) AS holdout_ratio, "
                                + "config_version, version, updated_by, updated_at "
                                + "FROM t_evaluation_setting WHERE tenant_id = ? AND setting_key = ?",
                        new Object[] {TENANT_ID, HOLDOUT_KEY},
                        (rs, rowNum) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("holdoutRatio", new BigDecimal(rs.getString("holdout_ratio")));
                            row.put("configVersion", rs.getLong("config_version"));
                            row.put("version", rs.getInt("version"));
                            row.put("updatedBy", rs.getString("updated_by"));
                            row.put("updatedAt", rs.getTimestamp("updated_at"));
                            return row;
                        });
        if (!rows.isEmpty()) {
            return rows.get(0);
        }

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("holdoutRatio", DEFAULT_HOLDOUT_RATIO);
        defaults.put("configVersion", currentConfigVersion());
        defaults.put("version", 0);
        defaults.put("updatedBy", null);
        defaults.put("updatedAt", null);
        return defaults;
    }

    private synchronized void ensureSchema() {
        if (schemaReady) {
            return;
        }
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_evaluation_setting ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "tenant_id VARCHAR(32) NOT NULL DEFAULT 'mocasa-ph', "
                        + "setting_key VARCHAR(64) NOT NULL, "
                        + "setting_value JSON NOT NULL, "
                        + "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', "
                        + "config_version BIGINT NOT NULL, "
                        + "version INT NOT NULL DEFAULT 0, "
                        + "updated_by VARCHAR(64) NULL, "
                        + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                        + "UNIQUE KEY uk_tenant_setting (tenant_id, setting_key), "
                        + "INDEX idx_config_version (config_version)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_evaluation_setting_history ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "tenant_id VARCHAR(32) NOT NULL DEFAULT 'mocasa-ph', "
                        + "setting_key VARCHAR(64) NOT NULL, "
                        + "setting_value JSON NOT NULL, "
                        + "config_version BIGINT NOT NULL, "
                        + "operator VARCHAR(64) NOT NULL, "
                        + "reason VARCHAR(512) NULL, "
                        + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "UNIQUE KEY uk_tenant_setting_version (tenant_id, setting_key, config_version), "
                        + "INDEX idx_setting_version (setting_key, config_version)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing(
                "ALTER TABLE t_config_change_log "
                        + "ADD COLUMN rollback_ref BIGINT NULL COMMENT 'rollback source config version' AFTER diff_summary");
        addColumnIfMissing(
                "ALTER TABLE t_config_change_log "
                        + "ADD COLUMN reason VARCHAR(512) NULL COMMENT 'operation reason' AFTER rollback_ref");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_script_template ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "tenant_id VARCHAR(32) NOT NULL DEFAULT 'mocasa-ph', "
                        + "script_slot VARCHAR(64) NOT NULL, "
                        + "channel VARCHAR(32) NOT NULL, "
                        + "locale VARCHAR(16) NOT NULL DEFAULT 'en', "
                        + "content_json JSON NOT NULL, "
                        + "external_template_id VARCHAR(128) NULL, "
                        + "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', "
                        + "config_version BIGINT NOT NULL, "
                        + "version INT NOT NULL DEFAULT 0, "
                        + "created_by VARCHAR(64) NULL, "
                        + "updated_by VARCHAR(64) NULL, "
                        + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                        + "UNIQUE KEY uk_slot_channel_locale (tenant_id, script_slot, channel, locale), "
                        + "INDEX idx_config_version (config_version)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_contact_plan_template ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "tenant_id VARCHAR(32) NOT NULL DEFAULT 'mocasa-ph', "
                        + "template_code VARCHAR(64) NOT NULL, "
                        + "stage VARCHAR(16) NOT NULL, "
                        + "product_code VARCHAR(64) NULL, "
                        + "risk_tier VARCHAR(16) NULL, "
                        + "tone VARCHAR(32) NULL, "
                        + "plan_json JSON NOT NULL, "
                        + "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', "
                        + "config_version BIGINT NOT NULL, "
                        + "version INT NOT NULL DEFAULT 0, "
                        + "created_by VARCHAR(64) NULL, "
                        + "updated_by VARCHAR(64) NULL, "
                        + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                        + "UNIQUE KEY uk_tenant_code (tenant_id, template_code), "
                        + "INDEX idx_stage_product (stage, product_code), "
                        + "INDEX idx_config_version (config_version)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        schemaReady = true;
    }

    private void addColumnIfMissing(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (DataAccessException e) {
            String message = e.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate column")) {
                throw e;
            }
        }
    }

    private BigDecimal loadHoldoutRatioAtVersion(long targetVersion) {
        List<BigDecimal> values =
                jdbcTemplate.query(
                        "SELECT JSON_UNQUOTE(JSON_EXTRACT(setting_value, '$.holdoutRatio')) AS holdout_ratio "
                                + "FROM t_evaluation_setting_history "
                                + "WHERE tenant_id = ? AND setting_key = ? AND config_version <= ? "
                                + "ORDER BY config_version DESC LIMIT 1",
                        new Object[] {TENANT_ID, HOLDOUT_KEY, targetVersion},
                        (rs, rowNum) -> new BigDecimal(rs.getString("holdout_ratio")));
        if (values.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No evaluation setting snapshot for targetVersion");
        }
        return values.get(0);
    }

    private void upsertHoldoutRatio(long configVersion, BigDecimal holdoutRatio, String operator) {
        int updated =
                jdbcTemplate.update(
                        "UPDATE t_evaluation_setting SET setting_value = JSON_OBJECT('holdoutRatio', ?), "
                                + "config_version = ?, version = version + 1, updated_by = ?, updated_at = NOW() "
                                + "WHERE tenant_id = ? AND setting_key = ?",
                        holdoutRatio,
                        configVersion,
                        operator,
                        TENANT_ID,
                        HOLDOUT_KEY);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO t_evaluation_setting(tenant_id, setting_key, setting_value, status, config_version, version, updated_by, created_at, updated_at) "
                            + "VALUES(?, ?, JSON_OBJECT('holdoutRatio', ?), 'ACTIVE', ?, 1, ?, NOW(), NOW())",
                    TENANT_ID,
                    HOLDOUT_KEY,
                    holdoutRatio,
                    configVersion,
                    operator);
        }
    }

    private void insertEvaluationHistory(
            long configVersion, BigDecimal holdoutRatio, String operator, String reason) {
        jdbcTemplate.update(
                "INSERT INTO t_evaluation_setting_history(tenant_id, setting_key, setting_value, config_version, operator, reason, created_at) "
                        + "VALUES(?, ?, JSON_OBJECT('holdoutRatio', ?), ?, ?, ?, NOW()) "
                        + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), operator = VALUES(operator), reason = VALUES(reason)",
                TENANT_ID,
                HOLDOUT_KEY,
                holdoutRatio,
                configVersion,
                operator,
                reason);
    }

    private void appendChangeLog(
            String configType,
            String configKey,
            long fromVersion,
            long toVersion,
            Long rollbackRef,
            String reason,
            String operator,
            String summary) {
        jdbcTemplate.update(
                "INSERT INTO t_config_change_log(tenant_id, config_type, config_key, from_version, to_version, diff_summary, rollback_ref, reason, operator, created_at) "
                        + "VALUES(?, ?, ?, ?, ?, JSON_OBJECT('summary', ?), ?, ?, ?, NOW())",
                TENANT_ID,
                configType,
                configKey,
                fromVersion,
                toVersion,
                summary,
                rollbackRef,
                reason,
                operator);
    }

    private long nextConfigVersion() {
        return currentConfigVersion() + 1;
    }

    private long currentConfigVersion() {
        Long current =
                jdbcTemplate.queryForObject(
                        "SELECT current_version FROM t_config_version_seq WHERE id = 1", Long.class);
        return current == null ? 0L : current.longValue();
    }

    private void publishConfigVersion(long configVersion) {
        jdbcTemplate.update(
                "UPDATE t_config_version_seq SET current_version = ?, updated_at = NOW() WHERE id = 1",
                configVersion);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "The configuration has been modified by another user, please refresh and retry");
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
}
