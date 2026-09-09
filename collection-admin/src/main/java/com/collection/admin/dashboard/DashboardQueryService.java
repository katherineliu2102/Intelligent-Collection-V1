package com.collection.admin.dashboard;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 管理后台看板热层查询（设计文档 §5.1）。所有「今日」边界用 PHT 00:00；禁止跨渠道合并触达率。 */
@Service
public class DashboardQueryService {

    static final String DELIVERED_RESULTS = "('DELIVERED','SENT','ACCEPTED')";
    static final String FAILED_RESULTS = "('FAILED','REJECTED','BOUNCED')";
    static final String ATTEMPTED_RESULTS =
            "('DELIVERED','SENT','ACCEPTED','FAILED','REJECTED','BOUNCED')";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int CASE_ID_CAP = 50;

    private final JdbcTemplate jdbc;

    public DashboardQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> todayExecution() {
        LocalDateTime from = DashboardClock.todayStart();
        LocalDateTime now = DashboardClock.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "on_demand");
        data.put("phtDate", DashboardClock.today().toString());
        data.put("from", from.format(TS_FMT));
        data.put("asOf", now.format(TS_FMT));
        data.put("slots", querySlots(from));
        data.put("outreach", queryTodayOutreach(from));
        data.put("answered", queryTodayAnswered(from));
        data.put("roll", queryRollAssertions(from));
        data.put("risk", queryTodayRisk(from));
        return data;
    }

    public Map<String, Object> outreachRealtime(int days) {
        int windowDays = clampDays(days);
        LocalDateTime to = DashboardClock.now();
        LocalDateTime from = to.minusDays(windowDays);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "on_demand");
        data.put("windowDays", windowDays);
        data.put("from", from.format(TS_FMT));
        data.put("to", to.format(TS_FMT));
        List<Map<String, Object>> byChannel = queryByChannel(windowDays);
        List<Map<String, Object>> byResult = queryByResult(windowDays);
        data.put("summary", buildSummary(byChannel));
        data.put("byChannel", byChannel);
        data.put("byResult", byResult);
        data.put("exceptions", queryExceptions());
        data.put("plans", queryPlanSummary());
        return data;
    }

    public Map<String, Object> portfolio() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "on_demand");
        data.put("asOf", DashboardClock.now().format(TS_FMT));
        data.put("portfolio", queryPortfolioSummary());
        data.put("byStage", queryPortfolioByStage());
        data.put("byStatus", queryPortfolioByStatus());
        Map<String, Object> touch = queryTouchConversion();
        touch.put(
                "settledInWindow",
                countLong(
                        "SELECT COUNT(*) FROM t_ai_collection "
                                + "WHERE settled_at IS NOT NULL AND settled_at >= DATE_SUB(?, INTERVAL 7 DAY)",
                        DashboardClock.now()));
        data.put("touchConversion", touch);
        data.put("todayInbox", queryTodayInbox());
        data.put("plans", queryPlanSummary());
        return data;
    }

    public Map<String, Object> aging() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "snapshot");
        data.put("asOf", DashboardClock.now().format(TS_FMT));
        data.put("buckets", queryAgingBuckets());
        return data;
    }

    public Map<String, Object> matrix(int days) {
        int windowDays = clampDays(days);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "on_demand");
        data.put("windowDays", windowDays);
        data.put("rows", queryMatrix(windowDays));
        return data;
    }

    public Map<String, Object> dailyByChannel(int days) {
        int windowDays = clampDays(days);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "on_demand");
        data.put("windowDays", windowDays);
        data.put("series", queryDailyByChannel(windowDays));
        data.put("aiAnswerRate", queryAiAnswerRateByDay(windowDays));
        return data;
    }

    public Map<String, Object> aicallRealtime(int days, boolean includeSynthetic) {
        int windowDays = clampDays(days);
        LocalDateTime to = DashboardClock.now();
        LocalDateTime from = to.minusDays(windowDays);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "on_demand");
        data.put("windowDays", windowDays);
        data.put("from", from.format(TS_FMT));
        data.put("to", to.format(TS_FMT));
        data.put("funnel", queryAiCallFunnel(windowDays, includeSynthetic));
        data.put("labelDistribution", queryAiCallLabels(windowDays, includeSynthetic));
        data.put("sipDistribution", queryAiCallSip(windowDays, includeSynthetic));
        data.put("failureStructure", queryAiCallFailureStructure(windowDays, includeSynthetic));
        data.put("waves", queryAiCallWaves(windowDays, includeSynthetic));
        return data;
    }

    public Map<String, Object> aicallDetail(
            int page, int pageSize, int days, boolean includeSynthetic) {
        return aicallDetail(page, pageSize, days, includeSynthetic, null, null, null);
    }

    public Map<String, Object> aicallDetail(
            int page,
            int pageSize,
            int days,
            boolean includeSynthetic,
            String resultLabel,
            String stage,
            String waveKey) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(100, pageSize));
        int windowDays = clampDays(days);
        int offset = (p - 1) * size;
        String synth = includeSynthetic ? "" : " AND s.is_synthetic = 0";
        DetailFilter filter = DetailFilter.of(resultLabel, stage, waveKey);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "on_demand");
        data.put("windowDays", windowDays);
        data.put("page", p);
        data.put("pageSize", size);
        data.put("total", countAiCallDetail(windowDays, synth, filter));
        data.put("items", queryAiCallDetail(windowDays, size, offset, synth, filter));
        data.put("facets", queryAiCallDetailFacets(windowDays, synth));
        return data;
    }

    public Map<String, Object> risk() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "HOT");
        data.put("freshness", "on_demand");
        data.put("highSensitivity", queryHighSensitivityLabels());
        data.put("disconnect", queryDisconnect());
        data.put("hanging", queryHanging());
        data.put("guardBlocked", queryGuardBlocked(null));
        return data;
    }

    private List<Map<String, Object>> querySlots(LocalDateTime from) {
        List<Map<String, Object>> slots = new ArrayList<>();
        slots.add(smsSlot(from));
        slots.add(pushSlot(from, "08:00", true));
        slots.add(aiSlot(from, "09:15", "0915"));
        slots.add(pushSlot(from, "12:00", false));
        slots.add(emailSlot(from));
        slots.add(aiSlot(from, "14:30", "1430"));
        return slots;
    }

    private Map<String, Object> smsSlot(LocalDateTime from) {
        Map<String, Object> slot = channelSlotShell("08:00", "SMS");
        Map<String, Object> metrics =
                oneRow(
                        "SELECT COUNT(*) AS records, "
                                + "SUM(result IN "
                                + ATTEMPTED_RESULTS
                                + ") AS attempted, "
                                + "SUM(result IN "
                                + DELIVERED_RESULTS
                                + ") AS delivered, "
                                + "SUM(result = 'FAILED' OR result = 'REJECTED' OR result = 'BOUNCED') AS failed, "
                                + "SUM(result = 'SKIPPED') AS skipped "
                                + "FROM t_contact_timeline "
                                + "WHERE direction='OUT' AND channel='SMS' AND created_at >= ?",
                        from);
        slot.putAll(nvlMetrics(metrics));
        slot.put(
                "stageBreakdown",
                jdbc.query(
                        "SELECT COALESCE(p.stage,'UNKNOWN') AS stage, "
                                + "SUM(t.result IN "
                                + DELIVERED_RESULTS
                                + ") AS delivered "
                                + "FROM t_contact_timeline t "
                                + "LEFT JOIN t_contact_plan p ON p.id = t.plan_id "
                                + "WHERE t.direction='OUT' AND t.channel='SMS' AND t.created_at >= ? "
                                + "GROUP BY COALESCE(p.stage,'UNKNOWN') "
                                + "ORDER BY MIN(FIELD(COALESCE(p.stage,'UNKNOWN'),'S0','S1','S2','S3','S4','UNKNOWN'))",
                        new Object[] {from},
                        this::genericRow));
        return slot;
    }

    private Map<String, Object> pushSlot(LocalDateTime from, String label, boolean morning) {
        Map<String, Object> slot = channelSlotShell(label, "PUSH");
        String hourPred = morning ? "HOUR(created_at) < 12" : "HOUR(created_at) >= 12";
        Map<String, Object> metrics =
                oneRow(
                        "SELECT COUNT(*) AS records, "
                                + "SUM(result IN "
                                + ATTEMPTED_RESULTS
                                + ") AS attempted, "
                                + "SUM(result IN "
                                + DELIVERED_RESULTS
                                + ") AS delivered, "
                                + "SUM(result IN "
                                + FAILED_RESULTS
                                + ") AS failed, "
                                + "SUM(result = 'SKIPPED') AS skipped "
                                + "FROM t_contact_timeline "
                                + "WHERE direction='OUT' AND channel='PUSH' AND created_at >= ? AND "
                                + hourPred,
                        from);
        slot.putAll(nvlMetrics(metrics));
        slot.put(
                "slotBreakdown",
                jdbc.query(
                        "SELECT COALESCE(script_slot,'UNKNOWN') AS scriptSlot, "
                                + "SUM(result IN "
                                + DELIVERED_RESULTS
                                + ") AS delivered "
                                + "FROM t_contact_timeline "
                                + "WHERE direction='OUT' AND channel='PUSH' AND created_at >= ? AND "
                                + hourPred
                                + " GROUP BY COALESCE(script_slot,'UNKNOWN') ORDER BY delivered DESC",
                        new Object[] {from},
                        this::genericRow));
        return slot;
    }

    private Map<String, Object> emailSlot(LocalDateTime from) {
        Map<String, Object> slot = channelSlotShell("14:00", "EMAIL");
        Map<String, Object> metrics =
                oneRow(
                        "SELECT COUNT(*) AS records, "
                                + "SUM(result IN "
                                + ATTEMPTED_RESULTS
                                + ") AS attempted, "
                                + "SUM(result IN "
                                + DELIVERED_RESULTS
                                + ") AS delivered, "
                                + "SUM(result IN "
                                + FAILED_RESULTS
                                + ") AS failed, "
                                + "SUM(result = 'SKIPPED') AS skipped "
                                + "FROM t_contact_timeline "
                                + "WHERE direction='OUT' AND channel='EMAIL' AND created_at >= ?",
                        from);
        slot.putAll(nvlMetrics(metrics));
        slot.put("zeroSendNormal", toLong(metrics.get("records")) == 0);
        slot.put(
                "slotBreakdown",
                jdbc.query(
                        "SELECT COALESCE(script_slot,'UNKNOWN') AS scriptSlot, "
                                + "SUM(result IN "
                                + DELIVERED_RESULTS
                                + ") AS delivered, "
                                + "SUM(result = 'SKIPPED') AS skipped "
                                + "FROM t_contact_timeline "
                                + "WHERE direction='OUT' AND channel='EMAIL' AND created_at >= ? "
                                + "GROUP BY COALESCE(script_slot,'UNKNOWN') ORDER BY delivered DESC",
                        new Object[] {from},
                        this::genericRow));
        return slot;
    }

    private Map<String, Object> aiSlot(LocalDateTime from, String label, String hhmm) {
        Map<String, Object> slot = channelSlotShell(label, "AI_CALL");
        String like = "%-" + hhmm + "-%";
        List<Map<String, Object>> sessions =
                jdbc.query(
                        "SELECT batch_id, was_answered, was_ringing, was_ai_connected, line_reason, "
                                + "final_failure_reason, sip_code, result_label "
                                + "FROM t_ai_call_session "
                                + "WHERE event='session.completed' AND is_synthetic=0 "
                                + "AND received_at >= ? AND batch_id LIKE ?",
                        new Object[] {from, like},
                        this::genericRow);
        long completed = sessions.size();
        long ringing = 0;
        long answered = 0;
        long aiConnected = 0;
        long snr = 0;
        long busy = 0;
        long noAnswer = 0;
        long failed = 0;
        long effective = 0;
        String batchId = null;
        Map<String, Long> answeredLabels = new LinkedHashMap<>();
        for (Map<String, Object> s : sessions) {
            if (batchId == null) {
                batchId = str(s.get("batch_id"));
            }
            boolean ans = toLong(s.get("was_answered")) == 1;
            String reason = str(s.get("final_failure_reason"));
            String line = str(s.get("line_reason"));
            if (toLong(s.get("was_ringing")) == 1) {
                ringing++;
            }
            String bucket = AiCallFailureClassifier.bucket(ans, reason, line);
            if ("ANSWERED".equals(bucket)) {
                answered++;
                if (toLong(s.get("was_ai_connected")) == 1 && "NORMAL".equals(line)) {
                    aiConnected++;
                }
                String labelVal = str(s.get("result_label"));
                if (labelVal != null && !"incomplete".equals(labelVal)) {
                    effective++;
                }
                String tag = (labelVal == null || labelVal.isEmpty()) ? "未回传" : labelVal;
                answeredLabels.merge(tag, 1L, Long::sum);
            } else if ("SNR".equals(bucket)) {
                snr++;
            } else if ("BUSY".equals(bucket)) {
                busy++;
            } else if ("NO_ANSWER".equals(bucket)) {
                noAnswer++;
            } else if ("FAILED".equals(bucket)) {
                failed++;
            }
        }
        slot.put("batchId", batchId);
        slot.put("waveKey", WaveKey.fromBatchId(batchId));
        slot.put("completed", completed);
        slot.put("ringing", ringing);
        slot.put("answered", answered);
        slot.put("snr", snr);
        slot.put("aiConnected", aiConnected);
        slot.put("effective", effective);
        slot.put("busy", busy);
        slot.put("noAnswer", noAnswer);
        slot.put("failed", failed);
        slot.put("answerRate", rate(answered, completed));
        slot.put("failedRate", rate(failed, completed));
        slot.put("missing", completed == 0);
        slot.put("answeredLabels", countRows(answeredLabels, "label"));
        return slot;
    }

    private Map<String, Object> queryTodayOutreach(LocalDateTime from) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
                "byChannel",
                jdbc.query(
                        metricSelect("channel", "channel", "")
                                + "FROM t_contact_timeline "
                                + "WHERE direction='OUT' AND channel IN ('SMS','PUSH','EMAIL') "
                                + "AND created_at >= ? GROUP BY channel ORDER BY channel",
                        new Object[] {from},
                        this::metricRow));
        out.put(
                "skipReasons",
                jdbc.query(
                        "SELECT t.channel AS channel, "
                                + "CASE "
                                + "WHEN s.result='COMPLIANCE_BLOCKED' THEN 'GUARD' "
                                + "WHEN s.result='SKIPPED' AND EXISTS ("
                                + "  SELECT 1 FROM t_ai_call_session a "
                                + "  WHERE a.case_id = t.case_id AND a.event='session.completed' "
                                + "  AND a.is_synthetic=0 AND a.was_answered=1 "
                                + "  AND a.line_reason NOT IN ('VOICEMAIL','CALL_SCREENING') "
                                + "  AND a.received_at >= ? AND a.received_at < t.created_at"
                                + ") THEN 'CONNECT_AND_STOP' "
                                + "WHEN s.result='SKIPPED' THEN 'OTHER_SKIPPED' "
                                + "ELSE COALESCE(s.result, t.result, 'UNKNOWN') END AS reason, "
                                + "COUNT(*) AS count "
                                + "FROM t_contact_timeline t "
                                + "LEFT JOIN t_contact_plan_step s ON s.id = t.step_id "
                                + "WHERE t.direction='OUT' AND t.result='SKIPPED' AND t.created_at >= ? "
                                + "AND t.channel IN ('SMS','PUSH','EMAIL','AI_CALL') "
                                + "GROUP BY t.channel, reason ORDER BY t.channel, count DESC",
                        new Object[] {from, from},
                        this::genericRow));
        return out;
    }

    private List<Map<String, Object>> queryTodayAnswered(LocalDateTime from) {
        return jdbc.query(
                answeredSessionSelect()
                        + "WHERE s.event='session.completed' AND s.is_synthetic=0 "
                        + "AND "
                        + liveAnsweredPred("s")
                        + " AND s.received_at >= ? "
                        + "ORDER BY COALESCE(s.answered_at, s.received_at) DESC LIMIT 100",
                new Object[] {from},
                this::mapAnsweredSession);
    }

    private Map<String, Object> queryRollAssertions(LocalDateTime from) {
        Map<String, Object> roll = new LinkedHashMap<>();
        List<Long> routed =
                jdbc.query(
                        "SELECT DISTINCT case_id FROM t_contact_plan "
                                + "WHERE cancel_reason='ROUTED_TO_LEGACY' "
                                + "AND COALESCE(completed_at, updated_at) >= ?",
                        new Object[] {from},
                        (rs, n) -> rs.getLong("case_id"));
        roll.put("routedToLegacy", caseList(routed));
        List<Long> after = new ArrayList<>();
        if (!routed.isEmpty()) {
            after =
                    jdbc.query(
                            "SELECT DISTINCT t.case_id FROM t_contact_timeline t "
                                    + "JOIN t_contact_plan p ON p.case_id = t.case_id "
                                    + "AND p.cancel_reason='ROUTED_TO_LEGACY' "
                                    + "AND COALESCE(p.completed_at, p.updated_at) >= ? "
                                    + "WHERE t.direction='OUT' AND t.result IN "
                                    + DELIVERED_RESULTS
                                    + " AND t.created_at > COALESCE(p.completed_at, p.updated_at)",
                            new Object[] {from},
                            (rs, n) -> rs.getLong("case_id"));
        }
        Map<String, Object> afterRow = caseList(after);
        afterRow.put("pass", after.isEmpty());
        roll.put("deliveredAfterRoute", afterRow);
        List<Long> upgrades =
                jdbc.query(
                        "SELECT DISTINCT case_id FROM t_contact_plan "
                                + "WHERE cancel_reason='STAGE_UPGRADE' "
                                + "AND COALESCE(completed_at, updated_at) >= ?",
                        new Object[] {from},
                        (rs, n) -> rs.getLong("case_id"));
        roll.put("stageUpgrade", caseList(upgrades));
        Map<String, Object> inbox =
                oneRow(
                        "SELECT "
                                + "SUM(message_type='caseEvent') AS caseEvent, "
                                + "SUM(message_type='repaymentEvent') AS repaymentEvent "
                                + "FROM t_ai_collection_inbox WHERE created_at >= ?",
                        from);
        roll.put("inbox", inbox);
        roll.put(
                "newPlans",
                countLong("SELECT COUNT(*) FROM t_contact_plan WHERE created_at >= ?", from));
        Map<String, Object> recon =
                oneRow(
                        "SELECT reconcile_date AS reconcileDate, owner_case_count AS ownerCaseCount, "
                                + "completed_at AS completedAt FROM t_ai_owner_reconcile WHERE reconcile_date = ?",
                        DashboardClock.today());
        roll.put("ownerReconcile", recon);
        Map<String, Object> cancels = new LinkedHashMap<>();
        List<Map<String, Object>> cancelRows =
                jdbc.query(
                        "SELECT cancel_reason AS reason, COUNT(*) AS count "
                                + "FROM t_contact_plan WHERE cancel_reason IS NOT NULL "
                                + "AND COALESCE(completed_at, updated_at) >= ? "
                                + "GROUP BY cancel_reason ORDER BY count DESC",
                        new Object[] {from},
                        this::genericRow);
        for (Map<String, Object> row : cancelRows) {
            cancels.put(str(row.get("reason")), row.get("count"));
        }
        roll.put("cancels", cancels);
        return roll;
    }

    private Map<String, Object> queryTodayRisk(LocalDateTime from) {
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("hanging", queryHanging());
        risk.put("guardBlocked", queryGuardBlocked(from));
        risk.put(
                "guardByChannel",
                jdbc.query(
                        "SELECT channel_type AS channel, COUNT(*) AS count "
                                + "FROM t_contact_plan_step "
                                + "WHERE status='SKIPPED' AND result='COMPLIANCE_BLOCKED' "
                                + "AND COALESCE(completed_at, updated_at) >= ? "
                                + "GROUP BY channel_type",
                        new Object[] {from},
                        this::genericRow));
        risk.put(
                "executingAi",
                countLong(
                        "SELECT COUNT(*) FROM t_contact_plan_step "
                                + "WHERE channel_type='AI_CALL' AND status='EXECUTING'"));
        risk.put(
                "pendingDueAi",
                countLong(
                        "SELECT COUNT(*) FROM t_contact_plan_step "
                                + "WHERE channel_type='AI_CALL' AND status='PENDING' "
                                + "AND trigger_time IS NOT NULL AND trigger_time <= ?",
                        DashboardClock.now()));
        risk.put("exceptions", queryExceptions());
        risk.put("disconnect", queryDisconnect());
        risk.put("highSensitivity", queryHighSensitivityLabels());
        return risk;
    }

    private List<Map<String, Object>> queryMatrix(int windowDays) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(
                jdbc.query(
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
                                + "WHERE t.direction='OUT' AND t.channel IN ('SMS','PUSH','EMAIL') "
                                + "AND t.created_at >= DATE_SUB(NOW(), INTERVAL "
                                + windowDays
                                + " DAY) "
                                + "GROUP BY t.channel, COALESCE(p.stage,'UNKNOWN')",
                        this::genericRow));
        rows.addAll(
                jdbc.query(
                        "SELECT 'AI_CALL' AS channel, "
                                + "COALESCE(s.stage_snapshot, p.stage, c.stage, 'UNKNOWN') AS stage, "
                                + "COUNT(*) AS records, COUNT(*) AS attempted, "
                                + "SUM(s.was_answered=1) AS delivered "
                                + "FROM t_ai_call_session s "
                                + "LEFT JOIN t_contact_plan p ON p.id = s.plan_id "
                                + "LEFT JOIN t_ai_collection c ON c.case_id = s.case_id "
                                + "WHERE s.event='session.completed' AND s.is_synthetic=0 "
                                + "AND s.received_at >= DATE_SUB(NOW(), INTERVAL "
                                + windowDays
                                + " DAY) "
                                + "GROUP BY COALESCE(s.stage_snapshot, p.stage, c.stage, 'UNKNOWN')",
                        this::genericRow));
        return rows;
    }

    private List<Map<String, Object>> queryDailyByChannel(int windowDays) {
        return jdbc.query(
                "SELECT DATE(created_at) AS day, channel, COUNT(*) AS records, "
                        + "SUM(result IN "
                        + ATTEMPTED_RESULTS
                        + ") AS attempted, "
                        + "SUM(result IN "
                        + DELIVERED_RESULTS
                        + ") AS delivered "
                        + "FROM t_contact_timeline "
                        + "WHERE direction='OUT' AND channel IN ('SMS','PUSH','EMAIL') "
                        + "AND created_at >= DATE_SUB(NOW(), INTERVAL "
                        + windowDays
                        + " DAY) "
                        + "GROUP BY DATE(created_at), channel ORDER BY day, channel",
                this::genericRow);
    }

    private List<Map<String, Object>> queryAiAnswerRateByDay(int windowDays) {
        return jdbc.query(
                "SELECT DATE(received_at) AS day, COUNT(*) AS completed, "
                        + "SUM(was_answered=1 AND (line_reason IS NULL "
                        + "OR line_reason NOT IN ('VOICEMAIL','CALL_SCREENING'))) AS answered "
                        + "FROM t_ai_call_session "
                        + "WHERE event='session.completed' AND is_synthetic=0 "
                        + "AND received_at >= DATE_SUB(NOW(), INTERVAL "
                        + windowDays
                        + " DAY) "
                        + "GROUP BY DATE(received_at) ORDER BY day",
                this::genericRow);
    }

    private List<Map<String, Object>> queryAiCallWaves(
            int windowDays, boolean includeSynthetic) {
        String synth = includeSynthetic ? "" : " AND is_synthetic = 0";
        List<Map<String, Object>> batches =
                jdbc.query(
                        "SELECT batch_id, was_answered, was_ringing, was_ai_connected, line_reason, "
                                + "final_failure_reason, sip_code "
                                + "FROM t_ai_call_session WHERE event='session.completed' "
                                + synth
                                + " AND received_at >= DATE_SUB(NOW(), INTERVAL "
                                + windowDays
                                + " DAY)",
                        this::genericRow);
        Map<String, Map<String, Object>> byWave = new LinkedHashMap<>();
        for (Map<String, Object> s : batches) {
            String wave = WaveKey.fromBatchId(str(s.get("batch_id")));
            if (wave == null) {
                wave = "UNKNOWN";
            }
            Map<String, Object> agg =
                    byWave.computeIfAbsent(
                            wave,
                            k -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("waveKey", k);
                                m.put("slot", WaveKey.slotHhmm(k));
                                m.put("completed", 0L);
                                m.put("answered", 0L);
                                m.put("snr", 0L);
                                m.put("busy", 0L);
                                m.put("noAnswer", 0L);
                                m.put("failed", 0L);
                                return m;
                            });
            boolean ans = toLong(s.get("was_answered")) == 1;
            String bucket =
                    AiCallFailureClassifier.bucket(
                            ans, str(s.get("final_failure_reason")), str(s.get("line_reason")));
            inc(agg, "completed");
            if ("ANSWERED".equals(bucket)) {
                inc(agg, "answered");
            } else if ("SNR".equals(bucket)) {
                inc(agg, "snr");
            } else if ("BUSY".equals(bucket)) {
                inc(agg, "busy");
            } else if ("NO_ANSWER".equals(bucket)) {
                inc(agg, "noAnswer");
            } else if ("FAILED".equals(bucket)) {
                inc(agg, "failed");
            }
        }
        List<Map<String, Object>> waves = new ArrayList<>(byWave.values());
        for (Map<String, Object> w : waves) {
            long completed = toLong(w.get("completed"));
            w.put("answerRate", rate(toLong(w.get("answered")), completed));
            w.put("failedRate", rate(toLong(w.get("failed")), completed));
        }
        waves.sort(
                (a, b) -> {
                    String ka = String.valueOf(a.get("waveKey"));
                    String kb = String.valueOf(b.get("waveKey"));
                    if ("UNKNOWN".equals(ka) && "UNKNOWN".equals(kb)) {
                        return 0;
                    }
                    if ("UNKNOWN".equals(ka)) {
                        return 1;
                    }
                    if ("UNKNOWN".equals(kb)) {
                        return -1;
                    }
                    return kb.compareTo(ka);
                });
        return waves;
    }

    private Map<String, Object> queryAiCallFunnel(int windowDays, boolean includeSynthetic) {
        String synth = includeSynthetic ? "" : " AND is_synthetic = 0";
        return nvl(
                oneRow(
                        "SELECT COUNT(*) AS dispatched, "
                                + "COALESCE(SUM(was_ringing=1),0) AS ringing, "
                                + "COALESCE(SUM(was_answered=1),0) AS answered, "
                                + "COALESCE(SUM(was_answered=1 AND (line_reason IS NULL "
                                + "OR line_reason NOT IN ('VOICEMAIL','CALL_SCREENING'))),0) AS liveAnswered, "
                                + "COALESCE(SUM(was_ai_connected=1 AND line_reason='NORMAL'),0) AS aiConnected, "
                                + "COALESCE(SUM(was_answered=1 AND line_reason IN ('VOICEMAIL','CALL_SCREENING')),0) AS invalid "
                                + "FROM t_ai_call_session "
                                + "WHERE event='session.completed' AND received_at >= DATE_SUB(NOW(), INTERVAL "
                                + windowDays
                                + " DAY)"
                                + synth));
    }

    private List<Map<String, Object>> queryAiCallFailureStructure(
            int windowDays, boolean includeSynthetic) {
        String synth = includeSynthetic ? "" : " AND is_synthetic = 0";
        return jdbc.query(
                "SELECT COALESCE(final_failure_reason,'UNKNOWN') AS reason, COUNT(*) AS count "
                        + "FROM t_ai_call_session "
                        + "WHERE event='session.completed' AND was_answered=0 "
                        + "AND COALESCE(final_failure_reason,'') NOT IN ('BUSY','NO_ANSWER') "
                        + synth
                        + " AND received_at >= DATE_SUB(NOW(), INTERVAL "
                        + windowDays
                        + " DAY) "
                        + "GROUP BY COALESCE(final_failure_reason,'UNKNOWN') ORDER BY count DESC",
                this::genericRow);
    }

    private List<Map<String, Object>> queryAiCallLabels(int windowDays, boolean includeSynthetic) {
        String synth = includeSynthetic ? "" : " AND is_synthetic = 0";
        return jdbc.query(
                "SELECT COALESCE(result_label,'未分类') AS label, COUNT(*) AS count "
                        + "FROM t_ai_call_session "
                        + "WHERE event='session.completed' AND "
                        + liveAnsweredPred(null)
                        + synth
                        + " AND received_at >= DATE_SUB(NOW(), INTERVAL "
                        + windowDays
                        + " DAY) "
                        + "GROUP BY COALESCE(result_label,'未分类') ORDER BY count DESC",
                (rs, n) -> {
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
        return jdbc.query(
                "SELECT COALESCE(sip_code,'N/A') AS sipCode, COUNT(*) AS count "
                        + "FROM t_ai_call_session "
                        + "WHERE event='session.completed' "
                        + synth
                        + " AND received_at >= DATE_SUB(NOW(), INTERVAL "
                        + windowDays
                        + " DAY) "
                        + "GROUP BY COALESCE(sip_code,'N/A') ORDER BY count DESC",
                this::genericRow);
    }

    private long countAiCallDetail(int windowDays, String synth, DetailFilter filter) {
        String sql =
                "SELECT COUNT(*) FROM t_ai_call_session s "
                        + "LEFT JOIN t_contact_plan p ON p.id = s.plan_id "
                        + "WHERE s.event='session.completed' AND "
                        + liveAnsweredPred("s")
                        + synth
                        + " AND s.received_at >= DATE_SUB(NOW(), INTERVAL "
                        + windowDays
                        + " DAY)"
                        + filter.sql;
        Long n =
                filter.args.length == 0
                        ? jdbc.queryForObject(sql, Long.class)
                        : jdbc.queryForObject(sql, filter.args, Long.class);
        return n == null ? 0L : n;
    }

    private List<Map<String, Object>> queryAiCallDetail(
            int windowDays, int limit, int offset, String synth, DetailFilter filter) {
        Object[] args = new Object[filter.args.length + 2];
        System.arraycopy(filter.args, 0, args, 0, filter.args.length);
        args[filter.args.length] = limit;
        args[filter.args.length + 1] = offset;
        return jdbc.query(
                answeredSessionSelect()
                        + "WHERE s.event='session.completed' AND "
                        + liveAnsweredPred("s")
                        + synth
                        + " AND s.received_at >= DATE_SUB(NOW(), INTERVAL "
                        + windowDays
                        + " DAY)"
                        + filter.sql
                        + " ORDER BY s.batch_id DESC, COALESCE(s.answered_at, s.received_at) DESC LIMIT ? OFFSET ?",
                args,
                this::mapAnsweredSession);
    }

    private Map<String, Object> queryAiCallDetailFacets(int windowDays, String synth) {
        List<Map<String, Object>> rows =
                jdbc.query(
                        "SELECT DISTINCT s.result_label AS resultLabel, "
                                + "COALESCE(s.stage_snapshot, p.stage) AS stageSnapshot, s.batch_id AS batchId "
                                + "FROM t_ai_call_session s "
                                + "LEFT JOIN t_contact_plan p ON p.id = s.plan_id "
                                + "WHERE s.event='session.completed' AND "
                                + liveAnsweredPred("s")
                                + synth
                                + " AND s.received_at >= DATE_SUB(NOW(), INTERVAL "
                                + windowDays
                                + " DAY)",
                        this::genericRow);
        LinkedHashMap<String, Boolean> labels = new LinkedHashMap<>();
        LinkedHashMap<String, Boolean> stages = new LinkedHashMap<>();
        LinkedHashMap<String, Boolean> waves = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String label = str(row.get("resultLabel"));
            labels.put(label == null ? "" : label, Boolean.TRUE);
            String stage = str(row.get("stageSnapshot"));
            stages.put(stage == null ? "" : stage, Boolean.TRUE);
            String wave = WaveKey.fromBatchId(str(row.get("batchId")));
            waves.put(wave == null ? "" : wave, Boolean.TRUE);
        }
        Map<String, Object> facets = new LinkedHashMap<>();
        facets.put("labels", new ArrayList<>(labels.keySet()));
        facets.put("stages", new ArrayList<>(stages.keySet()));
        facets.put("waves", new ArrayList<>(waves.keySet()));
        return facets;
    }

    private static String labelBucket(String label) {
        if ("promise_to_pay".equals(label)
                || "follow_up_required".equals(label)
                || "vague_commitment".equals(label)
                || "refused_to_pay".equals(label)
                || "refused_to_discuss".equals(label)) {
            return "业务结果";
        }
        if ("dispute".equals(label)) {
            return "合规风险";
        }
        return "未分类";
    }

    private List<Map<String, Object>> queryHighSensitivityLabels() {
        return jdbc.query(
                "SELECT session_id AS sessionId, case_id AS caseId, "
                        + "result_label AS resultLabel, summary, received_at AS receivedAt "
                        + "FROM t_ai_call_session WHERE result_label = 'dispute' "
                        + "ORDER BY received_at DESC LIMIT 20",
                this::genericRow);
    }

    private Map<String, Object> queryDisconnect() {
        return nvl(
                oneRow(
                        "SELECT COUNT(*) AS invalidNumber FROM t_ai_call_session "
                                + "WHERE final_failure_reason = 'INVALID_NUMBER'"));
    }

    private List<Map<String, Object>> queryHanging() {
        return jdbc.query(
                "SELECT s.id AS stepId, s.plan_id AS planId, s.step_order AS stepOrder, "
                        + "p.case_id AS caseId, s.executed_at AS executedAt, s.dispatched_at AS dispatchedAt "
                        + "FROM t_contact_plan_step s "
                        + "JOIN t_contact_plan p ON p.id = s.plan_id "
                        + "WHERE s.channel_type = 'AI_CALL' AND s.status = 'EXECUTING' "
                        + "AND s.dispatched_at IS NOT NULL "
                        + "AND s.dispatched_at < DATE_SUB(?, INTERVAL 15 MINUTE) "
                        + "AND NOT EXISTS (SELECT 1 FROM t_ai_call_session x "
                        + "  WHERE x.step_id = s.id AND x.event='session.completed') "
                        + "ORDER BY s.dispatched_at ASC LIMIT 20",
                new Object[] {DashboardClock.now()},
                this::genericRow);
    }

    private Map<String, Object> queryGuardBlocked(LocalDateTime from) {
        if (from == null) {
            return nvl(
                    oneRow(
                            "SELECT COUNT(*) AS guardBlocked FROM t_contact_plan_step "
                                    + "WHERE status='SKIPPED' AND result='COMPLIANCE_BLOCKED'"));
        }
        return nvl(
                oneRow(
                        "SELECT COUNT(*) AS guardBlocked FROM t_contact_plan_step "
                                + "WHERE status='SKIPPED' AND result='COMPLIANCE_BLOCKED' "
                                + "AND COALESCE(completed_at, updated_at) >= ?",
                        from));
    }

    private Map<String, Object> queryPortfolioSummary() {
        LocalDateTime todayStart = DashboardClock.todayStart();
        return nvl(
                oneRow(
                        "SELECT COUNT(*) AS totalCases, "
                                + "COALESCE(SUM(collection_status='IN_COLLECTION'),0) AS inCollection, "
                                + "COALESCE(SUM(collection_status='SETTLED'),0) AS settled, "
                                + "COALESCE(SUM(collection_status='CEASED'),0) AS ceased, "
                                + "COALESCE(SUM(CASE WHEN collection_status='IN_COLLECTION' THEN total_outstanding ELSE 0 END),0) AS inCollectionOutstanding, "
                                + "COALESCE(SUM(total_outstanding),0) AS totalOutstanding, "
                                + "COALESCE(SUM(CASE WHEN settled_at >= ? THEN last_paid_amount ELSE 0 END),0) AS todayRecovered, "
                                + "COALESCE(SUM(CASE WHEN settled_at >= ? THEN 1 ELSE 0 END),0) AS todaySettled "
                                + "FROM t_ai_collection",
                        todayStart,
                        todayStart));
    }

    private Map<String, Object> queryTouchConversion() {
        LocalDateTime now = DashboardClock.now();
        return nvl(
                oneRow(
                        "SELECT COUNT(DISTINCT t.case_id) AS touched, "
                                + "COUNT(DISTINCT CASE WHEN c.settled_at IS NOT NULL "
                                + "AND c.settled_at >= t.first_touch "
                                + "AND c.settled_at <= DATE_ADD(t.first_touch, INTERVAL 48 HOUR) "
                                + "THEN t.case_id END) AS converted48h "
                                + "FROM (SELECT case_id, MIN(ts) AS first_touch FROM ("
                                + "  SELECT case_id, created_at AS ts FROM t_contact_timeline "
                                + "  WHERE direction='OUT' "
                                + "  AND result IN ('DELIVERED','SENT','ACCEPTED','ANSWERED') "
                                + "  AND created_at >= DATE_SUB(?, INTERVAL 7 DAY) "
                                + "  UNION ALL "
                                + "  SELECT case_id, received_at AS ts FROM t_ai_call_session "
                                + "  WHERE event='session.completed' AND is_synthetic=0 AND was_answered=1 "
                                + "  AND received_at >= DATE_SUB(?, INTERVAL 7 DAY)"
                                + ") u GROUP BY case_id) t "
                                + "LEFT JOIN t_ai_collection c ON c.case_id = t.case_id",
                        now,
                        now));
    }

    private List<Map<String, Object>> queryPortfolioByStage() {
        return jdbc.query(
                "SELECT stage, COUNT(*) AS cases, "
                        + "COALESCE(SUM(total_outstanding),0) AS outstanding "
                        + "FROM t_ai_collection "
                        + "WHERE collection_status='IN_COLLECTION' "
                        + "AND stage IN ('S1','S2','S3','S4') "
                        + "GROUP BY stage "
                        + "ORDER BY MIN(FIELD(stage,'S1','S2','S3','S4'))",
                this::genericRow);
    }

    private List<Map<String, Object>> queryPortfolioByStatus() {
        return jdbc.query(
                "SELECT collection_status AS status, COUNT(*) AS cases, "
                        + "COALESCE(SUM(total_outstanding),0) AS outstanding "
                        + "FROM t_ai_collection GROUP BY collection_status ORDER BY cases DESC",
                this::genericRow);
    }

    private Map<String, Object> queryTodayInbox() {
        return nvl(
                oneRow(
                        "SELECT COUNT(*) AS todayInbox FROM t_ai_collection_inbox "
                                + "WHERE message_type = 'caseEvent' AND created_at >= ?",
                        DashboardClock.todayStart()));
    }

    private List<Map<String, Object>> queryAgingBuckets() {
        return jdbc.query(
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
                this::genericRow);
    }

    private List<Map<String, Object>> queryByChannel(int windowDays) {
        return jdbc.query(
                metricSelect("channel", "channel", "")
                        + "FROM t_contact_timeline "
                        + "WHERE direction = 'OUT' AND channel IN ('SMS','PUSH','EMAIL') "
                        + "AND created_at >= DATE_SUB(NOW(), INTERVAL "
                        + windowDays
                        + " DAY) "
                        + "GROUP BY channel ORDER BY records DESC",
                this::metricRow);
    }

    private List<Map<String, Object>> queryByResult(int windowDays) {
        return jdbc.query(
                "SELECT COALESCE(result, 'UNKNOWN') AS result, COUNT(*) AS count "
                        + "FROM t_contact_timeline "
                        + "WHERE direction = 'OUT' AND channel IN ('SMS','PUSH','EMAIL') "
                        + "AND created_at >= DATE_SUB(NOW(), INTERVAL "
                        + windowDays
                        + " DAY) "
                        + "GROUP BY COALESCE(result, 'UNKNOWN') ORDER BY count DESC",
                this::genericRow);
    }

    private static String metricSelect(String dimExpr, String dimAlias, String resultPrefix) {
        return "SELECT " + dimExpr + " AS " + dimAlias + ", " + metricAggregates(resultPrefix);
    }

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

    private Map<String, Object> metricRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String name = meta.getColumnLabel(i);
            if (isMetricColumn(name)) {
                continue;
            }
            row.put(name, rs.getObject(i));
        }
        putMetrics(
                row,
                rs.getLong("records"),
                rs.getLong("attempted"),
                rs.getLong("delivered"),
                rs.getLong("failed"),
                rs.getLong("skipped"),
                rs.getLong("other"));
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

    static void putMetrics(
            Map<String, Object> row,
            long records,
            long attempted,
            long delivered,
            long failed,
            long skipped,
            long other) {
        row.put("records", records);
        row.put("attempted", attempted);
        row.put("delivered", delivered);
        row.put("sent", delivered);
        row.put("failed", failed);
        row.put("skipped", skipped);
        row.put("other", other);
        row.put("deliveryRate", rate(delivered, attempted));
    }

    private Map<String, Object> buildSummary(List<Map<String, Object>> byChannel) {
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
        summary.put("delivered", delivered);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("other", other);
        // 计数可并列展示；送达率禁止跨渠道合并（原则 P1）
        summary.put("deliveryRate", null);
        return summary;
    }

    Map<String, Object> queryExceptions() {
        List<Map<String, Object>> rows =
                jdbc.query(
                        "SELECT status, COUNT(*) AS cnt FROM t_ops_exception GROUP BY status",
                        (rs, n) -> {
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
        out.putIfAbsent("open", 0L);
        out.putIfAbsent("ack", 0L);
        out.putIfAbsent("resolved", 0L);
        return out;
    }

    private Map<String, Object> queryPlanSummary() {
        List<Map<String, Object>> rows =
                jdbc.query(
                        "SELECT status, COUNT(*) AS cnt FROM t_contact_plan GROUP BY status",
                        (rs, n) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("status", rs.getString("status"));
                            row.put("count", rs.getLong("cnt"));
                            return row;
                        });
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        long active = 0;
        for (Map<String, Object> row : rows) {
            String status = String.valueOf(row.get("status"));
            long cnt = toLong(row.get("count"));
            out.put(status, cnt);
            total += cnt;
            if ("PENDING".equals(status)
                    || "STEP_SCHEDULED".equals(status)
                    || "STEP_EXECUTING".equals(status)
                    || "STEP_WAITING".equals(status)
                    || "EXECUTING".equals(status)) {
                active += cnt;
            }
        }
        out.put("total", total);
        out.put("active", active);
        return out;
    }

    private static Map<String, Object> channelSlotShell(String slot, String channel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slot", slot);
        m.put("channel", channel);
        m.put("pending", DashboardClock.now().toLocalTime().isBefore(LocalTime.parse(slot)));
        return m;
    }

    private static List<Map<String, Object>> countRows(Map<String, Long> counts, String nameKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(
                        e -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put(nameKey, e.getKey());
                            row.put("count", e.getValue());
                            out.add(row);
                        });
        return out;
    }

    static final class DetailFilter {
        final String sql;
        final Object[] args;

        private DetailFilter(String sql, Object[] args) {
            this.sql = sql;
            this.args = args;
        }

        static DetailFilter of(String resultLabel, String stage, String waveKey) {
            StringBuilder sql = new StringBuilder();
            List<Object> args = new ArrayList<>();
            if (resultLabel != null) {
                if (resultLabel.isEmpty()) {
                    sql.append(" AND (s.result_label IS NULL OR s.result_label='')");
                } else {
                    sql.append(" AND s.result_label=?");
                    args.add(resultLabel);
                }
            }
            if (stage != null) {
                if (stage.isEmpty()) {
                    sql.append(
                            " AND (COALESCE(s.stage_snapshot, p.stage) IS NULL"
                                    + " OR COALESCE(s.stage_snapshot, p.stage)='')");
                } else {
                    sql.append(" AND COALESCE(s.stage_snapshot, p.stage)=?");
                    args.add(stage);
                }
            }
            if (waveKey != null && waveKey.matches("\\d{8}-\\d{4}")) {
                sql.append(" AND s.batch_id LIKE ?");
                args.add("%" + waveKey + "%");
            }
            return new DetailFilter(sql.toString(), args.toArray());
        }
    }

    private static Map<String, Object> nvlMetrics(Map<String, Object> metrics) {
        Map<String, Object> m = new LinkedHashMap<>();
        long records = toLong(metrics.get("records"));
        long attempted = toLong(metrics.get("attempted"));
        long delivered = toLong(metrics.get("delivered"));
        m.put("records", records);
        m.put("attempted", attempted);
        m.put("delivered", delivered);
        m.put("failed", toLong(metrics.get("failed")));
        m.put("skipped", toLong(metrics.get("skipped")));
        m.put("deliveryRate", rate(delivered, attempted));
        return m;
    }

    private static Map<String, Object> caseList(List<Long> ids) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", ids.size());
        m.put("caseIds", ids.subList(0, Math.min(CASE_ID_CAP, ids.size())));
        return m;
    }

    /** 真人接通：线路接起且非信箱/筛选。缺 alias 时用于无表前缀 SQL。 */
    static String liveAnsweredPred(String alias) {
        String p = alias == null || alias.isEmpty() ? "" : alias + ".";
        return p
                + "was_answered=1 AND ("
                + p
                + "line_reason IS NULL OR "
                + p
                + "line_reason NOT IN ('VOICEMAIL','CALL_SCREENING'))";
    }

    private static String answeredSessionSelect() {
        return "SELECT s.session_id, s.case_id, s.batch_id, s.answered_at, s.ended_at, s.received_at, "
                + "TIMESTAMPDIFF(SECOND, s.answered_at, s.ended_at) AS durationSec, "
                + "s.result_label, s.summary, "
                + "COALESCE(s.stage_snapshot, p.stage) AS stage_snapshot, "
                + "s.dpd_snapshot AS dpd_snapshot, "
                + "s.caller_cli, s.line_reason "
                + "FROM t_ai_call_session s "
                + "LEFT JOIN t_contact_plan p ON p.id = s.plan_id ";
    }

    private Map<String, Object> mapAnsweredSession(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", rs.getString("session_id"));
        row.put("caseId", rs.getObject("case_id"));
        row.put("batchId", rs.getString("batch_id"));
        row.put("waveKey", WaveKey.fromBatchId(rs.getString("batch_id")));
        row.put("answeredAt", rs.getObject("answered_at"));
        row.put("endedAt", rs.getObject("ended_at"));
        row.put("receivedAt", rs.getObject("received_at"));
        row.put("durationSec", rs.getObject("durationSec"));
        row.put("resultLabel", rs.getString("result_label"));
        row.put("summary", rs.getString("summary"));
        row.put("stageSnapshot", rs.getString("stage_snapshot"));
        row.put("dpdSnapshot", rs.getObject("dpd_snapshot"));
        row.put("callerCli", rs.getString("caller_cli"));
        return row;
    }

    private Map<String, Object> oneRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.query(sql, args, this::genericRow);
        return rows.isEmpty() ? new LinkedHashMap<String, Object>() : rows.get(0);
    }

    private long countLong(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, args, Long.class);
        return n == null ? 0L : n;
    }

    private Map<String, Object> genericRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private static Map<String, Object> nvl(Map<String, Object> m) {
        return m == null ? new LinkedHashMap<String, Object>() : m;
    }

    private static Double rate(long num, long den) {
        if (den <= 0) {
            return null;
        }
        return Math.round(1000.0 * num / den) / 1000.0;
    }

    private static void inc(Map<String, Object> m, String key) {
        m.put(key, toLong(m.get(key)) + 1);
    }

    private static int clampDays(int days) {
        return Math.max(1, Math.min(90, days));
    }

    static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1L : 0L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return 0L;
        }
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
            return Boolean.parseBoolean(s) ? 1L : 0L;
        }
        return Long.parseLong(s);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
