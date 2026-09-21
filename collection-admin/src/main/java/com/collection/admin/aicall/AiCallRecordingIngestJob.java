package com.collection.admin.aicall;

import com.collection.channel.adapter.FacadeBatchClient;
import com.collection.channel.config.ChannelProperties;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * PHT 02:30 批量把 {@code recording_url} 拉进 GCS，回写 {@code recording_object_uri}。
 *
 * <p>与 script 拉取拆开。不新增 Cloud Scheduler Job。桶空则跳过。
 */
@Component
@ConditionalOnProperty(prefix = "collection.scheduler", name = "enabled", havingValue = "true")
public class AiCallRecordingIngestJob {

    private static final Logger log = LoggerFactory.getLogger(AiCallRecordingIngestJob.class);
    static final int BATCH_LIMIT = 250;
    static final int MAX_ATTEMPTS = 8;
    static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final ZoneId PHT = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JdbcTemplate jdbc;
    private final FacadeBatchClient facadeClient;
    private final ChannelProperties channelProperties;
    private final AicallRecordingProperties recordingProperties;
    private final GcsBlobWriter gcsBlobWriter;

    public AiCallRecordingIngestJob(
            JdbcTemplate jdbc,
            FacadeBatchClient facadeClient,
            ChannelProperties channelProperties,
            AicallRecordingProperties recordingProperties,
            GcsBlobWriter gcsBlobWriter) {
        this.jdbc = jdbc;
        this.facadeClient = facadeClient;
        this.channelProperties = channelProperties;
        this.recordingProperties = recordingProperties;
        this.gcsBlobWriter = gcsBlobWriter;
    }

    @Scheduled(cron = "0 30 2 * * *")
    public void scan() {
        try {
            ingestPending();
        } catch (RuntimeException e) {
            log.error("[ai-call-recording] ingest scan failed", e);
        }
    }

    int ingestPending() {
        String bucket =
                recordingProperties == null ? null : recordingProperties.getRecordingBucket();
        String credPath =
                recordingProperties == null ? null : recordingProperties.getRecordingCredentials();
        if (StringUtils.isBlank(bucket) || StringUtils.isBlank(credPath)) {
            log.warn("[ai-call-recording] skip: bucket or credentials empty");
            return 0;
        }
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT session_id, recording_url, created_at FROM t_ai_call_media "
                                + "WHERE recording_url IS NOT NULL AND recording_url <> '' "
                                + "AND (recording_object_uri IS NULL OR recording_object_uri = '') "
                                + "AND (recording_ingest IS NULL OR recording_ingest IN ('PENDING','FAILED')) "
                                + "AND recording_ingest_attempts < ? "
                                + "ORDER BY id ASC LIMIT ?",
                        MAX_ATTEMPTS,
                        BATCH_LIMIT);
        int ok = 0;
        for (Map<String, Object> row : rows) {
            if (ingestOne(
                    String.valueOf(row.get("session_id")),
                    String.valueOf(row.get("recording_url")),
                    row.get("created_at"),
                    bucket.trim())) {
                ok++;
            }
        }
        log.info("[ai-call-recording] scanned={} ok={}", rows.size(), ok);
        return ok;
    }

    boolean ingestOne(String sessionId, String recordingUrl, Object createdAt, String bucket) {
        jdbc.update(
                "UPDATE t_ai_call_media SET recording_ingest_attempts = "
                        + "recording_ingest_attempts + 1, updated_at=NOW() WHERE session_id=?",
                sessionId);
        String baseUrl =
                channelProperties == null || channelProperties.getFacade() == null
                        ? null
                        : channelProperties.getFacade().getBaseUrl();
        if (!FacadeMediaUrlGuard.hostAllowed(recordingUrl, baseUrl)) {
            markFailed(sessionId, "recording_url host not facade");
            return false;
        }
        try {
            byte[] body = facadeClient.getAbsoluteBytes(recordingUrl);
            if (body == null || body.length == 0) {
                markFailed(sessionId, "empty recording body");
                return false;
            }
            if (body.length > MAX_BYTES) {
                markFailed(sessionId, "recording too large");
                return false;
            }
            String objectName =
                    objectName(recordingProperties.getRecordingPrefix(), sessionId, createdAt);
            gcsBlobWriter.write(bucket, objectName, body, "audio/wav");
            String uri = "gs://" + bucket + "/" + objectName;
            jdbc.update(
                    "UPDATE t_ai_call_media SET recording_ingest='OK', recording_object_uri=?, "
                            + "recording_ingest_error=NULL, recording_ingested_at=NOW(), updated_at=NOW() "
                            + "WHERE session_id=?",
                    uri,
                    sessionId);
            return true;
        } catch (IllegalArgumentException e) {
            markFailed(sessionId, abbreviate(e.getMessage()));
            return false;
        } catch (RestClientException e) {
            markFailed(sessionId, abbreviate(e.getMessage()));
            return false;
        } catch (RuntimeException e) {
            markFailed(sessionId, abbreviate(e.getClass().getSimpleName()));
            log.warn("[ai-call-recording] ingest failed session={}", sessionId, e);
            return false;
        }
    }

    static String objectName(String prefix, String sessionId, Object createdAt) {
        String day = DAY.format(toPhtDate(createdAt));
        String safe = sessionId == null ? "unknown" : sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        String path = "ai-call/" + day + "/" + safe + ".wav";
        if (StringUtils.isBlank(prefix)) {
            return path;
        }
        String trimmed = prefix.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/" + path;
    }

    static LocalDate toPhtDate(Object createdAt) {
        if (createdAt instanceof Timestamp) {
            return ((Timestamp) createdAt).toInstant().atZone(PHT).toLocalDate();
        }
        if (createdAt instanceof Date) {
            return ((Date) createdAt).toInstant().atZone(PHT).toLocalDate();
        }
        return LocalDate.now(PHT);
    }

    private void markFailed(String sessionId, String error) {
        jdbc.update(
                "UPDATE t_ai_call_media SET recording_ingest='FAILED', recording_ingest_error=?, "
                        + "updated_at=NOW() WHERE session_id=?",
                error,
                sessionId);
    }

    private static String abbreviate(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "ingest failed";
        }
        return raw.length() <= 240 ? raw : raw.substring(0, 240);
    }
}
