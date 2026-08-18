package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSONObject;
import com.collection.common.enums.Stage;
import com.collection.common.event.CollectionEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把数仓 {@code caseEvent} / {@code repaymentEvent} 完整快照映射为领域事件 payload（语义字段 → {@link
 * CollectionEvent} 常量 key，契约见领域模型 §6.2）。
 */
@Component
public class CasePayloadMapper {

    private static final ZoneId PHT = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter LOCAL_OCCURRED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** v2 外部完整快照（caseEvent / repaymentEvent）的统一映射结果。 */
    public static final class AiSnapshot {
        public final Long caseId;
        public final Long userId;
        public final String caseVersion;
        public final Stage stage;
        public final Map<String, Object> snapshotFields;

        AiSnapshot(
                Long caseId,
                Long userId,
                String caseVersion,
                Stage stage,
                Map<String, Object> snapshotFields) {
            this.caseId = caseId;
            this.userId = userId;
            this.caseVersion = caseVersion;
            this.stage = stage;
            this.snapshotFields = snapshotFields;
        }
    }

    /** repaymentEvent 的增量字段；不包含产品、联系人或完整快照版本。 */
    public static final class RepaymentDelta {
        public final Long caseId;
        public final Long userId;
        public final boolean fullCleared;
        public final CaseProjectionFields fields;

        RepaymentDelta(Long caseId, Long userId, boolean fullCleared, CaseProjectionFields fields) {
            this.caseId = caseId;
            this.userId = userId;
            this.fullCleared = fullCleared;
            this.fields = fields;
        }
    }

    /** 避免将增量字段与完整快照的必填约束混在一起。 */
    public static final class CaseProjectionFields {
        public Integer dpd;
        public Stage stage;
        public BigDecimal overdueAmount;
        public BigDecimal penaltyAmount;
        public BigDecimal upcomingAmount;
        public LocalDate nextDueDate;
        public boolean nextDueDatePresent;
        public java.time.LocalDateTime occurredAt;
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
        String caseVersion = trimToNull(json.getString(CollectionEvent.CASE_VERSION));
        boolean ceased = "CASE_CEASED".equals(json.getString("eventType"));
        if (caseId == null || caseVersion == null || (!ceased && userId == null)) {
            throw new PoisonMessageException("v2 payload 缺 caseId/caseVersion/userId");
        }
        Stage stage = parseStage(trimToNull(json.getString(CollectionEvent.STAGE)));
        Map<String, Object> fields = new LinkedHashMap<>();
        putRawInt(fields, json, CollectionEvent.DPD);
        putRawStr(fields, json, CollectionEvent.PRODUCT);
        BigDecimal overdueAmount = getDecimal(json, "overdueAmount");
        BigDecimal totalOutstanding = getDecimal(json, CollectionEvent.TOTAL_OUTSTANDING);
        if (totalOutstanding == null) {
            totalOutstanding = overdueAmount;
        }
        if (totalOutstanding != null) {
            fields.put(CollectionEvent.TOTAL_OUTSTANDING, totalOutstanding);
        }
        if (overdueAmount != null) {
            fields.put(CollectionEvent.OVERDUE_AMOUNT, overdueAmount);
        }
        BigDecimal penalty = getDecimal(json, "overduePenaltyAmount");
        if (penalty == null) {
            penalty = getDecimal(json, CollectionEvent.PENALTY_AMOUNT);
        }
        if (penalty != null) {
            fields.put(CollectionEvent.PENALTY_AMOUNT, penalty);
        }
        putRawDecimal(fields, json, CollectionEvent.UPCOMING_AMOUNT);
        putRawStr(fields, json, CollectionEvent.NEXT_DUE_DATE);
        putRawStr(fields, json, CollectionEvent.DUE_DATE);
        JSONObject borrower = json.getJSONObject("borrower");
        if (borrower != null) {
            putRawStr(fields, borrower, CollectionEvent.NAME);
            String phone = normalizePhilippinePhone(trimToNull(borrower.getString(CollectionEvent.PHONE)));
            if (phone != null) {
                fields.put(CollectionEvent.PHONE, phone);
            }
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

    public RepaymentDelta mapRepaymentDelta(JSONObject json) {
        Long caseId = getLong(json, CollectionEvent.CASE_ID);
        Long userId = getLong(json, CollectionEvent.USER_ID);
        Boolean fullCleared = json.getBoolean("isFullCleared");
        if (caseId == null || userId == null || fullCleared == null) {
            throw new PoisonMessageException("repaymentEvent 缺 caseId/userId/isFullCleared");
        }
        CaseProjectionFields fields = new CaseProjectionFields();
        fields.dpd = json.getInteger(CollectionEvent.DPD);
        fields.stage = parseStage(trimToNull(json.getString(CollectionEvent.STAGE)));
        fields.overdueAmount = getDecimal(json, "overdueAmount");
        fields.penaltyAmount = getDecimal(json, "overduePenaltyAmount");
        if (fields.penaltyAmount == null) {
            fields.penaltyAmount = getDecimal(json, CollectionEvent.PENALTY_AMOUNT);
        }
        fields.upcomingAmount = getDecimal(json, "upcomingAmount");
        fields.nextDueDatePresent = json.containsKey("nextDueDate");
        fields.nextDueDate =
                fields.nextDueDatePresent
                        ? parseDate(json.get("nextDueDate"), "nextDueDate")
                        : null;
        fields.occurredAt = occurredAt(json, caseId, "repaymentEvent");
        if (fields.dpd == null
                || fields.overdueAmount == null
                || fields.penaltyAmount == null
                || fields.upcomingAmount == null
                || fields.overdueAmount.signum() < 0
                || fields.penaltyAmount.signum() < 0
                || fields.upcomingAmount.signum() < 0) {
            throw new PoisonMessageException("repaymentEvent 缺有效增量金额或 dpd，caseId=" + caseId);
        }
        return new RepaymentDelta(caseId, userId, fullCleared, fields);
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
                || fields.get(CollectionEvent.PENALTY_AMOUNT) == null) {
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

    static LocalDate parseDate(Object value, String field) {
        if (value == null || (value instanceof Number && ((Number) value).longValue() == 0L)) {
            return null;
        }
        String raw = trimToNull(value.toString());
        if (raw == null || "0".equals(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            throw new PoisonMessageException("非法 " + field + "=" + raw);
        }
    }

    static LocalDateTime occurredAt(JSONObject json, Long caseId, String messageType) {
        String raw = trimToNull(json.getString("occurredAt"));
        if (raw == null) {
            throw new PoisonMessageException(messageType + " 缺 occurredAt，caseId=" + caseId);
        }
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(PHT).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw, LOCAL_OCCURRED_AT);
            } catch (DateTimeParseException e) {
                throw new PoisonMessageException("非法 occurredAt=" + raw + " caseId=" + caseId);
            }
        }
    }

    private static String normalizePhilippinePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = phone.replaceAll("[\\s()-]", "");
        if (normalized.startsWith("+")) {
            return normalized;
        }
        if (normalized.matches("63\\d{10}")) {
            return "+" + normalized;
        }
        if (normalized.matches("0\\d{10}")) {
            return "+63" + normalized.substring(1);
        }
        if (normalized.matches("9\\d{9}")) {
            return "+63" + normalized;
        }
        return normalized;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

}
