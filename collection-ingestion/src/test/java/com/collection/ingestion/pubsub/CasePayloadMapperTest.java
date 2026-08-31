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
    void missingDueDate_isDerivedFromOccurredAtMinusDpd() {
        // 生产 caseEvent 的真实形态：交付契约字段表里没有 dueDate，上游不下发。
        // 缺锚点则 dayBlocks 排不出任何槽位，全量案件建不出计划，故必须反推。
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-2\",\"caseId\":\"506916\",\"userId\":\"2145522\","
                                + "\"caseVersion\":\"v1\",\"stage\":\"S3\",\"dpd\":25,\"product\":\"3\","
                                + "\"totalOutstanding\":8298.25,\"penaltyAmount\":0,"
                                + "\"occurredAt\":\"2026-08-22 02:00:40\"}");

        CasePayloadMapper.AiSnapshot snapshot = mapper.mapAiSnapshot(json);

        assertEquals("2026-07-28", snapshot.snapshotFields.get(CollectionEvent.DUE_DATE));
    }

    @Test
    void explicitDueDate_winsOverDerivation() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-3\",\"caseId\":\"506916\",\"userId\":\"2145522\","
                                + "\"caseVersion\":\"v1\",\"stage\":\"S3\",\"dpd\":25,\"product\":\"3\","
                                + "\"totalOutstanding\":8298.25,\"penaltyAmount\":0,"
                                + "\"dueDate\":\"2026-07-01\",\"occurredAt\":\"2026-08-22 02:00:40\"}");

        CasePayloadMapper.AiSnapshot snapshot = mapper.mapAiSnapshot(json);

        assertEquals("2026-07-01", snapshot.snapshotFields.get(CollectionEvent.DUE_DATE));
    }

    @Test
    void missingOccurredAt_leavesDueDateAbsentRatherThanGuessing() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-4\",\"caseId\":\"506916\",\"userId\":\"2145522\","
                                + "\"caseVersion\":\"v1\",\"stage\":\"S3\",\"dpd\":25,\"product\":\"3\","
                                + "\"totalOutstanding\":8298.25,\"penaltyAmount\":0}");

        CasePayloadMapper.AiSnapshot snapshot = mapper.mapAiSnapshot(json);

        assertFalse(snapshot.snapshotFields.containsKey(CollectionEvent.DUE_DATE));
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
                PoisonMessageException.class, () -> mapper.isFullCleared(JSON.parseObject("{}")));
    }

    @Test
    void repaymentAllowsNullUpcomingAmountWhenNoNextInstallmentReminderExists() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-repayment-1\",\"eventType\":\"REPAYMENT\","
                                + "\"occurredAt\":\"2026-08-11T10:06:00+08:00\","
                                + "\"caseId\":\"525441\",\"userId\":\"2145521\","
                                + "\"repayTime\":\"2026-08-11T10:00:00+08:00\","
                                + "\"paidAmount\":3000.0,\"overdueAmount\":6061.14,"
                                + "\"overduePenaltyAmount\":0.0,\"dpd\":34,\"stage\":\"S4\","
                                + "\"upcomingAmount\":null,\"nextDueDate\":null,\"isFullCleared\":false}");

        CasePayloadMapper.RepaymentDelta delta = mapper.mapRepaymentDelta(json);

        assertEquals(null, delta.fields.upcomingAmount);
        assertTrue(delta.fields.nextDueDatePresent);
        assertEquals(null, delta.fields.nextDueDate);
    }

    /** 还款增量同步 {@code stage}：与 dpd 同源同刻，避免投影出现「stage 来自入案、dpd 来自还款」的矛盾组合。 */
    @Test
    void repaymentSyncsStageAlongsideDpd() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-repayment-3\",\"eventType\":\"REPAYMENT\","
                                + "\"occurredAt\":\"2026-08-11T10:06:00+08:00\","
                                + "\"caseId\":\"525441\",\"userId\":\"2145521\","
                                + "\"overdueAmount\":0.0,\"overduePenaltyAmount\":0.0,\"dpd\":66,"
                                + "\"stage\":\"S4\",\"isFullCleared\":true}");

        CasePayloadMapper.RepaymentDelta delta = mapper.mapRepaymentDelta(json);

        assertEquals(Stage.S4, delta.fields.stage);
        assertTrue(delta.fields.stagePresent);
        assertEquals(66, delta.fields.dpd);
    }

    /** 提前还清：数仓给出负 dpd 与显式 {@code stage:null}，代表「不属任何催收阶段」，必须与「没给该字段」区分开。 */
    @Test
    void explicitNullStageIsCarriedAsPresentSoBaselineCanBeCleared() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-repayment-4\",\"eventType\":\"REPAYMENT\","
                                + "\"occurredAt\":\"2026-08-11T10:06:00+08:00\","
                                + "\"caseId\":\"525441\",\"userId\":\"2145521\","
                                + "\"overdueAmount\":0.0,\"overduePenaltyAmount\":0.0,\"dpd\":-5,"
                                + "\"stage\":null,\"isFullCleared\":true}");

        CasePayloadMapper.RepaymentDelta delta = mapper.mapRepaymentDelta(json);

        assertEquals(null, delta.fields.stage);
        assertTrue(delta.fields.stagePresent, "显式 null 必须算 present，否则永远清不掉基线阶段");
        assertEquals(-5, delta.fields.dpd);
    }

    /** 未携带 stage 字段：保持基线，不得被当成显式 null 而清空。 */
    @Test
    void missingStageKeyLeavesBaselineUntouched() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-repayment-5\",\"eventType\":\"REPAYMENT\","
                                + "\"occurredAt\":\"2026-08-11T10:06:00+08:00\","
                                + "\"caseId\":\"525441\",\"userId\":\"2145521\","
                                + "\"overdueAmount\":0.0,\"overduePenaltyAmount\":0.0,\"dpd\":-5,"
                                + "\"isFullCleared\":true}");

        CasePayloadMapper.RepaymentDelta delta = mapper.mapRepaymentDelta(json);

        assertEquals(null, delta.fields.stage);
        assertFalse(delta.fields.stagePresent, "字段缺失不等于阶段为空，必须保持基线");
    }

    /** 非法 {@code stage} 只跳过该字段，不得 poison 掉整笔还款——丢还款意味着已还清的客户继续被催， 代价远高于 stage 晚一个日切才纠正。 */
    @Test
    void repaymentKeepsIllegalStageFromDroppingTheWholePayment() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-repayment-2\",\"eventType\":\"REPAYMENT\","
                                + "\"occurredAt\":\"2026-08-11T10:06:00+08:00\","
                                + "\"caseId\":\"525441\",\"userId\":\"2145521\","
                                + "\"overdueAmount\":0.0,\"overduePenaltyAmount\":0.0,\"dpd\":-1,"
                                + "\"stage\":\"NOT_A_STAGE\",\"isFullCleared\":true}");

        CasePayloadMapper.RepaymentDelta delta = mapper.mapRepaymentDelta(json);

        assertTrue(delta.fullCleared);
        assertEquals(-1, delta.fields.dpd);
        assertEquals(null, delta.fields.stage, "非法取值降级为不同步，保持基线");
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

    @Test
    void parseDate_acceptsIsoDateAndTimestamp() {
        assertEquals(
                java.time.LocalDate.of(2026, 9, 1),
                CasePayloadMapper.parseDate("2026-09-01", "dueDate"));
        assertEquals(
                java.time.LocalDate.of(2026, 9, 1),
                CasePayloadMapper.parseDate("2026-09-01T00:00:00.000", "dueDate"));
        assertEquals(
                java.time.LocalDate.of(2026, 9, 1),
                CasePayloadMapper.parseDate("2026-09-01 00:00:00", "nextDueDate"));
        assertEquals(null, CasePayloadMapper.parseDate(0, "nextDueDate"));
        assertEquals(null, CasePayloadMapper.parseDate("0", "nextDueDate"));
        PoisonMessageException poison =
                assertThrows(
                        PoisonMessageException.class,
                        () -> CasePayloadMapper.parseDate("not-a-date", "dueDate"));
        assertTrue(poison.getMessage().contains("dueDate"));
    }

    @Test
    void mapAiSnapshot_s0ReminderWithTimestampDueDate_isNotPoison() {
        JSONObject json =
                JSON.parseObject(
                        "{\"eventId\":\"evt-s0\",\"caseId\":519673,\"userId\":1,"
                                + "\"caseVersion\":\"abc\",\"occurredAt\":\"2026-08-31 03:00:02\","
                                + "\"stage\":\"S0\",\"dpd\":-1,\"product\":\"3\","
                                + "\"overdueAmount\":0,\"overduePenaltyAmount\":0,\"upcomingAmount\":1800,"
                                + "\"dueDate\":\"2026-09-01T00:00:00.000\",\"nextDueDate\":\"2026-09-01T00:00:00.000\","
                                + "\"borrower\":{\"phone\":\"9654453072\"}}");

        CasePayloadMapper.AiSnapshot snapshot = mapper.mapAiSnapshot(json);
        CaseProjectionAssembler assembler = new CaseProjectionAssembler();
        com.collection.common.model.CaseProjection projection = assembler.assemble(json, snapshot);

        assertEquals(java.time.LocalDate.of(2026, 9, 1), projection.getDueDate());
        assertEquals(java.time.LocalDate.of(2026, 9, 1), projection.getNextDueDate());
        assertEquals(new java.math.BigDecimal("1800"), projection.getUpcomingAmount());
    }
}
