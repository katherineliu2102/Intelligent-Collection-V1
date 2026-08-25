package com.collection.ingestion.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.alibaba.fastjson.JSON;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.repository.CaseProjectionRepository;
import com.collection.common.repository.MissingCaseBaselineException;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import com.collection.ingestion.metrics.IngestionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** {@link AiCaseIngestionProcessor} 纯逻辑单测：投影先于领域事件、重放与乱序收敛、每日快照静默刷新、 外部阶段事件被拒。不连数据库 / GCP。 */
class AiCaseIngestionProcessorTest {

    private RecordingProjectionRepository repository;
    private IngestionService ingestionService;
    private InMemoryIngestionDedupStore dedup;
    private AiCaseIngestionProcessor processor;
    private IngestionProperties props;
    private IngestionFaultInjector faultInjector;

    @BeforeEach
    void setUp() {
        props = new IngestionProperties();
        CasePayloadMapper mapper = new CasePayloadMapper();
        faultInjector = new IngestionFaultInjector();
        ReflectionTestUtils.setField(faultInjector, "props", props);

        repository = new RecordingProjectionRepository();
        ingestionService = mock(IngestionService.class);
        dedup = new InMemoryIngestionDedupStore();

        processor = new AiCaseIngestionProcessor();
        ReflectionTestUtils.setField(processor, "mapper", mapper);
        ReflectionTestUtils.setField(processor, "assembler", new CaseProjectionAssembler());
        ReflectionTestUtils.setField(processor, "projectionRepository", repository);
        ReflectionTestUtils.setField(processor, "ingestionService", ingestionService);
        ReflectionTestUtils.setField(processor, "dedup", dedup);
        ReflectionTestUtils.setField(processor, "faultInjector", faultInjector);
        ReflectionTestUtils.setField(
                processor, "metrics", new IngestionMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void caseIngested_writesProjectionThenPublishes() {
        String body = caseIngestedBody("fingerprint-12");

        processor.handleCaseEvent(JSON.parseObject(body), body);

        CaseProjection projection = repository.applied.get(0).getProjection();
        assertEquals(525441L, projection.getCaseId());
        assertEquals("fingerprint-12", projection.getCaseVersion());
        assertEquals("IN_COLLECTION", projection.getCollectionStatus());
        assertEquals(0, new BigDecimal("3000.00").compareTo(projection.getTotalOutstanding()));
        assertEquals("+639563093217", projection.getBorrowerPhone());
        assertTrue(repository.published.contains("evt-1"));
        verify(ingestionService)
                .ingestCase(eq(525441L), eq(2145521L), eq(Stage.S1), any(Map.class));
    }

    @Test
    void caseIngested_publishFailure_leavesInboxPendingForRedelivery() {
        String body = caseIngestedBody("fingerprint-12");
        doThrow(new IllegalStateException("bus down"))
                .when(ingestionService)
                .ingestCase(anyLong(), anyLong(), any(), any());

        assertThrows(
                IllegalStateException.class,
                () -> processor.handleCaseEvent(JSON.parseObject(body), body));
        assertTrue(repository.published.isEmpty());

        // 重投：投影不再重复写入，只补发领域事件
        repository.nextOutcome = CaseProjectionRepository.Outcome.PENDING_PUBLISH;
        ingestionService = mock(IngestionService.class);
        ReflectionTestUtils.setField(processor, "ingestionService", ingestionService);

        processor.handleCaseEvent(JSON.parseObject(body), body);

        verify(ingestionService)
                .ingestCase(eq(525441L), eq(2145521L), eq(Stage.S1), any(Map.class));
        assertTrue(repository.published.contains("evt-1"));
    }

    /**
     * L4b-11 注入点自身的守护：投影后注入必须落在「投影已写、事件未发」这个窗口内。 注入点前移一行就会让 L4b-11 退化成 L4b-7（重投走 APPLIED 而非
     * PENDING_PUBLISH），本测试是唯一能挡住这种回归的地方。
     */
    @Test
    void postProjectionFault_writesProjectionButNotEvent() {
        props.setFaultInjectionEnabled(true);
        assertEquals(1, faultInjector.armPostProjection(1));
        String body = caseIngestedBody("fingerprint-12");

        assertThrows(
                IllegalStateException.class,
                () -> processor.handleCaseEvent(JSON.parseObject(body), body));

        assertEquals(1, repository.applied.size(), "投影必须已经写过一次");
        assertTrue(repository.published.isEmpty(), "领域事件不得发出，收件箱应留在待发布态");
        verifyNoInteractions(ingestionService);
        assertEquals(0, faultInjector.remainingPostProjection());

        // 重投：命中 PENDING_PUBLISH，只补发事件
        repository.nextOutcome = CaseProjectionRepository.Outcome.PENDING_PUBLISH;

        processor.handleCaseEvent(JSON.parseObject(body), body);

        verify(ingestionService, times(1))
                .ingestCase(eq(525441L), eq(2145521L), eq(Stage.S1), any(Map.class));
        assertTrue(repository.published.contains("evt-1"));
    }

    /** 未启用 fault-injection 时 arm 无效，避免开关误配把注入带到 Pilot。 */
    @Test
    void postProjectionFault_ignoredWhenDisabled() {
        assertEquals(0, faultInjector.armPostProjection(1));
        String body = caseIngestedBody("fingerprint-12");

        processor.handleCaseEvent(JSON.parseObject(body), body);

        assertTrue(repository.published.contains("evt-1"));
    }

    @Test
    void caseIngested_staleVersion_skipsPublish() {
        repository.nextOutcome = CaseProjectionRepository.Outcome.STALE_VERSION;
        String body = caseIngestedBody("fingerprint-11");

        processor.handleCaseEvent(JSON.parseObject(body), body);

        verifyNoInteractions(ingestionService);
        assertTrue(repository.published.isEmpty());
    }

    @Test
    void externalStageEvent_isRejectedAsPoison() {
        String body =
                caseIngestedBody("fingerprint-12")
                        .replace(
                                "\"eventId\":\"evt-1\",",
                                "\"eventId\":\"evt-1\",\"eventType\":\"CASE_STAGE_CHANGED\",");

        PoisonMessageException error =
                assertThrows(
                        PoisonMessageException.class,
                        () -> processor.handleCaseEvent(JSON.parseObject(body), body));

        assertTrue(error.getMessage().contains("CASE_STAGE_CHANGED"));
        assertTrue(repository.applied.isEmpty());
        verifyNoInteractions(ingestionService);
    }

    @Test
    void dailyCaseEventForExistingCycle_refreshesProjectionWithoutDomainEvent() {
        String initial = caseIngestedBody("fingerprint-12");
        processor.handleCaseEvent(JSON.parseObject(initial), initial);
        String refresh = caseIngestedBody("fingerprint-20").replace("\"evt-1\"", "\"evt-2\"");

        processor.handleCaseEvent(JSON.parseObject(refresh), refresh);

        CaseProjectionCommand command = repository.applied.get(1);
        assertEquals("caseEvent", command.getMessageType());
        assertEquals(false, command.isPublishRequired());
        verify(ingestionService, times(1))
                .ingestCase(eq(525441L), eq(2145521L), eq(Stage.S1), any(Map.class));
        assertTrue(repository.published.contains("evt-1"));
    }

    @Test
    void repayment_fullyCleared_publishesRepaymentReceived() {
        String caseBody = caseIngestedBody("fingerprint-12");
        processor.handleCaseEvent(JSON.parseObject(caseBody), caseBody);
        String body =
                "{\"eventId\":\"pay-1\",\"eventType\":\"REPAYMENT\","
                        + "\"occurredAt\":\"2026-08-12T10:06:00+08:00\",\"caseId\":\"525441\","
                        + "\"userId\":\"2145521\",\"isFullCleared\":true,"
                        + "\"stage\":\"S1\",\"dpd\":2,\"overdueAmount\":0.00,"
                        + "\"overduePenaltyAmount\":0.00,\"upcomingAmount\":0.00,\"nextDueDate\":null}";

        processor.handleRepaymentEvent(JSON.parseObject(body), body);

        verify(ingestionService).repayment(525441L, 2145521L);
        assertTrue(repository.published.contains("pay-1"));
        assertEquals("SETTLED", repository.applied.get(1).getProjection().getCollectionStatus());
        assertTrue(!dedup.isIngested(525441L), "整笔结清结束催收周期，入催标记必须清除");
    }

    /** 部分还款只刷新运行态：派生 IN_COLLECTION、发 CASE_BALANCE_UPDATED，且不清除入催标记。 */
    @Test
    void repayment_partial_publishesBalanceUpdatedAndKeepsIngestedMark() {
        String caseBody = caseIngestedBody("fingerprint-12");
        processor.handleCaseEvent(JSON.parseObject(caseBody), caseBody);
        assertTrue(dedup.isIngested(525441L));
        String body = partialRepaymentBody();

        processor.handleRepaymentEvent(JSON.parseObject(body), body);

        verify(ingestionService)
                .balanceUpdated(
                        eq(525441L),
                        eq(2145521L),
                        eq(9),
                        eq(new BigDecimal("1200.00")),
                        eq(new BigDecimal("1200.00")),
                        eq(new BigDecimal("50.00")),
                        eq(new BigDecimal("900.00")),
                        eq(LocalDate.of(2026, 9, 5)),
                        eq("IN_COLLECTION"));
        verify(ingestionService, never()).repayment(anyLong(), anyLong());
        assertTrue(dedup.isIngested(525441L), "部分还款不结束催收周期，入催标记必须保留");
        assertTrue(repository.published.contains("pay-2"));
    }

    /** repaymentEvent 不带 totalOutstanding：对客金额回落为还款后的 overdueAmount，不含罚息，也不写空。 */
    @Test
    void repayment_partial_fallsBackToOverdueAmountForOutstanding() {
        String caseBody = caseIngestedBody("fingerprint-12");
        processor.handleCaseEvent(JSON.parseObject(caseBody), caseBody);
        String body = partialRepaymentBody().replace("\"pay-2\"", "\"pay-3\"");

        processor.handleRepaymentEvent(JSON.parseObject(body), body);

        CaseProjection projection = repository.applied.get(1).getProjection();
        assertEquals("IN_COLLECTION", projection.getCollectionStatus());
        assertEquals(0, new BigDecimal("1200.00").compareTo(projection.getTotalOutstanding()));
        assertEquals(0, new BigDecimal("1200.00").compareTo(projection.getOverdueAmount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(projection.getPenaltyAmount()));
        assertEquals(LocalDate.of(2026, 9, 5), projection.getNextDueDate());
        assertTrue(projection.isNextDueDatePresent());
    }

    /** dpd ≥ 91 的还款增量派生 CEASED，不得按在催刷新运行态。 */
    @Test
    void repayment_partial_beyondD91_derivesCeased() {
        String caseBody = caseIngestedBody("fingerprint-12");
        processor.handleCaseEvent(JSON.parseObject(caseBody), caseBody);
        String body =
                partialRepaymentBody()
                        .replace("\"dpd\":9,", "\"dpd\":95,")
                        .replace("\"pay-2\"", "\"pay-5\"");

        processor.handleRepaymentEvent(JSON.parseObject(body), body);

        assertEquals("CEASED", repository.applied.get(1).getProjection().getCollectionStatus());
    }

    /** 缺完整 caseEvent 基线的还款：poison + ack，不得 nack 重投（重投也补不出基线）。 */
    @Test
    void repayment_withoutBaseline_isPoisonNotRetried() {
        String body = partialRepaymentBody();

        PoisonMessageException error =
                assertThrows(
                        PoisonMessageException.class,
                        () -> processor.handleRepaymentEvent(JSON.parseObject(body), body));

        assertTrue(error.getMessage().contains("525441"));
        verifyNoInteractions(ingestionService);
        assertTrue(repository.published.isEmpty());
    }

    /** 陈旧 repaymentEvent.occurredAt：投影按 SKIPPED 收敛，不得再发余额事件。 */
    @Test
    void repayment_staleOccurredAt_skipsPublish() {
        String caseBody = caseIngestedBody("fingerprint-12");
        processor.handleCaseEvent(JSON.parseObject(caseBody), caseBody);
        String stale =
                partialRepaymentBody()
                        .replace("2026-08-13T10:06:00+08:00", "2026-08-11T10:06:00+08:00")
                        .replace("\"pay-2\"", "\"pay-stale\"");

        processor.handleRepaymentEvent(JSON.parseObject(stale), stale);

        verify(ingestionService, never())
                .balanceUpdated(
                        anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(ingestionService, never()).repayment(anyLong(), anyLong());
        assertTrue(repository.published.stream().noneMatch("pay-stale"::equals));
    }

    @Test
    void duplicateEventId_isSkippedByDedupFastPath() {
        String body = caseIngestedBody("fingerprint-12");
        processor.handleCaseEvent(JSON.parseObject(body), body);
        int appliedOnce = repository.applied.size();

        processor.handleCaseEvent(JSON.parseObject(body), body);

        assertEquals(appliedOnce, repository.applied.size());
    }

    private String partialRepaymentBody() {
        return "{\"eventId\":\"pay-2\",\"eventType\":\"REPAYMENT\","
                + "\"occurredAt\":\"2026-08-13T10:06:00+08:00\",\"caseId\":525441,"
                + "\"userId\":2145521,\"isFullCleared\":false,"
                + "\"stage\":\"S2\",\"dpd\":9,\"overdueAmount\":1200.00,"
                + "\"overduePenaltyAmount\":50.00,\"upcomingAmount\":900.00,"
                + "\"nextDueDate\":\"2026-09-05\"}";
    }

    private String caseIngestedBody(String caseVersion) {
        return "{\"eventId\":\"evt-1\","
                + "\"occurredAt\":\"2026-08-12 03:35:00\",\"caseId\":525441,"
                + "\"userId\":\"2145521\",\"caseVersion\":\""
                + caseVersion
                + "\",\"product\":\"3\",\"stage\":\"S1\",\"dpd\":2,"
                + "\"collectionStatus\":\"IN_COLLECTION\",\"totalOutstanding\":3000.00,"
                + "\"penaltyAmount\":0.00,\"remainingAmount\":9000.00,\"dueDate\":\"2026-08-11\","
                + "\"borrower\":{\"name\":\"CORA\",\"phone\":\"+639563093217\","
                + "\"email\":\"hizon@example.com\",\"language\":\"en\"},"
                + "\"device\":{\"pushToken\":\"tok-1\"}}";
    }

    /**
     * 记录调用的投影仓储替身；语义与 {@code AiCaseProjectionRepository} 对齐：按内容指纹判定 caseEvent、 还款增量要求已有基线且不接受陈旧
     * {@code updatedAt}。可用 {@link #nextOutcome} 强制某一结果。
     */
    private static final class RecordingProjectionRepository implements CaseProjectionRepository {

        private final List<CaseProjectionCommand> applied = new ArrayList<>();
        private final List<String> published = new ArrayList<>();
        private final Map<Long, String> versions = new HashMap<>();
        private final Map<Long, CaseProjection> projections = new HashMap<>();
        private Outcome nextOutcome;

        @Override
        public Outcome apply(CaseProjectionCommand command) {
            applied.add(command);
            if (nextOutcome != null) {
                return nextOutcome;
            }
            CaseProjection projection = command.getProjection();
            String current = versions.get(projection.getCaseId());
            if (current != null && current.equals(projection.getCaseVersion())) {
                return Outcome.STALE_VERSION;
            }
            versions.put(projection.getCaseId(), projection.getCaseVersion());
            projections.put(projection.getCaseId(), projection);
            return command.isPublishRequired() ? Outcome.APPLIED : Outcome.APPLIED_WITHOUT_EVENT;
        }

        @Override
        public Outcome applyRepaymentDelta(CaseProjectionCommand command) {
            CaseProjection delta = command.getProjection();
            CaseProjection baseline = projections.get(delta.getCaseId());
            if (baseline == null) {
                throw new MissingCaseBaselineException(delta.getCaseId());
            }
            applied.add(command);
            if (nextOutcome != null) {
                return nextOutcome;
            }
            if (delta.getUpdatedAt() != null
                    && baseline.getUpdatedAt() != null
                    && delta.getUpdatedAt().isBefore(baseline.getUpdatedAt())) {
                return Outcome.STALE_VERSION;
            }
            projections.put(delta.getCaseId(), delta);
            return command.isPublishRequired() ? Outcome.APPLIED : Outcome.APPLIED_WITHOUT_EVENT;
        }

        @Override
        public void markEventPublished(String eventId) {
            published.add(eventId);
        }
    }
}
