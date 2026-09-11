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
                .anyMatch(s -> s.contains("SUM(s.was_answered=1)"))
                .anyMatch(s -> s.contains("AI_CALL_HUMAN") && s.contains("party='human'"))
                .noneMatch(
                        s -> s.contains("GROUP BY COALESCE(p.stage") && !s.contains("t.channel"));
    }

    @Test
    void portfolioYesterdayWorksetExcludesSkippedAndUsesActionDpd() {
        service.portfolio();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("t_contact_timeline")
                                        && s.contains("t_ai_call_session")
                                        && s.contains("UNION ALL")
                                        && s.contains("SKIPPED") == false
                                        && s.contains("$.caseContext.dpd")
                                        && s.contains("dpd_snapshot")
                                        && !s.contains("c.dpd > 0"));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("t_ai_collection_inbox")
                                        && s.contains("repaymentEvent")
                                        && s.contains("paidAmount")
                                && s.contains("openingOutstanding")
                                && s.contains("caseEvent"));
        assertThat(sql.getAllValues()).noneMatch(s -> s.contains("outstandingYesterdayEst"));
        assertThat(sql.getAllValues()).noneMatch(s -> s.contains("settledMissingTime"));
        assertThat(sql.getAllValues())
                .noneMatch(
                        s ->
                                s.contains("collection_status='IN_COLLECTION'")
                                        && s.contains("stage IN ('S1','S2','S3','S4')")
                                        && s.contains("FROM t_ai_collection")
                                        && !s.contains("t_contact_timeline"));
    }

    @Test
    void portfolioWorksetByStageUsesActionSnapshotNotLiveStage() {
        service.portfolio();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("x.src='AI'")
                                        && s.contains("stage_snapshot")
                                        && s.contains("GROUP BY x.case_id"));
    }

    @Test
    void answeredDetailListsLineAnswered() {
        service.todayExecution();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("s.was_answered=1")
                                        && s.contains("s.effective_conversation")
                                        && s.contains("s.right_party")
                                        && s.contains("s.party"));
        assertThat(sql.getAllValues())
                .noneMatch(s -> s.contains("COALESCE(s.answered_at, s.dialed_at)"));
        assertThat(sql.getAllValues()).noneMatch(s -> s.contains("TIMESTAMPDIFF"));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("s.dpd_snapshot AS dpd_snapshot")
                                        && !s.contains("COALESCE(s.dpd_snapshot, c.dpd)"));
    }

    @Test
    void aicallDetailDefaultsToLineAnswered() {
        service.aicallDetail(1, 25, 7, false);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("s.was_answered=1")
                                        && s.contains("s.effective_conversation")
                                        && s.contains("s.right_party"));
        assertThat(sql.getAllValues()).noneMatch(s -> s.contains("TIMESTAMPDIFF"));
    }

    @Test
    void aicallDetailFilterAddsConnectKind() {
        service.aicallDetail(1, 25, 7, false, null, null, null, "unrecognized");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(s -> s.contains("party<>'human'") && s.contains("was_answered=1"));
    }

    @Test
    void aicallDetailFilterAddsLabelAndWavePredicates() {
        service.aicallDetail(1, 25, 7, false, "vague_commitment", "S2", "20260909-0915");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("s.disposition=?")
                                        && s.contains("COALESCE(s.stage_snapshot, p.stage)=?")
                                        && s.contains("s.batch_id LIKE ?"));
    }

    @Test
    void aicallLabelsRequireRightPartyYes() {
        service.aicallRealtime(7, false);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(s -> s.contains("right_party='yes'") && s.contains("disposition"));
    }

    @Test
    void aicallFailureStructureGroupsByFailureClass() {
        service.aicallRealtime(7, false);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues())
                .anyMatch(
                        s ->
                                s.contains("failure_class")
                                        && s.contains("was_answered=0")
                                        && s.contains("GROUP BY failure_class"));
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
                                        && s.contains("disposition AS resultLabel"));
    }
}
