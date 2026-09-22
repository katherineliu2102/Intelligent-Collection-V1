package com.collection.admin.aicall;

import com.collection.admin.aicall.AiCallScriptParser.Parsed;
import com.collection.channel.adapter.FacadeBatchClient;
import com.collection.channel.config.ChannelProperties;
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
 * 异步拉取 {@code script_url} 写入 {@code t_ai_call_media.has_borrower_turn} 等列。
 *
 * <p>与告警扫描器相同：仅 {@code collection.scheduler.enabled=true}（Pilot）每分钟跑。不新增 Cloud Scheduler Job。不写
 * {@code t_ai_call_session.needs_review}。录音不下载。
 */
@Component
@ConditionalOnProperty(prefix = "collection.scheduler", name = "enabled", havingValue = "true")
public class AiCallScriptFetchJob {

    private static final Logger log = LoggerFactory.getLogger(AiCallScriptFetchJob.class);
    static final int BATCH_LIMIT = 20;
    static final int MAX_ATTEMPTS = 8;
    static final int MAX_BODY_CHARS = 512 * 1024;

    private final JdbcTemplate jdbc;
    private final FacadeBatchClient facadeClient;
    private final ChannelProperties channelProperties;

    public AiCallScriptFetchJob(
            JdbcTemplate jdbc,
            FacadeBatchClient facadeClient,
            ChannelProperties channelProperties) {
        this.jdbc = jdbc;
        this.facadeClient = facadeClient;
        this.channelProperties = channelProperties;
    }

    @Scheduled(cron = "20 * * * * *")
    public void scan() {
        try {
            fetchPending();
        } catch (RuntimeException e) {
            log.error("[ai-call-media] fetch scan failed", e);
        }
    }

    int fetchPending() {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT session_id, script_url FROM t_ai_call_media "
                                + "WHERE fetch_status IN ('PENDING','FAILED') "
                                + "AND fetch_attempts < ? "
                                + "AND script_url IS NOT NULL AND script_url <> '' "
                                + "ORDER BY id ASC LIMIT ?",
                        MAX_ATTEMPTS,
                        BATCH_LIMIT);
        int ok = 0;
        for (Map<String, Object> row : rows) {
            String sessionId = String.valueOf(row.get("session_id"));
            String scriptUrl = String.valueOf(row.get("script_url"));
            if (fetchOne(sessionId, scriptUrl)) {
                ok++;
            }
        }
        return ok;
    }

    boolean fetchOne(String sessionId, String scriptUrl) {
        jdbc.update(
                "UPDATE t_ai_call_media SET fetch_attempts = fetch_attempts + 1, updated_at=NOW() "
                        + "WHERE session_id=?",
                sessionId);
        String baseUrl =
                channelProperties == null || channelProperties.getFacade() == null
                        ? null
                        : channelProperties.getFacade().getBaseUrl();
        if (!FacadeMediaUrlGuard.hostAllowed(scriptUrl, baseUrl)) {
            markFailed(sessionId, "script_url host not facade");
            return false;
        }
        try {
            String body = facadeClient.getAbsoluteUrl(scriptUrl);
            if (body != null && body.length() > MAX_BODY_CHARS) {
                markFailed(sessionId, "body too large");
                return false;
            }
            Parsed parsed = AiCallScriptParser.parse(body);
            String status = parsed.turnCount == 0 ? "EMPTY" : "OK";
            jdbc.update(
                    "UPDATE t_ai_call_media SET fetch_status=?, script_json=?, transcript_text=?, "
                            + "turn_count=?, has_borrower_turn=?, fetch_error=NULL, fetched_at=NOW(), "
                            + "updated_at=NOW() WHERE session_id=?",
                    status,
                    parsed.scriptJson,
                    parsed.transcriptText,
                    parsed.turnCount,
                    parsed.hasBorrowerTurn ? 1 : 0,
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
            log.warn("[ai-call-media] fetch failed session={}", sessionId, e);
            return false;
        }
    }

    private void markFailed(String sessionId, String error) {
        jdbc.update(
                "UPDATE t_ai_call_media SET fetch_status='FAILED', fetch_error=?, updated_at=NOW() "
                        + "WHERE session_id=?",
                error,
                sessionId);
    }

    private static String abbreviate(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "fetch failed";
        }
        return raw.length() <= 240 ? raw : raw.substring(0, 240);
    }
}
