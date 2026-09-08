package com.collection.admin.alert;

import com.collection.admin.dashboard.AiCallFailureClassifier;
import com.collection.admin.dashboard.DashboardClock;
import com.collection.admin.dashboard.WaveKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI Call CRITICAL A1–A3。仅 {@code collection.scheduler.enabled=true} 时扫描（§5.5.4）。
 *
 * <p>文案含波次、分子分母、SIP Top、悬挂 id；不含明文手机号。n&lt;20 不告。
 */
@Component
@ConditionalOnProperty(prefix = "collection.scheduler", name = "enabled", havingValue = "true")
public class AiCallAlertScanner {

    private static final Logger log = LoggerFactory.getLogger(AiCallAlertScanner.class);
    private static final int MIN_N = 20;
    static final double FAILED_RATE_THRESHOLD = 0.15;

    private final JdbcTemplate jdbc;
    private final AlertDedupRepository dedup;
    private final DingTalkAlertClient dingtalk;
    private final OpsExceptionWriter exceptions;

    public AiCallAlertScanner(
            JdbcTemplate jdbc,
            AlertDedupRepository dedup,
            DingTalkAlertClient dingtalk,
            OpsExceptionWriter exceptions) {
        this.jdbc = jdbc;
        this.dedup = dedup;
        this.dingtalk = dingtalk;
        this.exceptions = exceptions;
    }

    @Scheduled(cron = "0 * * * * *")
    public void scan() {
        LocalDateTime now = DashboardClock.now();
        LocalDateTime todayStart = DashboardClock.todayStart();
        LocalDate day = DashboardClock.today();
        try {
            scanA1(todayStart, day);
            scanA2(now, todayStart, day);
            scanA3(now, day);
        } catch (RuntimeException e) {
            log.error("[alert] scan failed", e);
        }
    }

