package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSONObject;
import com.collection.common.enums.Stage;
import com.collection.common.event.CollectionEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把数仓 {@code caseEvent} / {@code repaymentEvent} 完整快照映射为领域事件 payload（语义字段 → {@link CollectionEvent}
 * 常量 key，契约见领域模型 §6.2）。
 */
@Slf4j
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

    /**
     * 避免将增量字段与完整快照的必填约束混在一起。
     *
     * <p>{@code stage} 随还款同步：还款会改变未结清的到期期次，数仓在 `repaymentEvent` 里给出的 stage 与 dpd 是同一时刻的口径， 只取 dpd
     * 不取 stage 会让投影出现「stage 来自入案、dpd 来自还款」的自相矛盾组合。
     *
     * <p>但解析必须宽松：非法取值只跳过本字段，不能抛 poison。为一个字段丢掉真实还款，会让已还清的 客户继续被催，代价远高于 stage 晚一个日切才纠正。
     */
    public static final class CaseProjectionFields {
        public Integer dpd;
        public Stage stage;
        /** 事件显式携带 stage 且取值合法（含显式 null）时为 true；非法取值按「未携带」处理以保基线。 */
        public boolean stagePresent;

        public BigDecimal overdueAmount;
        public BigDecimal penaltyAmount;
        public BigDecimal upcomingAmount;
        public LocalDate nextDueDate;
        public boolean nextDueDatePresent;
        public java.time.LocalDateTime occurredAt;
        /** 本次还款金额（repaymentEvent.paidAmount）；未提供时可为空。 */
        public BigDecimal paidAmount;
        /** 还款发生时间（repaymentEvent.repayTime，PHT）；缺省回退 occurredAt。 */
        public java.time.LocalDateTime repayTime;
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
        if (!fields.containsKey(CollectionEvent.DUE_DATE)) {
            LocalDate derived = deriveDueDate(json);
            if (derived != null) {
                fields.put(CollectionEvent.DUE_DATE, derived.toString());
            }
        }
        JSONObject borrower = json.getJSONObject("borrower");
        if (borrower != null) {
            putRawStr(fields, borrower, CollectionEvent.NAME);
            String phone =
                    normalizePhilippinePhone(trimToNull(borrower.getString(CollectionEvent.PHONE)));
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
        if (json.containsKey(CollectionEvent.STAGE)) {
            String raw = trimToNull(json.getString(CollectionEvent.STAGE));
            fields.stage = raw == null ? null : parseStageLenient(raw, caseId);
            // 非法取值时 parseStageLenient 返回 null，但那是「解析失败」不是「阶段为空」，
            // 不能当作 present——否则一个错字就会把基线阶段清掉。
            fields.stagePresent = raw == null || fields.stage != null;
        }
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
        fields.paidAmount = getDecimal(json, "paidAmount");
        fields.repayTime = repayTimeOrFallback(json, fields.occurredAt);
        if (fields.dpd == null
                || fields.overdueAmount == null
                || fields.penaltyAmount == null
                || fields.overdueAmount.signum() < 0
                || fields.penaltyAmount.signum() < 0
                || (fields.upcomingAmount != null && fields.upcomingAmount.signum() < 0)) {
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

    /** 还款增量专用：非法 stage 只跳过本字段并留痕，不得升级为 poison 而丢掉整笔还款。 */
    private Stage parseStageLenient(String stageRaw, Long caseId) {
        String trimmed = trimToNull(stageRaw);
        if (trimmed == null) {
            return null;
        }
        try {
            return Stage.valueOf(trimmed.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn(
                    "[Ingestion] caseId={} repaymentEvent 非法 stage={}，本次不同步阶段，其余还款字段照常入账",
                    caseId,
                    trimmed);
            return null;
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

    /**
     * 契约是 {@code yyyy-MM-dd}；数仓 ADS 源表若是 TIMESTAMP，会带成 {@code 2026-09-01T00:00:00.000}。 日历日含义不变时取前
     * 10 位，真正乱码才毒丸（毒丸 ACK 不进 inbox / 投影 / DLQ，会静默丢案）。
     */
    static LocalDate parseDate(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate();
        }
        if (value instanceof java.util.Date) {
            return Instant.ofEpochMilli(((java.util.Date) value).getTime())
                    .atZone(PHT)
                    .toLocalDate();
        }
        if (value instanceof Number && ((Number) value).longValue() == 0L) {
            return null;
        }
        String raw = trimToNull(value.toString());
        if (raw == null || "0".equals(raw)) {
            return null;
        }
        if (raw.length() >= 10) {
            String head = raw.substring(0, 10);
            if (looksLikeIsoDate(head)
                    && (raw.length() == 10 || isDateTimeSeparator(raw.charAt(10)))) {
                try {
                    return LocalDate.parse(head);
                } catch (DateTimeParseException ignored) {
                    // fall through
                }
            }
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new PoisonMessageException("非法 " + field + "=" + raw);
        }
    }

    private static boolean looksLikeIsoDate(String head) {
        return head.length() == 10 && head.charAt(4) == '-' && head.charAt(7) == '-';
    }

    private static boolean isDateTimeSeparator(char c) {
        return c == 'T' || c == ' ' || c == '+' || c == 'Z';
    }

    /**
     * 缺 dueDate 时由 {@code occurredAt - dpd} 反推。
     *
     * <p>计划模板是 dayBlocks 绝对槽位模型，靠 {@code dueDate + dpdDay} 把"模板第几天"换算成日历日； 锚点缺失时 {@code
     * PhtSlotScheduleCalculator.futureSlots} 直接返回空，一个步骤都排不出来， 表现为全量案件建不出计划。而交付契约的 caseEvent 字段表里没有
     * dueDate，上游至今未下发。
     *
     * <p>事件自带一组等价锚点：occurredAt 当天的 dpd。dpd 口径为应还日之后的自然日数，故可直接反推。 必须用 occurredAt 而非当天——dpd
     * 是快照时点的值，用当天反推会按事件在队列里滞留的时长整体偏移。
     *
     * <p>上游若补发 dueDate 则以上游为准，本方法不参与。
     */
    private static LocalDate deriveDueDate(JSONObject json) {
        Integer dpd = json.getInteger(CollectionEvent.DPD);
        String raw = trimToNull(json.getString("occurredAt"));
        if (dpd == null || raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(PHT).toLocalDate().minusDays(dpd);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw, LOCAL_OCCURRED_AT).toLocalDate().minusDays(dpd);
            } catch (DateTimeParseException e) {
                return null;
            }
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

    /** 还款时间：优先取 {@code repayTime}（专门字段），缺省回退 {@code occurredAt}。 「当日回收金额」按还款发生日切桶，依赖此值。 */
    static LocalDateTime repayTimeOrFallback(JSONObject json, LocalDateTime fallback) {
        String raw = trimToNull(json.getString("repayTime"));
        if (raw == null) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(PHT).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw, LOCAL_OCCURRED_AT);
            } catch (DateTimeParseException e) {
                return fallback;
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
