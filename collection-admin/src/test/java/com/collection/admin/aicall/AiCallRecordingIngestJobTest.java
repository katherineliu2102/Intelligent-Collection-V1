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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiCallRecordingIngestJobTest {

    private JdbcTemplate jdbc;
    private FacadeBatchClient client;
    private GcsBlobWriter gcs;
    private AiCallRecordingIngestJob job;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        client = mock(FacadeBatchClient.class);
        gcs = mock(GcsBlobWriter.class);
        ChannelProperties props = new ChannelProperties();
        props.getFacade().setBaseUrl("https://facade.example");
        AicallRecordingProperties rec = new AicallRecordingProperties();
        rec.setRecordingBucket("fintech_bdp");
        rec.setRecordingPrefix("intelligent-collection");
        rec.setRecordingCredentials("/opt/app/secrets/gcs-verif-ai.json");
        job = new AiCallRecordingIngestJob(jdbc, client, props, rec, gcs);
    }

    @Test
    void uploadsWavAndWritesGsUri() {
        stubOne("s1", "https://facade.example/s1.wav");
        when(client.getAbsoluteBytes(anyString())).thenReturn(new byte[] {1, 2, 3});

        assertThat(job.ingestPending()).isEqualTo(1);
        verify(gcs)
                .write(
                        eq("fintech_bdp"),
                        eq("intelligent-collection/ai-call/2026-09-15/s1.wav"),
                        eq(new byte[] {1, 2, 3}),
                        eq("audio/wav"));
        verify(jdbc)
                .update(
                        contains("recording_ingest='OK'"),
                        eq("gs://fintech_bdp/intelligent-collection/ai-call/2026-09-15/s1.wav"),
                        eq("s1"));
    }

    @Test
    void emptyCredentialsSkipsDownload() {
        AicallRecordingProperties rec = new AicallRecordingProperties();
        rec.setRecordingBucket("fintech_bdp");
        rec.setRecordingCredentials("");
        ChannelProperties props = new ChannelProperties();
        job = new AiCallRecordingIngestJob(jdbc, client, props, rec, gcs);
        assertThat(job.ingestPending()).isEqualTo(0);
        verify(client, never()).getAbsoluteBytes(anyString());
    }

    @Test
    void offHostUrlDoesNotDownload() {
        stubOne("s2", "https://evil.example/s2.wav");
        assertThat(job.ingestPending()).isEqualTo(0);
        verify(client, never()).getAbsoluteBytes(anyString());
        verify(jdbc).update(contains("recording_ingest='FAILED'"), contains("host"), eq("s2"));
    }

    @Test
    void tooLargeMarksFailed() {
        stubOne("s3", "https://facade.example/s3.wav");
        when(client.getAbsoluteBytes(anyString()))
                .thenReturn(new byte[AiCallRecordingIngestJob.MAX_BYTES + 1]);
        assertThat(job.ingestPending()).isEqualTo(0);
        verify(gcs, never()).write(anyString(), anyString(), any(byte[].class), anyString());
        verify(jdbc).update(contains("recording_ingest='FAILED'"), contains("too large"), eq("s3"));
    }

    @Test
    void objectNameUsesPhtDate() {
        Timestamp ts = Timestamp.valueOf(LocalDateTime.of(2026, 9, 15, 3, 0));
        assertThat(AiCallRecordingIngestJob.objectName("intelligent-collection", "ab/c", ts))
                .isEqualTo("intelligent-collection/ai-call/2026-09-15/ab_c.wav");
    }

    private void stubOne(String sessionId, String url) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("session_id", sessionId);
        row.put("recording_url", url);
        row.put("created_at", Timestamp.valueOf(LocalDateTime.of(2026, 9, 15, 12, 0)));
        rows.add(row);
        when(jdbc.queryForList(
                        anyString(),
                        eq(AiCallRecordingIngestJob.MAX_ATTEMPTS),
                        eq(AiCallRecordingIngestJob.BATCH_LIMIT)))
                .thenReturn(rows);
    }
}
