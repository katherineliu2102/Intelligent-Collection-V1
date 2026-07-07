package com.collection.admin.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 系统管理最小接口。 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final JdbcTemplate jdbcTemplate;

    public AdminController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Object user = request.getSession(false) == null ? null : request.getSession(false).getAttribute("ADMIN_USER");
        if (user == null) {
            return ApiResponse.failure("UNAUTHORIZED", "Login required");
        }
        return ApiResponse.success(user);
    }

    @GetMapping("/audit-logs")
    public Map<String, Object> auditLogs(
            @RequestParam(required = false) String configType,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(100, pageSize));
        int offset = (p - 1) * size;

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.isNotBlank(configType)) {
            where.append(" AND config_type = ? ");
            args.add(configType.trim());
        }
        if (StringUtils.isNotBlank(operator)) {
            where.append(" AND operator = ? ");
            args.add(operator.trim());
        }
        if (StringUtils.isNotBlank(from)) {
            where.append(" AND created_at >= ? ");
            args.add(from.trim());
        }
        if (StringUtils.isNotBlank(to)) {
            where.append(" AND created_at <= ? ");
            args.add(to.trim());
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM t_config_change_log " + where, args.toArray(), Long.class);
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add(size);
        dataArgs.add(offset);
        List<Map<String, Object>> items = jdbcTemplate.query(
                "SELECT id, config_type, config_key, from_version, to_version, diff_summary, operator, created_at "
                        + "FROM t_config_change_log "
                        + where
                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                dataArgs.toArray(),
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("configType", rs.getString("config_type"));
                    row.put("configKey", rs.getString("config_key"));
                    row.put("fromVersion", rs.getObject("from_version"));
                    row.put("toVersion", rs.getObject("to_version"));
                    row.put("diffSummary", rs.getString("diff_summary"));
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
}

