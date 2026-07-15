package com.collection.admin.web;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 数据分析看板（热层实时聚合，设计文档 §5.1 / 附录 C DashboardController）。 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private static final String DELIVERED_RESULTS =
            "('DELIVERED','SENT','ACCEPTED')";
    private static final String FAILED_RESULTS = "('FAILED','REJECTED','BOUNCED')";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbcTemplate;

    public DashboardController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/outreach/realtime")
    public Map<String, Object> outreachRealtime(
            @RequestParam(defaultValue = "7") int days) {
        int windowDays = Math.max(1, Math.min(90, days));
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(windowDays);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "realtime");
        data.put("windowDays", windowDays);
        data.put("from", from.format(TS_FMT));
        data.put("to", to.format(TS_FMT));

        List<Map<String, Object>> byChannel = queryByChannel(windowDays);
        List<Map<String, Object>> byStage = queryByStage(windowDays);
        List<Map<String, Object>> byResult = queryByResult(windowDays);
        List<Map<String, Object>> byTemplate = queryByTemplate(windowDays);
        Map<String, Object> summary = buildSummary(byChannel, byResult);
        Map<String, Object> exceptions = queryExceptions();
        Map<String, Object> plans = queryPlanSummary();

        data.put("summary", summary);
        data.put("byChannel", byChannel);
        data.put("byStage", byStage);
        data.put("byResult", byResult);
        data.put("byTemplate", byTemplate);
        data.put("exceptions", exceptions);
        data.put("plans", plans);
        return ApiResponse.success(data);
    }

    private List<Map<String, Object>> queryByChannel(int windowDays) {
        return jdbcTemplate.query(
                metricSelect("channel", "channel", "")
                        + "FROM t_contact_timeline "
                        + "WHERE direction = 'OUT' "
                        + "AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY) "
                        + "GROUP BY channel ORDER BY records DESC",
                new Object[] {windowDays},
                this::metricRow);
    }

    private List<Map<String, Object>> queryByStage(int windowDays) {
        return jdbcTemplate.query(
                metricSelect("COALESCE(p.stage, 'UNKNOWN')", "stage", "t.")
                        + "FROM t_contact_timeline t "
                        + "LEFT JOIN t_contact_plan p ON p.id = t.plan_id "
                        + "WHERE t.direction = 'OUT' "
                        + "AND t.created_at >= DATE_SUB(NOW(), INTERVAL ? DAY) "
                        + "GROUP BY COALESCE(p.stage, 'UNKNOWN') "
                        + "ORDER BY MIN(FIELD(COALESCE(p.stage, 'UNKNOWN'), "
                        + "'S0','S1','S2','S3','S4','UNKNOWN'))",
                new Object[] {windowDays},
                this::metricRow);
    }

    private List<Map<String, Object>> queryByResult(int windowDays) {
        return jdbcTemplate.query(
                "SELECT COALESCE(result, 'UNKNOWN') AS result, COUNT(*) AS count "
                        + "FROM t_contact_timeline "
                        + "WHERE direction = 'OUT' "
                        + "AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY) "
                        + "GROUP BY COALESCE(result, 'UNKNOWN') "
                        + "ORDER BY count DESC",
                new Object[] {windowDays},
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("result", rs.getString("result"));
                    row.put("count", rs.getLong("count"));
                    return row;
                });
    }

    private List<Map<String, Object>> queryByTemplate(int windowDays) {
        return jdbcTemplate.query(
                "SELECT t.template_id AS templateId, "
                        + "COALESCE(st.script_slot, CONCAT('tpl-', t.template_id)) AS scriptSlot, "
                        + "st.channel AS templateChannel, "
                        + "COUNT(*) AS records, "
                        + "SUM(t.result <> 'SKIPPED') AS sent, "
                        + "SUM(t.result IN " + DELIVERED_RESULTS + ") AS delivered, "
                        + "SUM(t.result IN " + FAILED_RESULTS + ") AS failed, "
                        + "SUM(t.result = 'SKIPPED') AS skipped "
                        + "FROM t_contact_timeline t "
                        + "LEFT JOIN t_script_template st ON st.id = t.template_id "
                        + "WHERE t.direction = 'OUT' "
                        + "AND t.created_at >= DATE_SUB(NOW(), INTERVAL ? DAY) "
                        + "GROUP BY t.template_id, st.script_slot, st.channel "
                        + "ORDER BY records DESC LIMIT 20",
                new Object[] {windowDays},
                (rs, rowNum) -> {
                    Map<String, Object> row = metricRow(rs, rowNum);
                    row.put("templateId", rs.getObject("templateId"));
                    row.put("templateChannel", rs.getString("templateChannel"));
                    return row;
                });
    }

    private static String metricSelect(String dimExpr, String dimAlias, String resultPrefix) {
        String resultCol = resultPrefix + "result";
        return "SELECT " + dimExpr + " AS " + dimAlias + ", "
                + "COUNT(*) AS records, "
                + "SUM(" + resultCol + " <> 'SKIPPED') AS sent, "
                + "SUM(" + resultCol + " IN " + DELIVERED_RESULTS + ") AS delivered, "
                + "SUM(" + resultCol + " IN " + FAILED_RESULTS + ") AS failed, "
                + "SUM(" + resultCol + " = 'SKIPPED') AS skipped ";
    }

    private Map<String, Object> metricRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        long records = rs.getLong("records");
        long sent = rs.getLong("sent");
        long delivered = rs.getLong("delivered");
        long failed = rs.getLong("failed");
        long skipped = rs.getLong("skipped");
        Map<String, Object> row = new LinkedHashMap<>();
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String name = meta.getColumnLabel(i);
            if ("records".equals(name)
                    || "sent".equals(name)
                    || "delivered".equals(name)
                    || "failed".equals(name)
                    || "skipped".equals(name)
                    || "deliveryRate".equals(name)) {
                continue;
            }
            row.put(name, rs.getObject(i));
        }
        row.put("records", records);
        row.put("sent", sent);
        row.put("delivered", delivered);
        row.put("failed", failed);
        row.put("skipped", skipped);
        row.put("deliveryRate", sent == 0 ? 0.0 : roundRate(delivered, sent));
        return row;
    }

    private Map<String, Object> buildSummary(
            List<Map<String, Object>> byChannel, List<Map<String, Object>> byResult) {
        long records = 0;
        long sent = 0;
        long delivered = 0;
        long failed = 0;
        long skipped = 0;
        for (Map<String, Object> row : byChannel) {
            records += toLong(row.get("records"));
            sent += toLong(row.get("sent"));
            delivered += toLong(row.get("delivered"));
            failed += toLong(row.get("failed"));
            skipped += toLong(row.get("skipped"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRecords", records);
        summary.put("totalSent", sent);
        summary.put("delivered", delivered);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("deliveryRate", sent == 0 ? 0.0 : roundRate(delivered, sent));
        summary.put("resultBreakdown", byResult);
        return summary;
    }

    private Map<String, Object> queryExceptions() {
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT status, COUNT(*) AS cnt FROM t_ops_exception GROUP BY status",
                        (rs, rowNum) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("status", rs.getString("status"));
                            row.put("count", rs.getLong("cnt"));
                            return row;
                        });
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : rows) {
            String status = String.valueOf(row.get("status")).toLowerCase();
            long cnt = toLong(row.get("count"));
            out.put(status, cnt);
            total += cnt;
        }
        out.put("total", total);
        out.put("scope", "all_time");
        out.putIfAbsent("open", 0L);
        out.putIfAbsent("ack", 0L);
        out.putIfAbsent("resolved", 0L);
        out.putIfAbsent("ignored", 0L);
        return out;
    }

    private Map<String, Object> queryPlanSummary() {
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT status, COUNT(*) AS cnt FROM t_contact_plan GROUP BY status",
                        (rs, rowNum) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("status", rs.getString("status"));
                            row.put("count", rs.getLong("cnt"));
                            return row;
                        });
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : rows) {
            out.put(String.valueOf(row.get("status")), row.get("count"));
            total += toLong(row.get("count"));
        }
        out.put("total", total);
        out.put("scope", "all_time");
        return out;
    }

    private static double roundRate(long delivered, long sent) {
        return Math.round(1000.0 * delivered / sent) / 1000.0;
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return Long.parseLong(String.valueOf(v));
    }
}
