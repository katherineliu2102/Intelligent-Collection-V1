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

/**
 * 案件检索（Phase 1 P0）。
 *
 * <p>案件目录 SSOT 是 ingestion 投影表 {@code t_ai_collection}（设计文档 §5.3）。旧库 {@code t_collection} 不再是新系统的案件
 * 目录：真实 {@code caseEvent} 只落投影表，读旧库会出现「引擎已在催、后台搜不到」。 计划/步骤/时间线仍读 {@code t_contact_plan*}。
 *
 * <p>姓名（{@code borrower_name}）按 §5.3.1 隐私口径不进列表，故不在此查询。
 */
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
            @RequestParam(required = false) String collectionStatus,
            @RequestParam(required = false) String planStatus,
            @RequestParam(required = false) Boolean frozen,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page must be >= 1")
                    int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "pageSize must be >= 1")
                    int pageSize) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(100, pageSize));
        int offset = (p - 1) * size;

        // 一案可有多条历史计划，按 case_id 直接 JOIN 会放大行数，此前用 ANY_VALUE 收敛，
        // 结果是列表里的计划状态随机取自任意一条历史计划。改为只挂最新一条，行数 1:1 且结果确定。
        String from =
                " FROM t_ai_collection c "
                        + "LEFT JOIN t_contact_plan p ON p.id = "
                        + "(SELECT MAX(p2.id) FROM t_contact_plan p2 WHERE p2.case_id = c.case_id) ";
        String frozenExists =
                "EXISTS (SELECT 1 FROM t_admin_case_freeze f "
                        + "WHERE f.case_id = c.case_id AND f.status = 'FROZEN')";
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();

        if (caseId != null) {
            where.append(" AND c.case_id = ? ");
            args.add(caseId);
        }
        if (userId != null) {
            where.append(" AND c.user_id = ? ");
            args.add(userId);
        }
        if (StringUtils.isNotBlank(stage)) {
            where.append(" AND c.stage = ? ");
            args.add(stage.trim());
        }
        if (StringUtils.isNotBlank(collectionStatus)) {
            where.append(" AND c.collection_status = ? ");
            args.add(collectionStatus.trim());
        }
        if (StringUtils.isNotBlank(planStatus)) {
            where.append(" AND p.status = ? ");
            args.add(planStatus.trim());
        }
        if (frozen != null) {
            where.append(frozen.booleanValue() ? " AND " : " AND NOT ").append(frozenExists);
        }

        String countSql = "SELECT COUNT(*) " + from + where;
        Long total = jdbcTemplate.queryForObject(countSql, args.toArray(), Long.class);

        String dataSql =
                "SELECT c.case_id AS caseId, c.user_id AS userId, c.dpd AS dpd, c.stage AS stage, "
                        + "c.collection_status AS collectionStatus, c.product AS product, "
                        + "p.status AS planStatus, "
                        + frozenExists
                        + " AS frozen, "
                        + "c.borrower_phone AS phone, c.borrower_email AS email "
                        + from
                        + where
                        + " ORDER BY c.case_id DESC LIMIT ? OFFSET ?";
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
                            row.put("collectionStatus", rs.getString("collectionStatus"));
                            row.put("product", rs.getString("product"));
                            row.put("planStatus", rs.getString("planStatus"));
                            row.put("frozen", rs.getInt("frozen") == 1);
                            row.put("phone", PiiMask.phone(rs.getString("phone")));
                            row.put("email", PiiMask.email(rs.getString("email")));
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
