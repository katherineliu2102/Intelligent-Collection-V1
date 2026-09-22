package com.collection.admin.aicall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.channel.adapter.FacadeBatchClient;
import com.collection.channel.config.ChannelProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClientException;

class AiCallScriptFetchJobTest {

    private JdbcTemplate jdbc;
    private FacadeBatchClient client;
    private AiCallScriptFetchJob job;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        client = mock(FacadeBatchClient.class);
        ChannelProperties props = new ChannelProperties();
        props.getFacade().setBaseUrl("https://facade.example");
        job = new AiCallScriptFetchJob(jdbc, client, props);
    }

    @Test
    void assistantOnlySetsHasBorrowerTurnZero() {
        stubOnePending("s1", "https://facade.example/scripts/s1.json");
        when(client.getAbsoluteUrl(anyString()))
                .thenReturn(
                        "{\"conversation_history\":[{\"role\":\"assistant\",\"content\":\"Hi\"}]}");

        assertThat(job.fetchPending()).isEqualTo(1);
        verify(jdbc)
                .update(
                        contains("has_borrower_turn=?"),
                        eq("OK"),
                        any(),
                        any(),
                        eq(1),
                        eq(0),
                        eq("s1"));
        verify(jdbc, never()).update(contains("needs_review"), any(), any());
    }

    @Test
    void borrowerTurnSetsHasBorrowerTurnOne() {
        stubOnePending("s2", "https://facade.example/scripts/s2.json");
        when(client.getAbsoluteUrl(anyString()))
                .thenReturn(
                        "{\"conversation_history\":["
                                + "{\"role\":\"assistant\",\"content\":\"Hi\"},"
                                + "{\"role\":\"user\",\"content\":\"Opo\"}]}");

        job.fetchPending();
        verify(jdbc)
                .update(
                        contains("has_borrower_turn=?"),
                        eq("OK"),
                        any(),
                        any(),
                        eq(2),
                        eq(1),
                        eq("s2"));
    }

    @Test
    void offHostUrlDoesNotCallFacade() {
        stubOnePending("s3", "https://evil.example/scripts/s3.json");
        assertThat(job.fetchPending()).isEqualTo(0);
        verify(client, never()).getAbsoluteUrl(anyString());
        verify(jdbc).update(contains("fetch_status='FAILED'"), contains("host"), eq("s3"));
    }

    @Test
    void httpFailureMarksFailed() {
        stubOnePending("s4", "https://facade.example/scripts/s4.json");
        when(client.getAbsoluteUrl(anyString())).thenThrow(new RestClientException("404"));

        assertThat(job.fetchPending()).isEqualTo(0);
        verify(jdbc).update(contains("fetch_status='FAILED'"), contains("404"), eq("s4"));
    }

    @Test
    void hostAllowedRequiresSameHostAndScheme() {
        assertThat(
                        FacadeMediaUrlGuard.hostAllowed(
                                "https://facade.example/s.json", "https://facade.example"))
                .isTrue();
        assertThat(
                        FacadeMediaUrlGuard.hostAllowed(
                                "http://facade.example/s.json", "https://facade.example"))
                .isFalse();
        assertThat(
                        FacadeMediaUrlGuard.hostAllowed(
                                "https://other.example/s.json", "https://facade.example"))
                .isFalse();
        assertThat(
                        FacadeMediaUrlGuard.hostAllowed(
                                "https://facade.example:8443/s.json", "https://facade.example"))
                .isFalse();
        assertThat(
                        FacadeMediaUrlGuard.hostAllowed(
                                "https://facade.example:443/s.json", "https://facade.example"))
                .isTrue();
    }

    private void stubOnePending(String sessionId, String url) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("session_id", sessionId);
        row.put("script_url", url);
        rows.add(row);
        when(jdbc.queryForList(
                        anyString(),
                        eq(AiCallScriptFetchJob.MAX_ATTEMPTS),
                        eq(AiCallScriptFetchJob.BATCH_LIMIT)))
                .thenReturn(rows);
    }
}
