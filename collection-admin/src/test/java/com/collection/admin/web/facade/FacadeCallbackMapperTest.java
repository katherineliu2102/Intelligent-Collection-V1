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
        JsonNode root =
                mapper.readTree(
                        "{\"event\":\"session.completed\",\"was_ai_connected\":true,"
                                + "\"line_outcome\":{\"reason\":\"VOICEMAIL\",\"was_answered\":true,"
                                + "\"was_ai_connected\":true}}");
        assertEquals(ContactResult.SENT_NO_RESPONSE, FacadeCallbackMapper.mapResult(root));
    }

    @Test
    void humanRequiresAiConnectedAndNormal() throws Exception {
        JsonNode human =
                mapper.readTree(
                        "{\"line_outcome\":{\"reason\":\"NORMAL\",\"was_answered\":true,"
                                + "\"was_ai_connected\":true}}");
        assertEquals(ContactResult.ANSWERED, FacadeCallbackMapper.mapResult(human));
        JsonNode normalOnly =
                mapper.readTree(
                        "{\"line_outcome\":{\"reason\":\"NORMAL\",\"was_answered\":true,"
                                + "\"was_ai_connected\":false}}");
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
