package com.collection.admin.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.validation.constraints.Min;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 案件检索（Phase 1 P0）。 */
@Validated
@RestController
@RequestMapping("/cases")
public class CaseQueryController {

    private final JdbcTemplate jdbcTemplate;

    public CaseQueryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false) Long caseId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String planStatus,
            @RequestParam(required = false) Boolean frozen,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page must be >= 1")
                    int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "pageSize must be >= 1")
                    int pageSize) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(100, pageSize));
        int offset = (p - 1) * size;

        StringBuilder from =
                new StringBuilder(
                        " FROM t_collection c LEFT JOIN t_contact_plan p ON p.case_id = CAST(c.loan_id AS UNSIGNED) "
                                + "LEFT JOIN t_admin_case_freeze f ON f.case_id = CAST(c.loan_id AS UNSIGNED) ");
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();

        if (caseId != null) {
            where.append(" AND CAST(c.loan_id AS UNSIGNED) = ? ");
            args.add(caseId);
        }
        if (userId != null) {
            where.append(" AND CAST(c.user_id AS UNSIGNED) = ? ");
            args.add(userId);
        }
        if (StringUtils.isNotBlank(stage)) {
            where.append(" AND p.stage = ? ");
            args.add(stage.trim());
        }
        if (StringUtils.isNotBlank(planStatus)) {
            where.append(" AND p.status = ? ");
            args.add(planStatus.trim());
        }
        if (frozen != null) {
            if (frozen.booleanValue()) {
                where.append(" AND f.status = 'FROZEN' ");
            } else {
                where.append(" AND (f.status IS NULL OR f.status <> 'FROZEN') ");
            }
        }

        String countSql = "SELECT COUNT(DISTINCT c.loan_id) " + from + where;
        Long total = jdbcTemplate.queryForObject(countSql, args.toArray(), Long.class);

        String dataSql =
                "SELECT CAST(c.loan_id AS UNSIGNED) AS caseId, "
                        + "ANY_VALUE(CAST(c.user_id AS UNSIGNED)) AS userId, "
                        + "ANY_VALUE(c.overdue_days) AS dpd, "
                        + "ANY_VALUE(p.stage) AS stage, ANY_VALUE(p.status) AS planStatus, "
                        + "MAX(CASE WHEN f.status = 'FROZEN' THEN 1 ELSE 0 END) AS frozen, "
                        + "ANY_VALUE(c.phone) AS phone, ANY_VALUE(c.email) AS email "
                        + from
                        + where
                        + " GROUP BY c.loan_id "
                        + " ORDER BY CAST(c.loan_id AS UNSIGNED) DESC LIMIT ? OFFSET ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add(size);
        dataArgs.add(offset);

        List<Map<String, Object>> items =
                jdbcTemplate.query(
                        dataSql,
                        dataArgs.toArray(),
                        (rs, rowNum) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("caseId", rs.getLong("caseId"));
                            row.put("userId", rs.getLong("userId"));
                            row.put("dpd", rs.getInt("dpd"));
                            row.put("stage", rs.getString("stage"));
                            row.put("planStatus", rs.getString("planStatus"));
                            row.put("frozen", rs.getInt("frozen") == 1);
                            row.put("phone", maskPhone(rs.getString("phone")));
                            row.put("email", maskEmail(rs.getString("email")));
                            return row;
                        });

        Map<String, Object> pageData = new LinkedHashMap<>();
        pageData.put("items", items);
        pageData.put("page", p);
        pageData.put("pageSize", size);
        pageData.put("total", total == null ? 0 : total);
        return ApiResponse.success(pageData);
    }

    private static String maskPhone(String phone) {
        if (StringUtils.isBlank(phone) || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }

    private static String maskEmail(String email) {
        if (StringUtils.isBlank(email) || !email.contains("@")) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(at);
        }
        return email.substring(0, 1) + "***" + email.substring(at);
    }
}
