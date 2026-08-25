package com.collection.admin.web.facade;

import com.collection.common.enums.ContactResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

/** 交接 §2 / §3：从 Facade session.completed 取出身份并映射为 ContactResult。 */
public final class FacadeCallbackMapper {

    private FacadeCallbackMapper() {}

    public static final class Identity {
        public final Long planId;
        public final Long stepId;
        public final Long caseId;
        public final String sessionId;
        public final String batchId;

        public Identity(Long planId, Long stepId, Long caseId, String sessionId, String batchId) {
            this.planId = planId;
            this.stepId = stepId;
            this.caseId = caseId;
            this.sessionId = sessionId;
            this.batchId = batchId;
        }

        public boolean hasPlanAndStep() {
            return planId != null && stepId != null;
        }
    }

    public static Identity identity(JsonNode root) {
        JsonNode meta = root.path("client_metadata");
        Long planId = longValue(meta, "plan_id");
        Long stepId = longValue(meta, "step_id");
        Long caseId = longValue(meta, "case_id");
        if (caseId == null) {
            caseId = parseLong(text(root, "external_case_id"));
        }
        String sessionId = text(root, "session_id");
        String batchId = firstNonBlank(text(root, "batch_id"), text(root, "external_batch_id"));
        return new Identity(planId, stepId, caseId, sessionId, batchId);
    }

    public static ContactResult mapResult(JsonNode root) {
        JsonNode line = root.path("line_outcome");
        String reason = text(line, "reason");
        boolean answered = bool(line, "was_answered");
        boolean aiConnected = bool(line, "was_ai_connected");
        String failure = text(root, "final_failure_reason");

        if ("VOICEMAIL".equals(reason) && answered) {
            return ContactResult.SENT_NO_RESPONSE;
        }
        if ("CALL_SCREENING".equals(reason) && answered) {
            return ContactResult.SENT_NO_RESPONSE;
        }
        if (aiConnected && "NORMAL".equals(reason)) {
            return ContactResult.ANSWERED;
        }
        String code = firstNonBlank(failure, reason);
        if ("NO_ANSWER".equals(code)) {
            return ContactResult.NO_ANSWER;
        }
        if ("BUSY".equals(code)) {
            return ContactResult.BUSY;
        }
        if ("DECLINE".equals(code)) {
            return ContactResult.REJECTED;
        }
        return ContactResult.FAILED;
    }

    public static boolean isSessionCompleted(JsonNode root) {
        return "session.completed".equals(text(root, "event"));
    }

    public static boolean isBatchCompleted(JsonNode root) {
        return "batch.completed".equals(text(root, "event"));
    }

    static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        String s = v.asText();
        return StringUtils.isBlank(s) ? null : s;
    }

    private static boolean bool(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return false;
        }
        JsonNode v = node.get(field);
        return v != null && v.isBoolean() && v.booleanValue();
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isNumber()) {
            return v.longValue();
        }
        return parseLong(v.asText());
    }

    static Long parseLong(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.isNotBlank(a)) {
            return a;
        }
        if (StringUtils.isNotBlank(b)) {
            return b;
        }
        return null;
    }
}
