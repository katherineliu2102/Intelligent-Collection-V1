package com.collection.admin.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    private static final String DELIVERED_RESULTS = "('DELIVERED','SENT','ACCEPTED')";
    private static final String FAILED_RESULTS = "('FAILED','REJECTED','BOUNCED')";
    private static final String ATTEMPTED_RESULTS =
            "('DELIVERED','SENT','ACCEPTED','FAILED','REJECTED','BOUNCED')";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbcTemplate;

    public DashboardController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/outreach/realtime")
    public Map<String, Object> outreachRealtime(@RequestParam(defaultValue = "30") int days) {
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
        Map<String, Object> summary = buildSummary(byChannel, byResult);
        Map<String, Object> exceptions = queryExceptions();
        Map<String, Object> plans = queryPlanSummary();

        data.put("summary", summary);
        data.put("byChannel", byChannel);
        data.put("byStage", byStage);
        data.put("byResult", byResult);
        data.put("exceptions", exceptions);
        data.put("plans", plans);
        return ApiResponse.success(data);
    }

    /** 催收组合概况（§5.1.1 回收 / §5.1.2 迁徙，读 t_ai_collection 投影，热层）。 */
    @GetMapping("/portfolio")
    public Map<String, Object> portfolio() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "realtime");
        data.put("asOf", LocalDateTime.now().format(TS_FMT));
        data.put("portfolio", queryPortfolioSummary());
        data.put("byStage", queryPortfolioByStage());
        data.put("byStatus", queryPortfolioByStatus());
        data.put("touchConversion", queryTouchConversion());
        data.put("todayInbox", queryTodayInbox());
        return ApiResponse.success(data);
    }

    /** Aging 分布（§5.1.2 队列迁徙：在催案件按 DPD 4 桶切分，时点型无窗口）。 */
    @GetMapping("/aging")
    public Map<String, Object> aging() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "snapshot");
        data.put("asOf", LocalDateTime.now().format(TS_FMT));
        data.put("buckets", queryAgingBuckets());
        return ApiResponse.success(data);
    }

    /** 渠道 × Stage 交叉矩阵（§3.5 触达执行：行=渠道、列=Stage，格=Attempted；含 AI_CALL， 与渠道独立表正交，只做交叉视图不替代独立表）。 */
    @GetMapping("/matrix")
    public Map<String, Object> matrix(@RequestParam(defaultValue = "7") int days) {
        int windowDays = Math.max(1, Math.min(90, days));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "realtime");
        data.put("windowDays", windowDays);
        data.put("rows", queryMatrix(windowDays));
        return ApiResponse.success(data);
    }

    private List<Map<String, Object>> queryMatrix(int windowDays) {
        return jdbcTemplate.query(
                "SELECT t.channel AS channel, COALESCE(p.stage,'UNKNOWN') AS stage, "
                        + "COUNT(*) AS records, "
                        + "SUM(t.result IN "
                        + ATTEMPTED_RESULTS
                        + ") AS attempted, "
                        + "SUM(t.result IN "
                        + DELIVERED_RESULTS
                        + ") AS delivered "
                        + "FROM t_contact_timeline t "
                        + "LEFT JOIN t_contact_plan p ON p.id = t.plan_id "
                        + "WHERE t.direction = 'OUT' "
                        + "AND t.created_at >= DATE_SUB(NOW(), INTERVAL ? DAY) "
                        + "GROUP BY t.channel, COALESCE(p.stage,'UNKNOWN') "
                        + "ORDER BY t.channel, stage",
                new Object[] {windowDays},
                this::portfolioRow);
    }

    /** 按日触达序列（设计原则 4「趋势 > 快照」：TrendSpark 迷你趋势的数据源）。 */
    @GetMapping("/daily")
    public Map<String, Object> daily(@RequestParam(defaultValue = "7") int days) {
        int windowDays = Math.max(1, Math.min(90, days));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "realtime");
        data.put("windowDays", windowDays);
        data.put("series", queryDaily(windowDays));
        return ApiResponse.success(data);
    }

    private List<Map<String, Object>> queryDaily(int windowDays) {
        return jdbcTemplate.query(
                "SELECT DATE(created_at) AS day, COUNT(*) AS records, "
                        + "SUM(result IN "
                        + ATTEMPTED_RESULTS
                        + ") AS attempted, "
                        + "SUM(result IN "
                        + DELIVERED_RESULTS
                        + ") AS delivered "
                        + "FROM t_contact_timeline "
                        + "WHERE direction = 'OUT' "
                        + "AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY) "
                        + "GROUP BY DATE(created_at) ORDER BY day",
                new Object[] {windowDays},
                this::portfolioRow);
    }

    private List<Map<String, Object>> queryAgingBuckets() {
        return jdbcTemplate.query(
                "SELECT CASE "
                        + "WHEN dpd <= 30 THEN '0-30' "
                        + "WHEN dpd <= 60 THEN '31-60' "
                        + "WHEN dpd <= 90 THEN '61-90' "
                        + "ELSE '91+' END AS bucket, "
                        + "COUNT(*) AS cases, "
                        + "COALESCE(SUM(total_outstanding),0) AS outstanding "
                        + "FROM t_ai_collection "
                        + "WHERE collection_status = 'IN_COLLECTION' "
                        + "GROUP BY bucket "
                        + "ORDER BY FIELD(bucket,'0-30','31-60','61-90','91+')",
                this::portfolioRow);
    }

    /** AI Call 分区（§5.1.4：业务结果首屏 + 渠道卫生层，读 t_ai_call_session）。 */
    @GetMapping("/aicall/realtime")
    public Map<String, Object> aicallRealtime(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "false") boolean includeSynthetic) {
        int windowDays = Math.max(1, Math.min(90, days));
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(windowDays);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "realtime");
        data.put("windowDays", windowDays);
        data.put("from", from.format(TS_FMT));
        data.put("to", to.format(TS_FMT));
        data.put("funnel", queryAiCallFunnel(windowDays, includeSynthetic));
        data.put("labelDistribution", queryAiCallLabels(windowDays, includeSynthetic));
        data.put("sipDistribution", queryAiCallSip(windowDays, includeSynthetic));
        return ApiResponse.success(data);
    }

    /** 接通明细（§5.1.4 业务结果下钻：was_answered=1 逐会话，时长由 ended_at-answered_at 派生）。 */
    @GetMapping("/aicall/detail")
    public Map<String, Object> aicallDetail(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "false") boolean includeSynthetic) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(100, pageSize));
        int windowDays = Math.max(1, Math.min(90, days));
        int offset = (p - 1) * size;
        String synth = includeSynthetic ? "" : " AND is_synthetic = 0";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "realtime");
        data.put("windowDays", windowDays);
        data.put("page", p);
        data.put("pageSize", size);
        data.put("total", countAiCallDetail(windowDays, synth));
        data.put("items", queryAiCallDetail(windowDays, size, offset, synth));
        return ApiResponse.success(data);
    }

    private long countAiCallDetail(int windowDays, String synth) {
        Long n =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM t_ai_call_session "
                                + "WHERE event='session.completed' AND was_answered = 1"
                                + synth
                                + " AND received_at >= DATE_SUB(NOW(), INTERVAL ? DAY)",
                        Long.class,
                        windowDays);
        return n == null ? 0L : n;
    }

    private List<Map<String, Object>> queryAiCallDetail(
            int windowDays, int limit, int offset, String synth) {
        return jdbcTemplate.query(
                "SELECT session_id, case_id, answered_at, ended_at, "
                        + "TIMESTAMPDIFF(SECOND, answered_at, ended_at) AS durationSec, "
                        + "result_label, summary, stage_snapshot, dpd_snapshot "
                        + "FROM t_ai_call_session "
                        + "WHERE event='session.completed' AND was_answered = 1"
                        + synth
                        + " AND received_at >= DATE_SUB(NOW(), INTERVAL ? DAY)"
                        + " ORDER BY COALESCE(answered_at, received_at) DESC LIMIT ? OFFSET ?",
                new Object[] {windowDays, limit, offset},
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("caseId", rs.getObject("case_id"));
                    row.put("answeredAt", rs.getObject("answered_at"));
                    row.put("endedAt", rs.getObject("ended_at"));
                    row.put("durationSec", rs.getObject("durationSec"));
                    row.put("resultLabel", rs.getString("result_label"));
                    row.put("summary", rs.getString("summary"));
                    row.put("stageSnapshot", rs.getString("stage_snapshot"));
                    row.put("dpdSnapshot", rs.getObject("dpd_snapshot"));
                    return row;
                });
    }

    private Map<String, Object> queryAiCallFunnel(int windowDays, boolean includeSynthetic) {
        String synth = includeSynthetic ? "" : " AND is_synthetic = 0";
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT COUNT(*) AS dispatched, "
                                + "COALESCE(SUM(was_answered=1),0) AS answered, "
                                + "COALESCE(SUM(was_ai_connected=1),0) AS aiConnected, "
                                + "COALESCE(SUM(was_answered=1 AND line_reason IN ('VOICEMAIL','CALL_SCREENING')),0) AS invalid "
                                + "FROM t_ai_call_session "
                                + "WHERE event='session.completed' AND received_at >= DATE_SUB(NOW(), INTERVAL ? DAY)"
                                + synth,
                        new Object[] {windowDays},
                        this::portfolioRow);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private List<Map<String, Object>> queryAiCallLabels(int windowDays, boolean includeSynthetic) {
        String synth = includeSynthetic ? "" : " AND is_synthetic = 0";
        return jdbcTemplate.query(
                "SELECT COALESCE(result_label,'未分类') AS label, COUNT(*) AS count "
                        + "FROM t_ai_call_session "
                        + "WHERE event='session.completed' AND received_at >= DATE_SUB(NOW(), INTERVAL ? DAY)"
                        + synth
                        + " GROUP BY COALESCE(result_label,'未分类') ORDER BY count DESC",
                new Object[] {windowDays},
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String label = rs.getString("label");
                    row.put("label", label);
                    row.put("count", rs.getLong("count"));
                    row.put("bucket", labelBucket(label));
                    return row;
                });
    }

    private List<Map<String, Object>> queryAiCallSip(int windowDays, boolean includeSynthetic) {
        String synth = includeSynthetic ? "" : " AND is_synthetic = 0";
        return jdbcTemplate.query(
                "SELECT COALESCE(sip_code,'N/A') AS sipCode, COUNT(*) AS count "
                        + "FROM t_ai_call_session "
                        + "WHERE event='session.completed' AND received_at >= DATE_SUB(NOW(), INTERVAL ? DAY)"
                        + synth
                        + " GROUP BY COALESCE(sip_code,'N/A') ORDER BY count DESC",
                new Object[] {windowDays},
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sipCode", rs.getString("sipCode"));
                    row.put("count", rs.getLong("count"));
                    return row;
                });
    }

    /** result_label 三桶分类：业务结果 / 合规风险 / 未分类（开放标签集，未知标签不丢弃）。 */
    private static String labelBucket(String label) {
        if ("promise_to_pay".equals(label)
                || "follow_up_required".equals(label)
                || "vague_commitment".equals(label)) {
            return "业务结果";
        }
        if ("dispute".equals(label)) {
            return "合规风险";
        }
        return "未分类";
    }

    /** 风险信号（§5.1.5：高敏标签清单 + 断联，读 t_ai_call_session）。 */
    @GetMapping("/risk")
    public Map<String, Object> risk() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "realtime");
        data.put("highSensitivity", queryHighSensitivityLabels());
        data.put("disconnect", queryDisconnect());
        data.put("hanging", queryHanging());
        data.put("guardBlocked", queryGuardBlocked());
        return ApiResponse.success(data);
    }

    /** 高敏标签清单（初始集合 {dispute}，配置化增补，§5.1.7）。 */
    private List<Map<String, Object>> queryHighSensitivityLabels() {
        return jdbcTemplate.query(
                "SELECT session_id, case_id, result_label, summary, received_at "
                        + "FROM t_ai_call_session "
                        + "WHERE result_label = 'dispute' "
                        + "ORDER BY received_at DESC LIMIT 20",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("caseId", rs.getObject("case_id"));
                    row.put("resultLabel", rs.getString("result_label"));
                    row.put("summary", rs.getString("summary"));
                    row.put("receivedAt", rs.getObject("received_at"));
                    return row;
                });
    }

    /** 断联信号：INVALID_NUMBER 计数（号码失效，勿再拨，§5.1.5）。 */
    private Map<String, Object> queryDisconnect() {
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT COUNT(*) AS invalidNumber "
                                + "FROM t_ai_call_session WHERE final_failure_reason = 'INVALID_NUMBER'",
                        this::portfolioRow);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    /** 悬挂会话：AI_CALL 步骤 EXECUTING 且超 15 分钟未收口（A3 看板面，§5.1.5）。 */
    private List<Map<String, Object>> queryHanging() {
        return jdbcTemplate.query(
                "SELECT id, plan_id, step_order, executed_at, dispatched_at "
                        + "FROM t_contact_plan_step "
                        + "WHERE channel_type = 'AI_CALL' AND status = 'EXECUTING' "
                        + "AND (dispatched_at IS NOT NULL AND dispatched_at < DATE_SUB(NOW(), INTERVAL 15 MINUTE) "
                        + "OR executed_at IS NOT NULL AND executed_at < DATE_SUB(NOW(), INTERVAL 15 MINUTE)) "
                        + "ORDER BY COALESCE(dispatched_at, executed_at) ASC LIMIT 20",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("stepId", rs.getObject("id"));
                    row.put("planId", rs.getObject("plan_id"));
                    row.put("stepOrder", rs.getObject("step_order"));
                    row.put("executedAt", rs.getObject("executed_at"));
                    row.put("dispatchedAt", rs.getObject("dispatched_at"));
                    return row;
                });
    }

    /** Guard 拦截：合规/频次守卫跳过的 AI 步数（SKIPPED + COMPLIANCE_BLOCKED，§5.1.5）。 */
    private Map<String, Object> queryGuardBlocked() {
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT COUNT(*) AS guardBlocked "
                                + "FROM t_contact_plan_step "
                                + "WHERE channel_type = 'AI_CALL' AND status = 'SKIPPED' AND result = 'COMPLIANCE_BLOCKED'",
                        this::portfolioRow);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private Map<String, Object> queryPortfolioSummary() {
        LocalDateTime todayStart = LocalDate.now(ZoneId.of("Asia/Manila")).atStartOfDay();
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT COUNT(*) AS totalCases, "
                                + "COALESCE(SUM(collection_status='IN_COLLECTION'),0) AS inCollection, "
                                + "COALESCE(SUM(collection_status='SETTLED'),0) AS settled, "
                                + "COALESCE(SUM(collection_status='CEASED'),0) AS ceased, "
                                + "COALESCE(SUM(CASE WHEN collection_status='IN_COLLECTION' THEN total_outstanding ELSE 0 END),0) AS inCollectionOutstanding, "
                                + "COALESCE(SUM(total_outstanding),0) AS totalOutstanding, "
                                + "COALESCE(SUM(CASE WHEN settled_at >= ? THEN last_paid_amount ELSE 0 END),0) AS todayRecovered, "
                                + "COALESCE(SUM(CASE WHEN settled_at >= ? THEN 1 ELSE 0 END),0) AS todaySettled "
                                + "FROM t_ai_collection",
                        new Object[] {todayStart, todayStart},
                        this::portfolioRow);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    /** 触达→还款转化（48h 归因窗口，§5.1.7：近 7 天触达案件中 48h 内还款的占比）。 */
    private Map<String, Object> queryTouchConversion() {
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT COUNT(DISTINCT t.case_id) AS touched, "
                                + "COUNT(DISTINCT CASE WHEN c.settled_at IS NOT NULL "
                                + "AND c.settled_at >= t.first_touch "
                                + "AND c.settled_at <= DATE_ADD(t.first_touch, INTERVAL 48 HOUR) "
                                + "THEN t.case_id END) AS converted48h "
                                + "FROM (SELECT case_id, MIN(created_at) AS first_touch "
                                + "FROM t_contact_timeline WHERE direction='OUT' "
                                + "AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) "
                                + "GROUP BY case_id) t "
                                + "LEFT JOIN t_ai_collection c ON c.case_id = t.case_id",
                        this::portfolioRow);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private List<Map<String, Object>> queryPortfolioByStage() {
        return jdbcTemplate.query(
                "SELECT COALESCE(stage,'N/A') AS stage, COUNT(*) AS cases, "
                        + "COALESCE(SUM(total_outstanding),0) AS outstanding "
                        + "FROM t_ai_collection GROUP BY COALESCE(stage,'N/A') "
                        + "ORDER BY MIN(FIELD(COALESCE(stage,'N/A'),'S0','S1','S2','S3','S4','N/A'))",
                this::portfolioRow);
    }

    private List<Map<String, Object>> queryPortfolioByStatus() {
        return jdbcTemplate.query(
                "SELECT collection_status AS status, COUNT(*) AS cases, "
                        + "COALESCE(SUM(total_outstanding),0) AS outstanding "
                        + "FROM t_ai_collection GROUP BY collection_status ORDER BY cases DESC",
                this::portfolioRow);
    }

    /** 今日新增 inbox（caseEvent 消息，PHT 口径）。「在催案件」是存量，这是今日流量，两者不可对账。 */
    private Map<String, Object> queryTodayInbox() {
        LocalDateTime todayStart = LocalDate.now(ZoneId.of("Asia/Manila")).atStartOfDay();
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        "SELECT COUNT(*) AS todayInbox "
                                + "FROM t_ai_collection_inbox "
                                + "WHERE message_type = 'caseEvent' AND created_at >= ?",
                        new Object[] {todayStart},
                        this::portfolioRow);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private Map<String, Object> portfolioRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private List<Map<String, Object>> queryByChannel(int windowDays) {
        return jdbcTemplate.query(
                metricSelect("channel", "channel", "")
                        + "FROM t_contact_timeline "
                        + "WHERE direction = 'OUT' AND channel <> 'AI_CALL' "
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
                        + "WHERE t.direction = 'OUT' AND t.channel <> 'AI_CALL' "
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
                        + "WHERE direction = 'OUT' AND channel <> 'AI_CALL' "
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

    private static String metricSelect(String dimExpr, String dimAlias, String resultPrefix) {
        return "SELECT " + dimExpr + " AS " + dimAlias + ", " + metricAggregates(resultPrefix);
    }

    /** attempted = 实际发起发送（含失败）；skipped/other 不计入送达率分母。 */
    private static String metricAggregates(String resultPrefix) {
        String r = resultPrefix + "result";
        return "COUNT(*) AS records, "
                + "SUM("
                + r
                + " IN "
                + ATTEMPTED_RESULTS
                + ") AS attempted, "
                + "SUM("
                + r
                + " IN "
                + DELIVERED_RESULTS
                + ") AS delivered, "
                + "SUM("
                + r
                + " IN "
                + FAILED_RESULTS
                + ") AS failed, "
                + "SUM("
                + r
                + " = 'SKIPPED') AS skipped, "
                + "SUM(CASE WHEN "
                + r
                + " IS NULL OR ("
                + r
                + " NOT IN ('DELIVERED','SENT','ACCEPTED','FAILED','REJECTED','BOUNCED','SKIPPED')) "
                + "THEN 1 ELSE 0 END) AS other ";
    }

    private Map<String, Object> metricRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        long records = rs.getLong("records");
        long attempted = rs.getLong("attempted");
        long delivered = rs.getLong("delivered");
        long failed = rs.getLong("failed");
        long skipped = rs.getLong("skipped");
        long other = rs.getLong("other");
        Map<String, Object> row = new LinkedHashMap<>();
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String name = meta.getColumnLabel(i);
            if (isMetricColumn(name)) {
                continue;
            }
            row.put(name, rs.getObject(i));
        }
        putMetrics(row, records, attempted, delivered, failed, skipped, other);
        return row;
    }

    private static boolean isMetricColumn(String name) {
        return "records".equals(name)
                || "attempted".equals(name)
                || "sent".equals(name)
                || "delivered".equals(name)
                || "failed".equals(name)
                || "skipped".equals(name)
                || "other".equals(name)
                || "deliveryRate".equals(name);
    }

    private static void putMetrics(
            Map<String, Object> row,
            long records,
            long attempted,
            long delivered,
            long failed,
            long skipped,
            long other) {
        row.put("records", records);
        row.put("attempted", attempted);
        row.put("sent", attempted); // 兼容旧字段名
        row.put("delivered", delivered);
        row.put("failed", failed);
        row.put("skipped", skipped);
        row.put("other", other);
        row.put("deliveryRate", attempted == 0 ? 0.0 : roundRate(delivered, attempted));
    }

    private Map<String, Object> buildSummary(
            List<Map<String, Object>> byChannel, List<Map<String, Object>> byResult) {
        long records = 0;
        long attempted = 0;
        long delivered = 0;
        long failed = 0;
        long skipped = 0;
        long other = 0;
        for (Map<String, Object> row : byChannel) {
            records += toLong(row.get("records"));
            attempted += toLong(row.get("attempted"));
            delivered += toLong(row.get("delivered"));
            failed += toLong(row.get("failed"));
            skipped += toLong(row.get("skipped"));
            other += toLong(row.get("other"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRecords", records);
        summary.put("totalAttempted", attempted);
        summary.put("totalSent", attempted); // 兼容
        summary.put("delivered", delivered);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("other", other);
        summary.put("deliveryRate", attempted == 0 ? 0.0 : roundRate(delivered, attempted));
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

    private static double roundRate(long delivered, long attempted) {
        return Math.round(1000.0 * delivered / attempted) / 1000.0;
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
