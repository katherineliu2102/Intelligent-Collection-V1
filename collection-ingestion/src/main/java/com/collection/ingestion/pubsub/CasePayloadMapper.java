package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSONObject;
import com.collection.common.enums.Stage;
import com.collection.common.event.CollectionEvent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把数仓 {@code caseEvent} / {@code repaymentEvent} 完整快照映射为领域事件 payload（语义字段 → {@link
 * CollectionEvent} 常量 key，契约见领域模型 §6.2）。
 */
@Component
public class CasePayloadMapper {

    /** v2 外部完整快照（caseEvent / repaymentEvent）的统一映射结果。 */
    public static final class AiSnapshot {
        public final Long caseId;
        public final Long userId;
        public final Long caseVersion;
        public final Stage stage;
        public final Map<String, Object> snapshotFields;

        AiSnapshot(
                Long caseId,
                Long userId,
                Long caseVersion,
                Stage stage,
                Map<String, Object> snapshotFields) {
            this.caseId = caseId;
            this.userId = userId;
            this.caseVersion = caseVersion;
            this.stage = stage;
            this.snapshotFields = snapshotFields;
        }
    }

    /** v2 稳定业务事件键，由数仓 publish 时生成；重试、重投与重放均复用同一值。 */
    public String eventId(JSONObject json) {
        return trimToNull(json.getString("eventId"));
    }

    /** v2 caseEvent / repaymentEvent 的完整案件快照。 */
    public AiSnapshot mapAiSnapshot(JSONObject json) {
        return mapAiSnapshot(json, true);
    }

    private AiSnapshot mapAiSnapshot(JSONObject json, boolean requireFinancials) {
        Long caseId = getLong(json, CollectionEvent.CASE_ID);
        Long userId = getLong(json, CollectionEvent.USER_ID);
        Long caseVersion = getLong(json, CollectionEvent.CASE_VERSION);
        boolean ceased = "CASE_CEASED".equals(json.getString("eventType"));
        if (caseId == null || caseVersion == null || caseVersion < 1 || (!ceased && userId == null)) {
            throw new PoisonMessageException("v2 payload 缺 caseId/caseVersion/userId");
        }
        Stage stage = parseStage(trimToNull(json.getString(CollectionEvent.STAGE)));
        Map<String, Object> fields = new LinkedHashMap<>();
        putRawInt(fields, json, CollectionEvent.DPD);
        putRawStr(fields, json, CollectionEvent.PRODUCT);
        putRawDecimal(fields, json, CollectionEvent.TOTAL_OUTSTANDING);
        putRawDecimal(fields, json, CollectionEvent.PENALTY_AMOUNT);
        putRawStr(fields, json, CollectionEvent.DUE_DATE);
        JSONObject borrower = json.getJSONObject("borrower");
        if (borrower != null) {
            putRawStr(fields, borrower, CollectionEvent.NAME);
            putRawStr(fields, borrower, CollectionEvent.PHONE);
            putRawStr(fields, borrower, CollectionEvent.EMAIL);
            putRawStr(fields, borrower, CollectionEvent.LANGUAGE);
        }
        JSONObject device = json.getJSONObject("device");
        if (device != null) {
            String token = trimToNull(device.getString("pushToken"));
            if (token != null) {
                fields.put(CollectionEvent.JPUSH_TOKEN, token);
            }
        }
        if (requireFinancials && !ceased) {
            requireFinancialFields(caseId, fields);
        }
        return new AiSnapshot(caseId, userId, caseVersion, stage, fields);
    }

    public boolean isFullCleared(JSONObject json) {
        Boolean value = json.getBoolean("isFullCleared");
        if (value == null) {
            throw new PoisonMessageException("repaymentEvent 缺 isFullCleared");
        }
        return value;
    }

    // ───────────────────────── helpers ─────────────────────────

    private void putRawStr(Map<String, Object> fields, JSONObject json, String key) {
        String value = trimToNull(json.getString(key));
        if (value != null) {
            fields.put(key, value);
        }
    }

    private void putRawInt(Map<String, Object> fields, JSONObject json, String key) {
        Integer value = json.getInteger(key);
        if (value != null) {
            fields.put(key, value);
        }
    }

    private void putRawDecimal(Map<String, Object> fields, JSONObject json, String key) {
        BigDecimal value = getDecimal(json, key);
        if (value != null) {
            fields.put(key, value);
        }
    }

    private Stage parseStage(String stageRaw) {
        if (stageRaw == null) {
            return null;
        }
        try {
            return Stage.valueOf(stageRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PoisonMessageException("非法 stage=" + stageRaw);
        }
    }

    private void requireFinancialFields(Long caseId, Map<String, Object> fields) {
        if (fields.get(CollectionEvent.DPD) == null
                || fields.get(CollectionEvent.PRODUCT) == null
                || fields.get(CollectionEvent.TOTAL_OUTSTANDING) == null
                || fields.get(CollectionEvent.PENALTY_AMOUNT) == null
                || fields.get(CollectionEvent.DUE_DATE) == null) {
            throw new PoisonMessageException(
                    "v3 payload missing required financial fields, caseId=" + caseId);
        }
    }

    private Long getLong(JSONObject json, String key) {
        Object v = json.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal getDecimal(JSONObject json, String key) {
        Object v = json.get(key);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

}
