package com.collection.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.repository.CaseProjectionRepository.Outcome;
import com.collection.common.repository.MissingCaseBaselineException;
import com.collection.service.mapper.AiCollectionInboxMapper;
import com.collection.service.mapper.AiCollectionInboxRow;
import com.collection.service.mapper.AiCollectionProjectionMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 还款增量合并的仓储级判定（数据接入规格 §3.3）：无基线 poison、陈旧 {@code occurredAt} 记 SKIPPED、 正常增量转 PENDING 待发。SQL 本身由
 * {@code ContactPlanMapperIT} 一类集成测试覆盖，这里只钉决策分支。
 */
class AiCaseProjectionRepositoryTest {

    private static final long CASE_ID = 525441L;

    private AiCaseProjectionRepository repository;
    private AiCollectionProjectionMapper projectionMapper;
    private AiCollectionInboxMapper inboxMapper;

    @BeforeEach
    void setUp() {
        repository = new AiCaseProjectionRepository();
        projectionMapper = mock(AiCollectionProjectionMapper.class);
        inboxMapper = mock(AiCollectionInboxMapper.class);
        ReflectionTestUtils.setField(repository, "projectionMapper", projectionMapper);
        ReflectionTestUtils.setField(repository, "inboxMapper", inboxMapper);
    }

