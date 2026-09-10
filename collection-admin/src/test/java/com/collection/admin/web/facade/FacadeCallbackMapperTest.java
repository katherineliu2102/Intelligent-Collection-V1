package com.collection.admin.web.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.collection.common.enums.ContactResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FacadeCallbackMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void voicemailIsSentNoResponseNotAnswered() throws Exception {
        JsonNode byParty =
                mapper.readTree(
                        "{\"event\":\"session.completed\","
                                + "\"line_outcome\":{\"reason\":\"VOICEMAIL\",\"was_answered\":true,"
                                + "\"party\":\"voicemail\"}}");
        assertEquals(ContactResult.SENT_NO_RESPONSE, FacadeCallbackMapper.mapResult(byParty));
        JsonNode byReason =
                mapper.readTree(
                        "{\"line_outcome\":{\"reason\":\"VOICEMAIL\",\"was_answered\":true}}");
        assertEquals(ContactResult.SENT_NO_RESPONSE, FacadeCallbackMapper.mapResult(byReason));
    }

    @Test
    void callScreeningIsSentNoResponse() throws Exception {
        JsonNode root =
                mapper.readTree(
                        "{\"line_outcome\":{\"party\":\"call_screening\",\"was_answered\":true,"
                                + "\"reason\":\"CALL_SCREENING\"}}");
        assertEquals(ContactResult.SENT_NO_RESPONSE, FacadeCallbackMapper.mapResult(root));
    }

    @Test
    void humanRequiresEffectiveConversation() throws Exception {
        JsonNode talked =
                mapper.readTree(
                        "{\"line_outcome\":{\"party\":\"human\",\"reason\":\"NORMAL\","
                                + "\"was_answered\":true},"
                                + "\"ai_result\":{\"effective_conversation\":true,"
                                + "\"disposition\":\"promise_to_pay\"}}");
        assertEquals(ContactResult.ANSWERED, FacadeCallbackMapper.mapResult(talked));
        JsonNode silent =
                mapper.readTree(
                        "{\"line_outcome\":{\"party\":\"human\",\"reason\":\"NORMAL\","
                                + "\"was_answered\":true},"
                                + "\"ai_result\":{\"effective_conversation\":false}}");
        assertEquals(ContactResult.SENT_NO_RESPONSE, FacadeCallbackMapper.mapResult(silent));
        JsonNode normalOnly =
                mapper.readTree(
                        "{\"line_outcome\":{\"reason\":\"NORMAL\",\"was_answered\":true,"
                                + "\"was_ai_connected\":true}}");
        assertEquals(ContactResult.FAILED, FacadeCallbackMapper.mapResult(normalOnly));
    }

    @Test
    void busyAndUnknown() throws Exception {
        assertEquals(
                ContactResult.BUSY,
                FacadeCallbackMapper.mapResult(
                        mapper.readTree("{\"final_failure_reason\":\"BUSY\"}")));
        assertEquals(
                ContactResult.FAILED,
                FacadeCallbackMapper.mapResult(
                        mapper.readTree(
                                "{\"final_failure_reason\":\"MEDIA_NEGOTIATION_FAILED\"}")));
        assertEquals(
                ContactResult.REJECTED,
                FacadeCallbackMapper.mapResult(
                        mapper.readTree("{\"line_outcome\":{\"reason\":\"DECLINE\"}}")));
    }

    @Test
    void identityFromMetadata() throws Exception {
        JsonNode root =
                mapper.readTree(
                        "{\"session_id\":\"s1\",\"batch_id\":\"b1\",\"external_case_id\":\"9\","
                                + "\"client_metadata\":{\"plan_id\":11,\"step_id\":\"22\",\"case_id\":9}}");
        FacadeCallbackMapper.Identity id = FacadeCallbackMapper.identity(root);
        assertEquals(11L, id.planId.longValue());
        assertEquals(22L, id.stepId.longValue());
        assertEquals(9L, id.caseId.longValue());
        assertEquals("s1", id.sessionId);
        assertEquals("b1", id.batchId);
        assertTrue(id.hasPlanAndStep());
    }

    @Test
    void identityFallsBackToExternalCaseId() throws Exception {
        JsonNode root = mapper.readTree("{\"external_case_id\":\"528834\",\"session_id\":\"s\"}");
        FacadeCallbackMapper.Identity id = FacadeCallbackMapper.identity(root);
        assertEquals(528834L, id.caseId.longValue());
        assertNull(id.planId);
        assertFalse(id.hasPlanAndStep());
    }
}
