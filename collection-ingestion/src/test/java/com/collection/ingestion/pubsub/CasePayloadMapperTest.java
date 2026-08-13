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
                                + "\"caseVersion\":7,\"stage\":\"S0\",\"dpd\":-2,\"product\":\"3\","
                                + "\"totalOutstanding\":0,\"penaltyAmount\":0,\"dueDate\":\"2026-08-11\","
                                + "\"borrower\":{\"name\":\"Cora\",\"phone\":\"+639563093217\","
                                + "\"email\":\"cora@example.com\",\"language\":\"en\"},"
                                + "\"device\":{\"pushToken\":\"push-1\"}}");

        CasePayloadMapper.AiSnapshot snapshot = mapper.mapAiSnapshot(json);

        assertEquals(525441L, snapshot.caseId);
        assertEquals(2145521L, snapshot.userId);
        assertEquals(7L, snapshot.caseVersion);
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
                        "{\"caseId\":\"525441\",\"userId\":\"2145521\",\"caseVersion\":7,"
                                + "\"stage\":\"S0\",\"dpd\":-2,\"product\":\"3\","
                                + "\"totalOutstanding\":0,\"penaltyAmount\":0}");

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
}