    @Test
    @DisplayName("无完整 caseEvent 基线 → MissingCaseBaselineException，且不写投影不写收件箱")
    void missingBaselineIsRejected() {
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID)).thenReturn(null);

        assertThatThrownBy(
                        () ->
                                repository.applyRepaymentDelta(
                                        command(
                                                "pay-1",
                                                delta(LocalDateTime.of(2026, 8, 13, 10, 6)))))
                .isInstanceOf(MissingCaseBaselineException.class)
                .hasMessageContaining(String.valueOf(CASE_ID));

        verify(projectionMapper, never()).updateRepaymentDelta(any());
        verify(inboxMapper, never()).insertIgnoreDuplicate(any());
    }

    @Test
    @DisplayName("occurredAt 早于投影 → 收件箱记 SKIPPED、返回 STALE_VERSION、不覆盖运行态")
    void staleOccurredAtIsSkipped() {
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID))
                .thenReturn(baseline(LocalDateTime.of(2026, 8, 13, 10, 6)));

        Outcome outcome =
                repository.applyRepaymentDelta(
                        command("pay-stale", delta(LocalDateTime.of(2026, 8, 11, 10, 6))));

        assertThat(outcome).isEqualTo(Outcome.STALE_VERSION);
        verify(projectionMapper, never()).updateRepaymentDelta(any());
        AiCollectionInboxRow row = capturedInboxRow();
        assertThat(row.getPublishStatus()).isEqualTo("SKIPPED");
        assertThat(row.isProjectionApplied()).isFalse();
    }

    @Test
    @DisplayName("occurredAt 等于投影时间 → 视为不陈旧，正常合并（重放同一时刻的事实仍需收敛）")
    void sameOccurredAtIsApplied() {
        LocalDateTime sameMoment = LocalDateTime.of(2026, 8, 13, 10, 6);
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID)).thenReturn(baseline(sameMoment));
        when(projectionMapper.updateRepaymentDelta(any())).thenReturn(1);

        Outcome outcome = repository.applyRepaymentDelta(command("pay-same", delta(sameMoment)));

        assertThat(outcome).isEqualTo(Outcome.APPLIED);
        assertThat(capturedInboxRow().getPublishStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("新鲜增量 → 合并到基线后更新，收件箱记 PENDING 等待事件确认")
    void freshDeltaIsMergedOntoBaseline() {
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID))
                .thenReturn(baseline(LocalDateTime.of(2026, 8, 12, 3, 35)));
        when(projectionMapper.updateRepaymentDelta(any())).thenReturn(1);

        Outcome outcome =
                repository.applyRepaymentDelta(
                        command("pay-2", delta(LocalDateTime.of(2026, 8, 13, 10, 6))));

        assertThat(outcome).isEqualTo(Outcome.APPLIED);
        ArgumentCaptor<CaseProjection> merged = ArgumentCaptor.forClass(CaseProjection.class);
        verify(projectionMapper).updateRepaymentDelta(merged.capture());
        // 基线的产品与借款人信息不参与还款增量，必须原样保留
        assertThat(merged.getValue().getProduct()).isEqualTo("3");
        assertThat(merged.getValue().getBorrowerPhone()).isEqualTo("+639563093217");
        // stage 随还款同步（基线 S1 → 增量 S2），与 dpd 同源同刻
        assertThat(merged.getValue().getStage()).isEqualTo("S2");
        assertThat(merged.getValue().getTotalOutstanding())
                .isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(capturedInboxRow().getPublishStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("增量未携带 stage（缺字段或取值非法）→ 保留基线 stage，其余还款字段照常入账")
    void absentStageInDeltaKeepsBaselineStage() {
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID))
                .thenReturn(baseline(LocalDateTime.of(2026, 8, 12, 3, 35)));
        when(projectionMapper.updateRepaymentDelta(any())).thenReturn(1);

        CaseProjection noStage = delta(LocalDateTime.of(2026, 8, 13, 10, 6));
        noStage.setStage(null);
        noStage.setStagePresent(false);

        assertThat(repository.applyRepaymentDelta(command("pay-3", noStage)))
                .isEqualTo(Outcome.APPLIED);
        ArgumentCaptor<CaseProjection> merged = ArgumentCaptor.forClass(CaseProjection.class);
        verify(projectionMapper).updateRepaymentDelta(merged.capture());
        assertThat(merged.getValue().getStage()).isEqualTo("S1");
        assertThat(merged.getValue().getDpd()).isEqualTo(3);
    }

    @Test
    @DisplayName("增量显式携带 stage=null（提前还清，下一期在 3 天以上）→ 必须清空基线阶段，不能被当成缺省值吞掉")
    void explicitNullStageClearsBaselineStage() {
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID))
                .thenReturn(baseline(LocalDateTime.of(2026, 8, 12, 3, 35)));
        when(projectionMapper.updateRepaymentDelta(any())).thenReturn(1);

        CaseProjection clearedStage = delta(LocalDateTime.of(2026, 8, 13, 10, 6));
        clearedStage.setStage(null);
        clearedStage.setStagePresent(true);
        clearedStage.setDpd(-5);
        clearedStage.setOverdueAmount(BigDecimal.ZERO);

        assertThat(repository.applyRepaymentDelta(command("pay-4", clearedStage)))
                .isEqualTo(Outcome.APPLIED);
        ArgumentCaptor<CaseProjection> merged = ArgumentCaptor.forClass(CaseProjection.class);
        verify(projectionMapper).updateRepaymentDelta(merged.capture());
        assertThat(merged.getValue().getStage()).isNull();
        assertThat(merged.getValue().getDpd()).isEqualTo(-5);
    }

    @Test
    @DisplayName("同一 eventId 已 PENDING → 只补发事件，不再读写投影")
    void pendingEventIdOnlyRepublishes() {
        when(inboxMapper.selectPublishStatus("pay-2")).thenReturn("PENDING");

        Outcome outcome =
                repository.applyRepaymentDelta(
                        command("pay-2", delta(LocalDateTime.of(2026, 8, 13, 10, 6))));

        assertThat(outcome).isEqualTo(Outcome.PENDING_PUBLISH);
        verify(projectionMapper, never()).selectProjectionForUpdate(anyLong());
        verify(inboxMapper, never()).insertIgnoreDuplicate(any());
    }

    @Test
    @DisplayName("同一 eventId 已 PUBLISHED → 整条重复，返回 ALREADY_PROCESSED")
    void publishedEventIdIsAlreadyProcessed() {
        when(inboxMapper.selectPublishStatus("pay-2")).thenReturn("PUBLISHED");

        Outcome outcome =
                repository.applyRepaymentDelta(
                        command("pay-2", delta(LocalDateTime.of(2026, 8, 13, 10, 6))));

        assertThat(outcome).isEqualTo(Outcome.ALREADY_PROCESSED);
        verify(projectionMapper, never()).selectProjectionForUpdate(anyLong());
    }

    @Test
    @DisplayName("caseEvent 快照更旧 → 不覆盖投影，收件箱记 SKIPPED（防 Pub/Sub 重投乱序）")
    void staleCaseEventSnapshotDoesNotOverwriteProjection() {
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID))
                .thenReturn(baseline(LocalDateTime.of(2026, 8, 13, 3, 0)));

        Outcome outcome =
                repository.apply(
                        caseCommand(
                                "case-stale",
                                snapshot("fingerprint-11", LocalDateTime.of(2026, 8, 12, 3, 0))));

        assertThat(outcome).isEqualTo(Outcome.STALE_VERSION);
        verify(projectionMapper, never()).updateIfChanged(any());
        assertThat(capturedInboxRow().getPublishStatus()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("caseEvent 指纹相同 → 仍刷新归属日，不覆盖业务列")
    void identicalFingerprintStillRefreshesOwnerDate() {
        CaseProjection stored = baseline(LocalDateTime.of(2026, 8, 13, 3, 0));
        stored.setOwner("NEW");
        stored.setOwnerDate(LocalDate.of(2026, 8, 13));
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID)).thenReturn(stored);
        when(projectionMapper.updateOwnerDate(any())).thenReturn(1);

        CaseProjection incoming = snapshot("fingerprint-12", LocalDateTime.of(2026, 8, 14, 3, 0));
        incoming.setOwner("NEW");
        incoming.setOwnerDate(LocalDate.of(2026, 8, 14));
        CaseProjectionCommand command = caseCommand("case-same", incoming);
        command.setPublishRequired(false);

        Outcome outcome = repository.apply(command);

        assertThat(outcome).isEqualTo(Outcome.APPLIED_WITHOUT_EVENT);
        verify(projectionMapper).updateOwnerDate(any());
        verify(projectionMapper, never()).updateIfChanged(any());
    }

    @Test
    @DisplayName("caseEvent 归属日早于已落库 → 拒绝，不覆盖")
    void olderOwnerDateIsRejected() {
        CaseProjection stored = baseline(LocalDateTime.of(2026, 8, 14, 3, 0));
        stored.setOwnerDate(LocalDate.of(2026, 8, 14));
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID)).thenReturn(stored);

        CaseProjection incoming = snapshot("fingerprint-13", LocalDateTime.of(2026, 8, 13, 3, 0));
        incoming.setOwnerDate(LocalDate.of(2026, 8, 13));

        Outcome outcome = repository.apply(caseCommand("case-older-owner", incoming));

        assertThat(outcome).isEqualTo(Outcome.STALE_VERSION);
        verify(projectionMapper, never()).updateIfChanged(any());
        verify(projectionMapper, never()).updateOwnerDate(any());
    }

    @Test
    @DisplayName("caseEvent 指纹变化且不更旧 → 覆盖投影并排队事件；等时刻刷新同样放行")
    void fresherOrSameMomentSnapshotIsApplied() {
        LocalDateTime baselineMoment = LocalDateTime.of(2026, 8, 13, 3, 0);
        when(inboxMapper.selectPublishStatus(anyString())).thenReturn(null);
        when(projectionMapper.selectProjectionForUpdate(CASE_ID))
                .thenReturn(baseline(baselineMoment));
        when(projectionMapper.updateIfChanged(any())).thenReturn(1);

        assertThat(
                        repository.apply(
                                caseCommand(
                                        "case-fresh",
                                        snapshot(
                                                "fingerprint-13",
                                                LocalDateTime.of(2026, 8, 14, 3, 0)))))
                .isEqualTo(Outcome.APPLIED);
        assertThat(
                        repository.apply(
                                caseCommand(
                                        "case-same-moment",
                                        snapshot("fingerprint-14", baselineMoment))))
                .isEqualTo(Outcome.APPLIED);
    }

    private AiCollectionInboxRow capturedInboxRow() {
        ArgumentCaptor<AiCollectionInboxRow> row =
                ArgumentCaptor.forClass(AiCollectionInboxRow.class);
        verify(inboxMapper).insertIgnoreDuplicate(row.capture());
        return row.getValue();
    }

    private CaseProjectionCommand command(String eventId, CaseProjection projection) {
        CaseProjectionCommand command = new CaseProjectionCommand();
        command.setEventId(eventId);
        command.setMessageType("repaymentEvent");
        command.setEventType("REPAYMENT");
        command.setPayload("{}");
        command.setPublishRequired(true);
        command.setProjection(projection);
        return command;
    }

    private CaseProjection baseline(LocalDateTime updatedAt) {
        CaseProjection projection = new CaseProjection();
        projection.setCaseId(CASE_ID);
        projection.setUserId(2145521L);
        projection.setCaseVersion("fingerprint-12");
        projection.setProduct("3");
        projection.setStage("S1");
        projection.setDpd(2);
        projection.setCollectionStatus("IN_COLLECTION");
        projection.setTotalOutstanding(new BigDecimal("3000.00"));
        projection.setBorrowerPhone("+639563093217");
        projection.setUpdatedAt(updatedAt);
        return projection;
    }

    private CaseProjectionCommand caseCommand(String eventId, CaseProjection projection) {
        CaseProjectionCommand command = command(eventId, projection);
        command.setMessageType("caseEvent");
        command.setEventType("CASE_INGESTED");
        return command;
    }

    /** caseEvent 是完整快照：{@code caseVersion} 为内容指纹，{@code updatedAt} 来自 payload 的 occurredAt。 */
    private CaseProjection snapshot(String caseVersion, LocalDateTime occurredAt) {
        CaseProjection projection = baseline(occurredAt);
        projection.setCaseVersion(caseVersion);
        return projection;
    }

    private CaseProjection delta(LocalDateTime updatedAt) {
        CaseProjection projection = new CaseProjection();
        projection.setCaseId(CASE_ID);
        projection.setUserId(2145521L);
        projection.setStage("S2");
        projection.setStagePresent(true);
        // 基线 dpd=2、相隔 1 天，dpd=3 是唯一自洽的取值：更大的跳变会被还款自洽性护栏判为口径错误
        projection.setDpd(3);
        projection.setCollectionStatus("IN_COLLECTION");
        projection.setOverdueAmount(new BigDecimal("1200.00"));
        projection.setTotalOutstanding(new BigDecimal("1200.00"));
        projection.setPenaltyAmount(new BigDecimal("50.00"));
        projection.setUpcomingAmount(new BigDecimal("900.00"));
        projection.setUpdatedAt(updatedAt);
        return projection;
    }
}
