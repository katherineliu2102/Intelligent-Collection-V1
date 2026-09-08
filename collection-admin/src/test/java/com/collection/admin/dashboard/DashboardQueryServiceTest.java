package com.collection.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DashboardQueryServiceTest {

    private JdbcTemplate jdbc;
    private DashboardQueryService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(Collections.emptyList());
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(Collections.emptyList());
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(Collections.emptyList());
        when(jdbc.queryForObject(anyString(), any(Object[].class), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        service = new DashboardQueryService(jdbc);
    }

    @Test
    void toLongAcceptsMysqlTinyint1Booleans() {
        assertThat(DashboardQueryService.toLong(null)).isEqualTo(0L);
        assertThat(DashboardQueryService.toLong(Boolean.FALSE)).isEqualTo(0L);
        assertThat(DashboardQueryService.toLong(Boolean.TRUE)).isEqualTo(1L);
        assertThat(DashboardQueryService.toLong("false")).isEqualTo(0L);
        assertThat(DashboardQueryService.toLong("true")).isEqualTo(1L);
        assertThat(DashboardQueryService.toLong(Integer.valueOf(3))).isEqualTo(3L);
    }

    @Test
    void deliveryRateIsNullWhenAttemptedIsZero() {
        Map<String, Object> row = new LinkedHashMap<>();
        DashboardQueryService.putMetrics(row, 10, 0, 0, 0, 10, 0);
        assertThat(row.get("deliveryRate")).isNull();
        DashboardQueryService.putMetrics(row, 10, 8, 6, 2, 2, 0);
        assertThat(row.get("deliveryRate")).isEqualTo(0.75);
    }

    @Test
    void outreachRealtimeDoesNotMergeChannelsByStage() {
        Map<String, Object> data = service.outreachRealtime(7);
        assertThat(data).doesNotContainKey("byStage");
        assertThat(data.get("byChannel")).isInstanceOf(java.util.List.class);
        assertThat(((Map<?, ?>) data.get("summary")).get("deliveryRate")).isNull();
    }

    @Test
    void matrixSqlGroupsByChannelAndStage() {
        service.matrix(7);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(s -> s.contains("GROUP BY t.channel, COALESCE(p.stage"))
                .anyMatch(s -> s.contains("COALESCE(s.stage_snapshot, p.stage, c.stage"))
                .noneMatch(
                        s -> s.contains("GROUP BY COALESCE(p.stage") && !s.contains("t.channel"));
    }

    @Test
    void portfolioByStageOmitsS0AndNullStage() {
        service.portfolio();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("stage IN ('S1','S2','S3','S4')")
                                        && s.contains("collection_status='IN_COLLECTION'")
                                        && !s.contains("'N/A'"));
    }

    @Test
    void touchConversionCountsDistinctCasesAndIncludesAiAnswered() {
        service.portfolio();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("COUNT(DISTINCT t.case_id) AS touched")
                                        && s.contains("'SENT'")
                                        && s.contains("t_ai_call_session")
                                        && s.contains("UNION ALL"));
    }

    @Test
    void answeredDetailExcludesSnrAndUsesEndedMinusAnswered() {
        service.todayExecution();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("TIMESTAMPDIFF(SECOND, s.answered_at, s.ended_at)")
                                        && s.contains("VOICEMAIL")
                                        && s.contains("CALL_SCREENING")
                                        && s.contains("was_answered=1"));
        assertThat(sql.getAllValues())
                .noneMatch(s -> s.contains("COALESCE(s.answered_at, s.dialed_at)"));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("s.dpd_snapshot AS dpd_snapshot")
                                        && !s.contains("COALESCE(s.dpd_snapshot, c.dpd)"));
    }

    @Test
    void aicallDetailExcludesSnr() {
        service.aicallDetail(1, 25, 7, false);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("TIMESTAMPDIFF(SECOND, s.answered_at, s.ended_at)")
                                        && s.contains("line_reason NOT IN ('VOICEMAIL','CALL_SCREENING')"));
    }

    @Test
    void aicallWavesUseSameDateWindowAsFunnel() {
        service.aicallRealtime(7, false);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("batch_id")
                                        && s.contains("DATE_SUB(NOW(), INTERVAL 7 DAY)")
                                        && s.contains("t_ai_call_session"));
    }

    @Test
    void highSensitivityAliasesCamelCaseForUi() {
        service.risk();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("session_id AS sessionId")
                                        && s.contains("case_id AS caseId")
                                        && s.contains("result_label AS resultLabel"));
    }
}
