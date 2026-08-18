package com.collection.ingestion.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.collection.common.enums.Stage;
import com.collection.common.event.CollectionEvent;
import org.junit.jupiter.api.Test;

/** {@link CasePayloadMapper} 的 v3 完整快照映射测试。 */
class CasePayloadMapperTest {

    private final CasePayloadMapper mapper = new CasePayloadMapper();

    @Test
    void mapAiSnapshot_readsNestedCaseEvent() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-1\",\"caseId\":\"525441\",\"userId\":\"2145521\","
                                + "\"caseVersion\":\"7c1e2a9b4d0f83a15e6b8c2d9f0a4e31\",\"stage\":\"S0\",\"dpd\":-2,\"product\":\"3\","
                                + "\"totalOutstanding\":0,\"penaltyAmount\":0,\"dueDate\":\"2026-08-11\","
                                + "\"borrower\":{\"name\":\"Cora\",\"phone\":\"+639563093217\","
                                + "\"email\":\"cora@example.com\",\"language\":\"en\"},"
                                + "\"device\":{\"pushToken\":\"push-1\"}}");

        CasePayloadMapper.AiSnapshot snapshot = mapper.mapAiSnapshot(json);

        assertEquals(525441L, snapshot.caseId);
        assertEquals(2145521L, snapshot.userId);
        assertEquals("7c1e2a9b4d0f83a15e6b8c2d9f0a4e31", snapshot.caseVersion);
        assertEquals(Stage.S0, snapshot.stage);
        assertEquals("Cora", snapshot.snapshotFields.get(CollectionEvent.NAME));
        assertEquals("+639563093217", snapshot.snapshotFields.get(CollectionEvent.PHONE));
        assertEquals("push-1", snapshot.snapshotFields.get(CollectionEvent.JPUSH_TOKEN));
        assertEquals("evt-1", mapper.eventId(json));
    }

    @Test
    void snapshotWithMissingFinancialField_isPoison() {
        JSONObject json =
                JSON.parseObject(
                        "{\"caseId\":\"525441\",\"userId\":\"2145521\",\"caseVersion\":\"7c1e2a9b4d0f83a15e6b8c2d9f0a4e31\","
                                + "\"stage\":\"S0\",\"dpd\":-2,\"product\":\"3\","
                                + "\"totalOutstanding\":0}");

        assertThrows(PoisonMessageException.class, () -> mapper.mapAiSnapshot(json));
    }

    @Test
    void repaymentRequiresExplicitFullClearSignal() {
        assertTrue(mapper.isFullCleared(JSON.parseObject("{\"isFullCleared\":true}")));
        assertFalse(mapper.isFullCleared(JSON.parseObject("{\"isFullCleared\":false}")));
        assertThrows(
                PoisonMessageException.class,
                () -> mapper.isFullCleared(JSON.parseObject("{}")));
    }

    @Test
    void mapAiSnapshot_acceptsProducerNumericIdsAndLocalOccurredAt() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-2\",\"caseId\":483877,\"userId\":3780028,"
                                + "\"caseVersion\":\"a48911a34fecdd3074f1acc8634d5043\","
                                + "\"occurredAt\":\"2026-08-17 10:40:12\",\"stage\":\"S4\",\"dpd\":70,"
                                + "\"product\":\"3\",\"overdueAmount\":6075.2,\"overduePenaltyAmount\":390.5,"
                                + "\"upcomingAmount\":0,\"nextDueDate\":0,"
                                + "\"borrower\":{\"phone\":\"9654453072\"}}");

        CasePayloadMapper.AiSnapshot snapshot = mapper.mapAiSnapshot(json);

        assertEquals(483877L, snapshot.caseId);
        assertEquals(3780028L, snapshot.userId);
        assertEquals("+639654453072", snapshot.snapshotFields.get(CollectionEvent.PHONE));
        assertEquals(
                java.time.LocalDateTime.of(2026, 8, 17, 10, 40, 12),
                CasePayloadMapper.occurredAt(json, snapshot.caseId, "caseEvent"));
        assertEquals(null, CasePayloadMapper.parseDate(json.get("nextDueDate"), "nextDueDate"));
    }
}
