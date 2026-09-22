package com.collection.admin.alert;

import com.collection.admin.dashboard.DashboardClock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code t_alert_dedup}：同日同对象只发一次；连续 3 个日历日后抑制，恢复时清零（§5.5.4）。 */
@Repository
public class AlertDedupRepository {

    static final int SUPPRESS_AFTER_DAYS = 3;

    private final JdbcTemplate jdbc;

    public AlertDedupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code SEND} 应发告警；{@code SUPPRESS} 已满 3 日只打日志；{@code SKIP} 今日已发过 */
    public Decision beforeSend(String alertId, String objectKey, LocalDate day) {
        List<Map<String, Object>> today =
                jdbc.queryForList(
                        "SELECT status FROM t_alert_dedup "
                                + "WHERE alert_id=? AND object_key=? AND calendar_day=?",
                        alertId,
                        objectKey,
                        day);
        if (!today.isEmpty()) {
            return Decision.SKIP;
        }
        Integer consecutive = null;
        try {
            consecutive =
                    jdbc.queryForObject(
                            "SELECT consecutive_days FROM t_alert_dedup "
                                    + "WHERE alert_id=? AND object_key=? AND calendar_day=DATE_SUB(?, INTERVAL 1 DAY)",
                            Integer.class,
                            alertId,
                            objectKey,
                            day);
        } catch (EmptyResultDataAccessException ignored) {
            consecutive = null;
        }
        int next = consecutive == null ? 1 : consecutive + 1;
        if (consecutive != null && consecutive >= SUPPRESS_AFTER_DAYS) {
            insertQuiet(alertId, objectKey, day, "SUPPRESSED", consecutive);
            return Decision.SUPPRESS;
        }
        try {
            jdbc.update(
                    "INSERT INTO t_alert_dedup(alert_id, object_key, calendar_day, status, "
                            + "consecutive_days, last_sent_at) VALUES(?,?,?,'SENT',?,?)",
                    alertId,
                    objectKey,
                    day,
                    next,
                    DashboardClock.now());
        } catch (DuplicateKeyException e) {
            return Decision.SKIP;
        }
        return Decision.SEND;
    }

    public void markRecovered(String alertId, String objectKey, LocalDate day) {
        Integer last;
        try {
            last =
                    jdbc.queryForObject(
                            "SELECT consecutive_days FROM t_alert_dedup "
                                    + "WHERE alert_id=? AND object_key=? ORDER BY calendar_day DESC LIMIT 1",
                            Integer.class,
                            alertId,
                            objectKey);
        } catch (EmptyResultDataAccessException e) {
            return;
        }
        if (last == null || last == 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO t_alert_dedup(alert_id, object_key, calendar_day, status, "
                        + "consecutive_days, last_sent_at) VALUES(?,?,?,'RECOVERED',0,?) "
                        + "ON DUPLICATE KEY UPDATE status='RECOVERED', consecutive_days=0, last_sent_at=VALUES(last_sent_at)",
                alertId,
                objectKey,
                day,
                DashboardClock.now());
    }

    private void insertQuiet(
            String alertId, String objectKey, LocalDate day, String status, int consecutive) {
        try {
            jdbc.update(
                    "INSERT INTO t_alert_dedup(alert_id, object_key, calendar_day, status, consecutive_days) "
                            + "VALUES(?,?,?,?,?)",
                    alertId,
                    objectKey,
                    day,
                    status,
                    consecutive);
        } catch (DuplicateKeyException ignored) {
            // 同日重复扫描
        }
    }

    public enum Decision {
        SEND,
        SUPPRESS,
        SKIP
    }
}
