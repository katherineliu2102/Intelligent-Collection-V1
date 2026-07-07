package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSONObject;
import com.collection.common.enums.Stage;
import com.collection.common.event.CollectionEvent;
import com.collection.ingestion.config.IngestionProperties;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把上游 {@code case_push} / {@code repayment_push_and_load} 报文映射为领域事件 payload（语义字段 → {@link
 * CollectionEvent} 常量 key，契约见领域模型 §9.2）。
 *
 * <p>上游真实 JSON key 名待信贷联调（数据接入规格 C-I-01~16）：本类通过 {@code collection.ingestion.case-push.field-map}
 * 把语义字段映射到上游 key，未配则按契约同名取值，因此 <b>自发样例消息（按 §9.2 key）即可直接跑通</b>，待信贷给出样例 JSON 后只改 Nacos 别名表。
 *
 * <p>清洗口径与 {@code RealCaseService} 对齐（C-I-06）：phone 归一化 E.164 {@code +63}；脏 email → 不带出。
 */
@Component
public class CasePayloadMapper {

    private static final Logger log = LoggerFactory.getLogger(CasePayloadMapper.class);

    @Resource private IngestionProperties props;

    /** 入案映射结果。{@code stage} 可为 null（交由 IngestionService 据 dpd 推导）。 */
    public static final class CaseIngest {
        public final Long caseId;
        public final Long userId;
        public final Stage stage;
        public final Map<String, Object> snapshotFields;

        CaseIngest(Long caseId, Long userId, Stage stage, Map<String, Object> snapshotFields) {
            this.caseId = caseId;
            this.userId = userId;
            this.stage = stage;
            this.snapshotFields = snapshotFields;
        }
    }

    /** 消息类型：优先 PubSub attribute，其次 JSON 字段。 */
    public String dataType(JSONObject json, String attrDataType) {
        if (StringUtils.isNotBlank(attrDataType)) {
            return attrDataType.trim();
        }
        return trimToNull(json.getString(props.getCasePush().getDataTypeField()));
    }

    /** 业务 message_id；缺失回退 PubSub 原生 messageId（去重用）。 */
    public String messageId(JSONObject json, String pubsubMessageId) {
        String biz = trimToNull(json.getString(props.getCasePush().getMessageIdField()));
        return biz != null ? biz : pubsubMessageId;
    }

    /** case_push 的业务主键 loan_id（= payload caseId）。 */
    public Long loanId(JSONObject json) {
        return getLong(json, key(CollectionEvent.CASE_ID));
    }

    /**
     * 映射 case_push → 入案 payload。{@code caseId} 必填（缺失抛 {@link PoisonMessageException}）。
     * snapshotFields 仅带出存在的字段（缺失字段 null 防御，下游 PUSH 缺 token → fallback SMS）。
     */
    public CaseIngest mapCasePush(JSONObject json) {
        Long caseId = getLong(json, key(CollectionEvent.CASE_ID));
        if (caseId == null) {
            throw new PoisonMessageException("case_push 缺必填 caseId/loan_id");
        }
        Long userId = getLong(json, key(CollectionEvent.USER_ID));

        Stage stage = null;
        String stageRaw = trimToNull(json.getString(key(CollectionEvent.STAGE)));
        if (stageRaw != null) {
            try {
                stage = Stage.valueOf(stageRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn(
                        "[Ingestion] case_push 非法 stage='{}' caseId={}，改由 dpd 推导",
                        stageRaw,
                        caseId);
            }
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        putInt(fields, json, CollectionEvent.DPD);
        putStr(fields, json, CollectionEvent.PRODUCT);
        putDecimal(fields, json, CollectionEvent.TOTAL_OUTSTANDING);
        putDecimal(fields, json, CollectionEvent.PENALTY_AMOUNT);
        putStr(fields, json, CollectionEvent.DUE_DATE);
        putStr(fields, json, CollectionEvent.FULL_REPAY_TIME);
        putStr(fields, json, CollectionEvent.NAME);

        String phone = normalizePhone(trimToNull(json.getString(key(CollectionEvent.PHONE))));
        if (phone != null) {
            fields.put(CollectionEvent.PHONE, phone);
        }
        String email = cleanEmail(trimToNull(json.getString(key(CollectionEvent.EMAIL))));
        if (email != null) {
            fields.put(CollectionEvent.EMAIL, email);
        }
        putStr(fields, json, CollectionEvent.JPUSH_TOKEN);

        return new CaseIngest(caseId, userId, stage, fields);
    }

    /** repayment 的 userId（必填，缺失抛 poison）。 */
    public Long repaymentUserId(JSONObject json) {
        Long userId = getLong(json, key(CollectionEvent.USER_ID));
        if (userId == null) {
            throw new PoisonMessageException("repayment_push_and_load 缺必填 userId");
        }
        return userId;
    }

    /**
     * 是否全额结清（命中则 DEL ingested key，§2.2.2）。判定条件待信贷联调（C-I-13）：当前取 {@code fullRepay==true} 或 {@code
     * totalOutstanding<=0}。
     */
    public boolean fullySettled(JSONObject json) {
        Boolean flag = json.getBoolean("fullRepay");
        if (flag != null) {
            return flag;
        }
        BigDecimal out = getDecimal(json, key(CollectionEvent.TOTAL_OUTSTANDING));
        return out != null && out.compareTo(BigDecimal.ZERO) <= 0;
    }

    // ───────────────────────── helpers ─────────────────────────

    private String key(String semantic) {
        return props.getCasePush().getFieldMap().getOrDefault(semantic, semantic);
    }

    private void putStr(Map<String, Object> fields, JSONObject json, String semantic) {
        String v = trimToNull(json.getString(key(semantic)));
        if (v != null) {
            fields.put(semantic, v);
        }
    }

    private void putInt(Map<String, Object> fields, JSONObject json, String semantic) {
        Integer v = json.getInteger(key(semantic));
        if (v != null) {
            fields.put(semantic, v);
        }
    }

    private void putDecimal(Map<String, Object> fields, JSONObject json, String semantic) {
        BigDecimal v = getDecimal(json, key(semantic));
        if (v != null) {
            fields.put(semantic, v);
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

    /** 归一化 E.164 +63（菲律宾）：去空格/连字符；09xx/9xx → +639xx；已 + 开头保留。与 RealCaseService 同口径。 */
    private static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.replaceAll("[\\s-]", "");
        if (p.isEmpty()) {
            return null;
        }
        if (p.startsWith("+")) {
            return p;
        }
        if (p.startsWith("0")) {
            p = p.substring(1);
        }
        if (p.startsWith("63")) {
            return "+" + p;
        }
        return "+63" + p;
    }

    /** 清洗脏邮箱：空 / "0" / 无 "@" → null（EMAIL 渠道由 Guard SKIP 处理）。与 RealCaseService 同口径。 */
    private static String cleanEmail(String raw) {
        if (raw == null) {
            return null;
        }
        if ("0".equals(raw) || !raw.contains("@")) {
            return null;
        }
        return raw;
    }
}
