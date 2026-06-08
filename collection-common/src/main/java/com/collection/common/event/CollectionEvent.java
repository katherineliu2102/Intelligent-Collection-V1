package com.collection.common.event;

import com.collection.common.enums.EventType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 内部领域事件信封。通过 {@link CollectionEventBus} 在模块间流转。
 * 对应基础设施规范 §2：事件序列化为 JSON，包含 eventId / eventType / timestamp / payload。
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
    public static final String PTP_ID = "ptpId";
    public static final String TARGET_STAGE = "targetStage";
    public static final String MAX_DPD = "maxDpd";
    public static final String DISPOSITION = "disposition";
    public static final String PROVIDER_MSG_ID = "providerMsgId";
    public static final String RESULT = "result";

    private String eventId;
    private EventType eventType;
    private LocalDateTime occurredAt;
    private Map<String, Object> payload = new HashMap<>();

    public CollectionEvent() {
    }

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
}