    void scanA1(LocalDateTime todayStart, LocalDate day) {
        List<Map<String, Object>> sessions =
                jdbc.query(
                        "SELECT batch_id, was_answered, line_reason, final_failure_reason, sip_code "
                                + "FROM t_ai_call_session "
                                + "WHERE event='session.completed' AND is_synthetic=0 AND received_at >= ?",
                        (rs, n) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("batch_id", rs.getString("batch_id"));
                            row.put("was_answered", rs.getInt("was_answered"));
                            row.put("line_reason", rs.getString("line_reason"));
                            row.put("final_failure_reason", rs.getString("final_failure_reason"));
                            row.put("sip_code", rs.getString("sip_code"));
                            return row;
                        },
                        todayStart);
        Map<String, WaveAgg> bySlot = new LinkedHashMap<>();
        for (Map<String, Object> s : sessions) {
            String wave = WaveKey.fromBatchId(String.valueOf(s.get("batch_id")));
            String slot = WaveKey.slotHhmm(wave);
            if (slot == null) {
                continue;
            }
            WaveAgg agg = bySlot.computeIfAbsent(slot, k -> new WaveAgg(wave, k));
            agg.completed++;
            boolean ans = toLong(s.get("was_answered")) == 1;
            String reason = str(s.get("final_failure_reason"));
            String line = str(s.get("line_reason"));
            if (AiCallFailureClassifier.isFailed(ans, reason, line)) {
                agg.failed++;
                String sip = str(s.get("sip_code"));
                if (sip != null) {
                    agg.sip.merge(sip, 1L, Long::sum);
                }
            }
        }
        for (WaveAgg agg : bySlot.values()) {
            boolean fire = agg.completed >= MIN_N && agg.failedRate() > FAILED_RATE_THRESHOLD;
            if (!fire) {
                if (agg.completed >= MIN_N) {
                    dedup.markRecovered("A1", agg.slot, day);
                }
                continue;
            }
            dispatch(
                    "A1",
                    agg.slot,
                    day,
                    "A1 AI Call FAILED "
                            + agg.failed
                            + "/"
                            + agg.completed
                            + " ("
                            + pct(agg.failedRate())
                            + ") wave="
                            + agg.wave
                            + " slot="
                            + agg.slot
                            + " sipTop="
                            + agg.sipTop()
                            + " threshold=15% n>="
                            + MIN_N);
        }
    }

    void scanA2(LocalDateTime now, LocalDateTime todayStart, LocalDate day) {
        LocalDateTime dueBefore = now.minusMinutes(10);
        List<Map<String, Object>> overdue =
                jdbc.query(
                        "SELECT COALESCE(s.trigger_time, s.original_trigger_time) AS dueAt "
                                + "FROM t_contact_plan_step s "
                                + "WHERE s.channel_type='AI_CALL' AND s.status='PENDING' "
                                + "AND COALESCE(s.trigger_time, s.original_trigger_time) IS NOT NULL "
                                + "AND COALESCE(s.trigger_time, s.original_trigger_time) <= ? "
                                + "AND COALESCE(s.trigger_time, s.original_trigger_time) >= ?",
                        (rs, n) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            java.sql.Timestamp ts = rs.getTimestamp("dueAt");
                            if (ts != null) {
                                row.put("dueAt", ts.toLocalDateTime());
                            }
                            return row;
                        },
                        dueBefore,
                        todayStart);
        Map<String, Integer> overdueBySlot = new LinkedHashMap<>();
        for (Map<String, Object> row : overdue) {
            LocalDateTime due = (LocalDateTime) row.get("dueAt");
            if (due == null) {
                continue;
            }
            String slot = WaveKey.slotHhmmFromTrigger(due.getHour(), due.getMinute());
            if (slot == null) {
                continue;
            }
            overdueBySlot.merge(slot, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : overdueBySlot.entrySet()) {
            String slot = e.getKey();
            long sessions =
                    nvlLong(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM t_ai_call_session "
                                            + "WHERE event='session.completed' AND is_synthetic=0 "
                                            + "AND received_at >= ? AND batch_id LIKE ?",
                                    Long.class,
                                    todayStart,
                                    "%-" + slot + "-%"));
            if (sessions > 0) {
                dedup.markRecovered("A2", slot, day);
                continue;
            }
            dispatch(
                    "A2",
                    slot,
                    day,
                    "A2 leak: AI_CALL steps still PENDING "
                            + e.getValue()
                            + " after trigger+10m, slot="
                            + slot
                            + " sessions=0");
        }
    }

    void scanA3(LocalDateTime now, LocalDate day) {
        LocalDateTime hangBefore = now.minusMinutes(15);
        List<Map<String, Object>> hanging =
                jdbc.query(
                        "SELECT s.id AS stepId, s.plan_id AS planId, p.case_id AS caseId, "
                                + "s.dispatched_at AS dispatchedAt "
                                + "FROM t_contact_plan_step s "
                                + "JOIN t_contact_plan p ON p.id = s.plan_id "
                                + "WHERE s.channel_type='AI_CALL' AND s.status='EXECUTING' "
                                + "AND s.dispatched_at IS NOT NULL AND s.dispatched_at < ? "
                                + "AND NOT EXISTS (SELECT 1 FROM t_ai_call_session x "
                                + "  WHERE x.step_id=s.id AND x.event='session.completed')",
                        (rs, n) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("stepId", rs.getLong("stepId"));
                            row.put("planId", rs.getLong("planId"));
                            row.put("caseId", rs.getObject("caseId"));
                            row.put("dispatchedAt", rs.getTimestamp("dispatchedAt"));
                            return row;
                        },
                        hangBefore);
        if (hanging.isEmpty()) {
            dedup.markRecovered("A3", "hanging", day);
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> h : hanging) {
            Long stepId = (Long) h.get("stepId");
            Long planId = (Long) h.get("planId");
            Long caseId = h.get("caseId") == null ? null : toLong(h.get("caseId"));
            String cluster = "A3:AI_CALL:" + stepId;
            exceptions.upsertOpen(
                    "CALLBACK_TIMEOUT",
                    "AI_CALL",
                    "HANGING",
                    caseId,
                    planId,
                    stepId,
                    "CRITICAL",
                    "AI_CALL EXECUTING >15m without session stepId=" + stepId + " caseId=" + caseId,
                    cluster);
            ids.add(String.valueOf(stepId));
        }
        String shown = ids.size() > 20 ? ids.subList(0, 20) + "..." : ids.toString();
        dispatch(
                "A3",
                "hanging",
                day,
                "A3 hanging EXECUTING>15m without session count="
                        + hanging.size()
                        + " stepIds="
                        + shown);
    }

    private void dispatch(String alertId, String objectKey, LocalDate day, String text) {
        AlertDedupRepository.Decision d = dedup.beforeSend(alertId, objectKey, day);
        if (d == AlertDedupRepository.Decision.SKIP) {
            return;
        }
        if (d == AlertDedupRepository.Decision.SUPPRESS) {
            log.warn("[alert] suppressed {}: {}", alertId, text);
            return;
        }
        dingtalk.sendText(text);
    }

    private static String pct(double rate) {
        return Math.round(rate * 1000.0) / 10.0 + "%";
    }

    private static long nvlLong(Long n) {
        return n == null ? 0L : n;
    }

    private static long toLong(Object v) {
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
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
            return Boolean.parseBoolean(s) ? 1L : 0L;
        }
        return Long.parseLong(s);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    static final class WaveAgg {
        final String wave;
        final String slot;
        long completed;
        long failed;
        final Map<String, Long> sip = new LinkedHashMap<>();

        WaveAgg(String wave, String slot) {
            this.wave = wave;
            this.slot = slot;
        }

        double failedRate() {
            return completed == 0 ? 0 : (double) failed / completed;
        }

        String sipTop() {
            return sip.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .reduce((a, b) -> a + "," + b)
                    .orElse("-");
        }
    }
}
