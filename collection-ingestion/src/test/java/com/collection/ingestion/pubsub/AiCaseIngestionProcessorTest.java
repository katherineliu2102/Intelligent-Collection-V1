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
import com.alibaba.fastjson.JSONObject;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.repository.CaseProjectionRepository;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link AiCaseIngestionProcessor} 纯逻辑单测：投影先于领域事件、重放与乱序收敛、每日快照静默刷新、
 * 外部阶段事件被拒。不连数据库 / GCP。
 */
class AiCaseIngestionProcessorTest {

    private RecordingProjectionRepository repository;
    private IngestionService ingestionService;
    private InMemoryIngestionDedupStore dedup;
    private AiCaseIngestionProcessor processor;

    @BeforeEach
    void setUp() {
        IngestionProperties props = new IngestionProperties();
        CasePayloadMapper mapper = new CasePayloadMapper();
        IngestionFaultInjector faultInjector = new IngestionFaultInjector();
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
    }

    @Test
    void caseIngested_writesProjectionThenPublishes() {
        String body = caseIngestedBody(12L);

        processor.handleCaseEvent(JSON.parseObject(body), body);

        CaseProjection projection = repository.applied.get(0).getProjection();
        assertEquals(525441L, projection.getCaseId());
        assertEquals(12L, projection.getCaseVersion());
        assertEquals("IN_COLLECTION", projection.getCollectionStatus());
        assertEquals(0, new BigDecimal("3000.00").compareTo(projection.getTotalOutstanding()));
        assertEquals("+639563093217", projection.getBorrowerPhone());
        assertTrue(repository.published.contains("evt-1"));
        verify(ingestionService)
                .ingestCase(eq(525441L), eq(2145521L), eq(Stage.S1), any(Map.class));
    }

    @Test
    void caseIngested_publishFailure_leavesInboxPendingForRedelivery() {
        String body = caseIngestedBody(12L);
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

        verify(ingestionService).ingestCase(eq(525441L), eq(2145521L), eq(Stage.S1), any(Map.class));
        assertTrue(repository.published.contains("evt-1"));
    }

    @Test
    void caseIngested_staleVersion_skipsPublish() {
        repository.nextOutcome = CaseProjectionRepository.Outcome.STALE_VERSION;
        String body = caseIngestedBody(11L);

        processor.handleCaseEvent(JSON.parseObject(body), body);

        verifyNoInteractions(ingestionService);
        assertTrue(repository.published.isEmpty());
    }

    @Test
    void externalStageEvent_isRejectedAsPoison() {
        String body = caseIngestedBody(12L).replace("CASE_INGESTED", "CASE_STAGE_CHANGED");

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
        String initial = caseIngestedBody(12L);
        processor.handleCaseEvent(JSON.parseObject(initial), initial);
        String refresh = caseIngestedBody(20L).replace("\"evt-1\"", "\"evt-2\"");

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
        String body =
                "{\"eventId\":\"pay-1\",\"eventType\":\"REPAYMENT\","
                        + "\"occurredAt\":\"2026-08-12T10:06:00+08:00\",\"caseId\":\"525441\","
                        + "\"userId\":\"2145521\",\"caseVersion\":13,\"isFullCleared\":true,"
                        + "\"product\":\"3\",\"stage\":\"S1\",\"dpd\":2,\"collectionStatus\":\"SETTLED\","
                        + "\"totalOutstanding\":0.00,\"penaltyAmount\":0.00,\"remainingAmount\":0.00,"
                        + "\"dueDate\":\"2026-08-11\","
                        + "\"borrower\":{\"name\":\"CORA\",\"phone\":\"+639563093217\",\"language\":\"en\"}}";

        processor.handleRepaymentEvent(JSON.parseObject(body), body);

        verify(ingestionService).repayment(525441L, 2145521L);
        assertTrue(repository.published.contains("pay-1"));
        assertEquals(
                "SETTLED", repository.applied.get(0).getProjection().getCollectionStatus());
    }

    @Test
    void duplicateEventId_isSkippedByDedupFastPath() {
        String body = caseIngestedBody(12L);
        processor.handleCaseEvent(JSON.parseObject(body), body);
        int appliedOnce = repository.applied.size();

        processor.handleCaseEvent(JSON.parseObject(body), body);

        assertEquals(appliedOnce, repository.applied.size());
    }

    private String caseIngestedBody(long caseVersion) {
        return "{\"eventId\":\"evt-1\",\"eventType\":\"CASE_INGESTED\","
                + "\"occurredAt\":\"2026-08-12T03:35:00+08:00\",\"caseId\":\"525441\","
                + "\"userId\":\"2145521\",\"caseVersion\":"
                + caseVersion
                + ",\"product\":\"3\",\"stage\":\"S1\",\"dpd\":2,"
                + "\"collectionStatus\":\"IN_COLLECTION\",\"totalOutstanding\":3000.00,"
                + "\"penaltyAmount\":0.00,\"remainingAmount\":9000.00,\"dueDate\":\"2026-08-11\","
                + "\"borrower\":{\"name\":\"CORA\",\"phone\":\"+639563093217\","
                + "\"email\":\"hizon@example.com\",\"language\":\"en\"},"
                + "\"device\":{\"pushToken\":\"tok-1\"}}";
    }

    /** 记录调用的投影仓储替身；默认按版本水位判定，可用 {@link #nextOutcome} 强制某一结果。 */
    private static final class RecordingProjectionRepository implements CaseProjectionRepository {

        private final List<CaseProjectionCommand> applied = new ArrayList<>();
        private final List<String> published = new ArrayList<>();
        private final Map<Long, Long> versions = new HashMap<>();
        private Outcome nextOutcome;

        @Override
        public Outcome apply(CaseProjectionCommand command) {
            applied.add(command);
            if (nextOutcome != null) {
                return nextOutcome;
            }
            CaseProjection projection = command.getProjection();
            Long current = versions.get(projection.getCaseId());
            if (current != null && current >= projection.getCaseVersion()) {
                return Outcome.STALE_VERSION;
            }
            versions.put(projection.getCaseId(), projection.getCaseVersion());
            return command.isPublishRequired() ? Outcome.APPLIED : Outcome.APPLIED_WITHOUT_EVENT;
        }

        @Override
        public void markEventPublished(String eventId) {
            published.add(eventId);
        }
    }
}
