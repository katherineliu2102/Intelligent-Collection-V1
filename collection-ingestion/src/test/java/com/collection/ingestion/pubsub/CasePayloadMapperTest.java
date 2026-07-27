package com.collection.ingestion.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.collection.common.enums.Stage;
import com.collection.common.event.CollectionEvent;
import com.collection.ingestion.config.IngestionProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** {@link CasePayloadMapper} 纯逻辑单测（不连 GCP / Spring 上下文）。 */
class CasePayloadMapperTest {

    private IngestionProperties props;
    private CasePayloadMapper mapper;

    @BeforeEach
    void setUp() {
        props = new IngestionProperties();
        mapper = new CasePayloadMapper();
        ReflectionTestUtils.setField(mapper, "props", props);
    }

    @Test
    void mapCasePush_byContractKeys_buildsSnapshotFieldsWithCleaning() {
        JSONObject json =
                JSON.parseObject(
                        "{\"caseId\":99000001,\"userId\":12345,\"stage\":\"S2\",\"dpd\":20,"
                                + "\"product\":\"SKYPAYLOANS\",\"totalOutstanding\":1500.50,"
                                + "\"penaltyAmount\":120,\"phone\":\"09171234567\","
                                + "\"email\":\"a@b.com\",\"jpushToken\":\"tok-1\"}");

        CasePayloadMapper.CaseIngest ci = mapper.mapCasePush(json);

        assertEquals(99000001L, ci.caseId);
        assertEquals(12345L, ci.userId);
        assertEquals(Stage.S2, ci.stage);
        assertEquals(20, ci.snapshotFields.get(CollectionEvent.DPD));
        assertEquals("SKYPAYLOANS", ci.snapshotFields.get(CollectionEvent.PRODUCT));
        assertEquals(
                0, new BigDecimal("1500.50").compareTo((BigDecimal) ci.snapshotFields.get(CollectionEvent.TOTAL_OUTSTANDING)));
        assertEquals("+639171234567", ci.snapshotFields.get(CollectionEvent.PHONE));
        assertEquals("a@b.com", ci.snapshotFields.get(CollectionEvent.EMAIL));
        assertEquals("tok-1", ci.snapshotFields.get(CollectionEvent.JPUSH_TOKEN));
    }

    @Test
    void mapCasePush_dirtyEmail_isDropped() {
        JSONObject json = JSON.parseObject("{\"caseId\":1,\"email\":\"0\",\"phone\":\"+639998887777\"}");
        CasePayloadMapper.CaseIngest ci = mapper.mapCasePush(json);
        assertFalse(ci.snapshotFields.containsKey(CollectionEvent.EMAIL));
        assertEquals("+639998887777", ci.snapshotFields.get(CollectionEvent.PHONE));
    }

    @Test
    void mapCasePush_missingCaseId_throwsPoison() {
        JSONObject json = JSON.parseObject("{\"stage\":\"S1\"}");
        assertThrows(PoisonMessageException.class, () -> mapper.mapCasePush(json));
    }

    @Test
    void mapCasePush_invalidStage_fallsBackToNull() {
        JSONObject json = JSON.parseObject("{\"caseId\":1,\"stage\":\"BOGUS\"}");
        assertNull(mapper.mapCasePush(json).stage);
    }

    @Test
    void fieldMap_aliasUpstreamKeys() {
        props.getCasePush().getFieldMap().put(CollectionEvent.CASE_ID, "loan_id");
        props.getCasePush().getFieldMap().put(CollectionEvent.DPD, "overdue_days");
        JSONObject json = JSON.parseObject("{\"loan_id\":\"99000009\",\"overdue_days\":45}");

        CasePayloadMapper.CaseIngest ci = mapper.mapCasePush(json);
        assertEquals(99000009L, ci.caseId);
        assertEquals(45, ci.snapshotFields.get(CollectionEvent.DPD));
    }

    @Test
    void dataType_prefersAttributeOverJson() {
        JSONObject json = JSON.parseObject("{\"dataType\":\"case_push\"}");
        assertEquals("repayment_push_and_load", mapper.dataType(json, "repayment_push_and_load"));
        assertEquals("case_push", mapper.dataType(json, null));
    }

    @Test
    void repayment_keysAreContractNamed_notViaFieldMap() {
        // 即使配了 case_push field-map（userId→userID / caseId→loanID），repayment 仍按真实小写键读。
        props.getCasePush().getFieldMap().put(CollectionEvent.USER_ID, "userID");
        props.getCasePush().getFieldMap().put(CollectionEvent.CASE_ID, "loanID");
        JSONObject json =
                JSON.parseObject(
                        "{\"userId\":777,\"loanId\":88,\"fullRepayTime\":\"2026-07-01 10:00:00\",\"STATUS\":1,\"overdue\":0.0}");
        assertEquals(777L, mapper.repaymentUserId(json));
        assertEquals(88L, mapper.repaymentLoanId(json));
    }

    @Test
    void fullySettled_byFullRepayTimeOrStatus4() {
        // 样例：STATUS=1（待还款）但 fullRepayTime 非空 → 结清（靠 fullRepayTime 命中）
        assertTrue(
                mapper.fullySettled(
                        JSON.parseObject("{\"userId\":1,\"fullRepayTime\":\"2026-07-01 10:00:00\",\"STATUS\":1}")));
        // STATUS=4（结清）无 fullRepayTime → 结清
        assertTrue(mapper.fullySettled(JSON.parseObject("{\"userId\":1,\"STATUS\":4}")));
        // STATUS=2（逾期）无 fullRepayTime → 未结清
        assertFalse(mapper.fullySettled(JSON.parseObject("{\"userId\":1,\"STATUS\":2}")));
    }

    @Test
    void repaymentUserId_missing_throwsPoison() {
        assertThrows(
                PoisonMessageException.class,
                () -> mapper.repaymentUserId(JSON.parseObject("{\"loanId\":1}")));
    }
}
