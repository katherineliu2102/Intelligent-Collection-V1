package com.collection.admin.alert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AiCallAlertScannerTest {

    private JdbcTemplate jdbc;
    private AlertDedupRepository dedup;
    private DingTalkAlertClient dingtalk;
    private OpsExceptionWriter exceptions;
    private AiCallAlertScanner scanner;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        dedup = mock(AlertDedupRepository.class);
        dingtalk = mock(DingTalkAlertClient.class);
        exceptions = mock(OpsExceptionWriter.class);
        scanner = new AiCallAlertScanner(jdbc, dedup, dingtalk, exceptions);
        when(dedup.beforeSend(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(AlertDedupRepository.Decision.SEND);
    }

    @Test
    void a1FiresWhenFailedRateExceedsThresholdAndNAtLeast20() {
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(sessions(20, "FAILED"));

        scanner.scanA1(LocalDateTime.of(2026, 9, 7, 10, 0), LocalDate.of(2026, 9, 7));

        verify(dingtalk).sendText(contains("A1 AI Call FAILED 20/20"));
        verify(dingtalk).sendText(contains("slot=0915"));
        verify(dingtalk, never()).sendText(contains("cli="));
    }

    @Test
    void a1DoesNotFireForBusy() {
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(sessions(20, "BUSY"));

        scanner.scanA1(LocalDateTime.of(2026, 9, 7, 10, 0), LocalDate.of(2026, 9, 7));

        verify(dingtalk, never()).sendText(anyString());
        verify(dedup).markRecovered(eq("A1"), eq("0915"), any(LocalDate.class));
    }

    @Test
    void a1DoesNotFireWhenNBelow20() {
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(sessions(19, "FAILED"));

        scanner.scanA1(LocalDateTime.of(2026, 9, 7, 10, 0), LocalDate.of(2026, 9, 7));

        verify(dingtalk, never()).sendText(anyString());
        verify(dedup, never()).markRecovered(eq("A1"), anyString(), any(LocalDate.class));
    }

    @Test
    void a3WritesOpsExceptionAndAlerts() {
        List<Map<String, Object>> hanging = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stepId", 88L);
        row.put("planId", 11L);
        row.put("caseId", 9L);
        hanging.add(row);
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(hanging);

        scanner.scanA3(LocalDateTime.of(2026, 9, 7, 10, 0), LocalDate.of(2026, 9, 7));

        verify(exceptions)
                .upsertOpen(
                        eq("CALLBACK_TIMEOUT"),
                        eq("AI_CALL"),
                        eq("HANGING"),
                        eq(9L),
                        eq(11L),
                        eq(88L),
                        eq("CRITICAL"),
                        contains("stepId=88"),
                        eq("A3:AI_CALL:88"));
        verify(dingtalk).sendText(contains("A3 hanging"));
        verify(dingtalk, never()).sendText(contains("09xxxxxxxx"));
    }

    private static List<Map<String, Object>> sessions(int n, String reason) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batch_id", "mocasa-20260907-0915-1");
            row.put("was_answered", 0);
            row.put("line_reason", null);
            row.put(
                    "final_failure_reason",
                    "BUSY".equals(reason) ? "BUSY" : "MEDIA_NEGOTIATION_FAILED");
            row.put("sip_code", "488");
            row.put("caller_cli", "6310001");
            out.add(row);
        }
        return out;
    }
}
