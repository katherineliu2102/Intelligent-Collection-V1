package com.collection.admin.web;

import com.collection.admin.web.dto.ExceptionResolveRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/** 异常队列接口（Phase 1 逐条）。 */
@Validated
@RestController
@RequestMapping("/ops/exceptions")
public class OpsQueueController {

    private final JdbcTemplate jdbcTemplate;

    public OpsQueueController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(100, pageSize));
        int offset = (p - 1) * size;

        StringBuilder where = new StringBuilder(" WHERE status = ? ");
        List<Object> args = new ArrayList<>();
        args.add(status);
        if (StringUtils.isNotBlank(type)) {
            where.append(" AND exception_type = ? ");
            args.add(type.trim());
        }
        if (StringUtils.isNotBlank(channel)) {
            where.append(" AND channel = ? ");
            args.add(channel.trim());
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM t_ops_exception " + where, args.toArray(), Long.class);
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add(size);
        dataArgs.add(offset);
        List<Map<String, Object>> items = jdbcTemplate.query(
                "SELECT id, exception_type, channel, error_code, case_id, plan_id, step_id, severity, "
                        + "message, status, cluster_key, created_at "
                        + "FROM t_ops_exception "
                        + where
                        + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                dataArgs.toArray(),
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("exceptionType", rs.getString("exception_type"));
                    row.put("channel", rs.getString("channel"));
                    row.put("errorCode", rs.getString("error_code"));
                    row.put("caseId", rs.getObject("case_id"));
                    row.put("planId", rs.getObject("plan_id"));
                    row.put("stepId", rs.getObject("step_id"));
                    row.put("severity", rs.getString("severity"));
                    row.put("message", rs.getString("message"));
                    row.put("status", rs.getString("status"));
                    row.put("clusterKey", rs.getString("cluster_key"));
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

    @PostMapping("/{id}/ack")
    public Map<String, Object> ack(@PathVariable Long id, HttpServletRequest request) {
        String operator = currentUser(request);
        jdbcTemplate.update(
                "UPDATE t_ops_exception SET status = 'ACK', operator = ?, updated_at = NOW() WHERE id = ?",
                operator,
                id);
        return ApiResponse.success("ACK");
    }

    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(
            @PathVariable Long id,
            @Valid @RequestBody ExceptionResolveRequest body,
            HttpServletRequest request) {
        String operator = currentUser(request);
        String action = body.getAction();
        String note = body.getNote() == null ? "" : body.getNote();
        jdbcTemplate.update(
                "UPDATE t_ops_exception SET status = 'RESOLVED', operator = ?, resolved_at = NOW(), "
                        + "detail_json = JSON_OBJECT('action', ?, 'note', ?), updated_at = NOW() WHERE id = ?",
                operator,
                action,
                note,
                id);
        return ApiResponse.success("RESOLVED");
    }

    private static String currentUser(HttpServletRequest request) {
        Object u = request.getSession(false) == null ? null : request.getSession(false).getAttribute("ADMIN_USER");
        if (u instanceof Map) {
            Object name = ((Map<?, ?>) u).get("username");
            if (name != null) {
                return String.valueOf(name);
            }
        }
        return "system";
    }
}

