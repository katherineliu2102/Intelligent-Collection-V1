package com.collection.common.event;

import com.collection.common.enums.EventType;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

/**
 * 内部领域事件信封。通过 {@link CollectionEventBus} 在模块间流转。 对应基础设施规范 §2：事件序列化为 JSON，包含 eventId / eventType /
 * timestamp / payload。
 *
 * <p>payload 已知 key（按事件类型）：caseId / userId / planId / stepId / stage / ptpId / targetStage 等。
 */
@Data
public class CollectionEvent {

    public static final String CASE_ID = "caseId";
    public static final String USER_ID = "userId";
    public static final String PLAN_ID = "planId";
    public static final String STEP_ID = "stepId";
    public static final String STAGE = "stage";
    /** Phase 2 预留：仅 PTP_EXPIRED 事件使用，Phase 1 引擎不消费（核心引擎规格 §2.6）。 */
    public static final String PTP_ID = "ptpId";

    public static final String TARGET_STAGE = "targetStage";
    public static final String MAX_DPD = "maxDpd";
    public static final String DISPOSITION = "disposition";
    public static final String PROVIDER_MSG_ID = "providerMsgId";
    public static final String RESULT = "result";

    // ── 决策 B（2026-06-29）：CASE_INGESTED 携带的快照字段，引擎据此组装 ContextSnapshot，
    //    运行时不读旧库 t_collection。SSOT 见领域模型 §9.2。
    public static final String DPD = "dpd";
    public static final String PRODUCT = "product";
    public static final String TOTAL_OUTSTANDING = "totalOutstanding";
    public static final String PENALTY_AMOUNT = "penaltyAmount";
    public static final String DUE_DATE = "dueDate";
    public static final String FULL_REPAY_TIME = "fullRepayTime";
    public static final String NAME = "name";
    public static final String PHONE = "phone";
    public static final String EMAIL = "email";
    public static final String JPUSH_TOKEN = "jpushToken";

    private String eventId;
    private EventType eventType;
    private LocalDateTime occurredAt;
    private Map<String, Object> payload = new HashMap<>();

    public CollectionEvent() {}

    public static CollectionEvent of(EventType type) {
        CollectionEvent e = new CollectionEvent();
        e.eventId = UUID.randomUUID().toString();
        e.eventType = type;
        e.occurredAt = LocalDateTime.now();
        return e;
    }

    public CollectionEvent with(String key, Object value) {
        this.payload.put(key, value);
        return this;
    }

    public Long getLong(String key) {
        Object v = payload.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return Long.valueOf(v.toString());
    }

    public String getString(String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }

    public Integer getInt(String key) {
        Object v = payload.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return Integer.valueOf(v.toString().trim());
    }

    public java.math.BigDecimal getBigDecimal(String key) {
        Object v = payload.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof java.math.BigDecimal) {
            return (java.math.BigDecimal) v;
        }
        return new java.math.BigDecimal(v.toString().trim());
    }

    /** payload 是否含某 key（用于判断事件是否携带可选字段，如决策 B 的快照字段）。 */
    public boolean has(String key) {
        return payload.containsKey(key) && payload.get(key) != null;
    }
}
