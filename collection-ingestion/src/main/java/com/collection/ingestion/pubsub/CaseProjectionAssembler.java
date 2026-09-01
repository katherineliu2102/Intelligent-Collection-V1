package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSONObject;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.CaseProjection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 把 v2 完整快照映射为 {@link CaseProjection}。金融与联系人字段一律取自 {@link
 * CasePayloadMapper.AiSnapshot#snapshotFields}，使写入 t_ai_collection 的值与发布给引擎的 payload 完全一致。
 */
@Component
public class CaseProjectionAssembler {

    public CaseProjection assemble(JSONObject json, CasePayloadMapper.AiSnapshot snapshot) {
        Map<String, Object> fields = snapshot.snapshotFields;
        CaseProjection projection = new CaseProjection();
        projection.setCaseId(snapshot.caseId);
        projection.setUserId(snapshot.userId);
        projection.setCaseVersion(snapshot.caseVersion);
        projection.setDpd((Integer) fields.get(CollectionEvent.DPD));
        projection.setStage(snapshot.stage == null ? null : snapshot.stage.name());
        projection.setCollectionStatus(deriveStatus(json, fields.get(CollectionEvent.DPD)));
        projection.setProduct((String) fields.get(CollectionEvent.PRODUCT));
        projection.setTotalOutstanding(decimal(fields.get(CollectionEvent.TOTAL_OUTSTANDING)));
        BigDecimal overdueAmount = decimal(json.get("overdueAmount"));
        projection.setOverdueAmount(
                overdueAmount == null ? projection.getTotalOutstanding() : overdueAmount);
        projection.setPenaltyAmount(decimal(fields.get(CollectionEvent.PENALTY_AMOUNT)));
        projection.setRemainingAmount(remainingAmount(json, projection.getTotalOutstanding()));
        projection.setUpcomingAmount(json.getBigDecimal("upcomingAmount"));
        projection.setDueDate(
                dueDate(fields.get(CollectionEvent.DUE_DATE), snapshot.caseId, "dueDate"));
        projection.setNextDueDate(dueDate(json.get("nextDueDate"), snapshot.caseId, "nextDueDate"));
        projection.setBorrowerName((String) fields.get(CollectionEvent.NAME));
        projection.setBorrowerPhone((String) fields.get(CollectionEvent.PHONE));
        projection.setBorrowerEmail((String) fields.get(CollectionEvent.EMAIL));
        projection.setBorrowerLanguage(language(fields.get(CollectionEvent.LANGUAGE)));
        projection.setPushToken((String) fields.get(CollectionEvent.JPUSH_TOKEN));
        projection.setUpdatedAt(occurredAt(json, snapshot.caseId));
        return projection;
    }

    public CaseProjection assembleRepaymentDelta(CasePayloadMapper.RepaymentDelta delta) {
        CasePayloadMapper.CaseProjectionFields fields = delta.fields;
        CaseProjection projection = new CaseProjection();
        projection.setCaseId(delta.caseId);
        projection.setUserId(delta.userId);
        projection.setDpd(fields.dpd);
        // stage 与 dpd 同源同刻：只写 dpd 会让投影出现「stage 来自入案、dpd 来自还款」的矛盾组合。
        // stagePresent=false（缺字段或取值非法）时保持基线；显式 null 是有效取值，代表不属任何催收阶段。
        projection.setStage(fields.stage == null ? null : fields.stage.name());
        projection.setStagePresent(fields.stagePresent);
        projection.setOverdueAmount(fields.overdueAmount);
        projection.setTotalOutstanding(fields.overdueAmount);
        projection.setPenaltyAmount(fields.penaltyAmount);
        projection.setUpcomingAmount(fields.upcomingAmount);
        projection.setNextDueDate(fields.nextDueDate);
        projection.setNextDueDatePresent(fields.nextDueDatePresent);
        projection.setCollectionStatus(
                delta.fullCleared ? "SETTLED" : fields.dpd >= 91 ? "CEASED" : "IN_COLLECTION");
        projection.setUpdatedAt(fields.occurredAt);
        return projection;
    }

    private String deriveStatus(JSONObject json, Object dpdRaw) {
        Boolean fullCleared = json.getBoolean("isFullCleared");
        Integer dpd = dpdRaw instanceof Integer ? (Integer) dpdRaw : null;
        if (Boolean.TRUE.equals(fullCleared)) {
            return "SETTLED";
        }
        return dpd != null && dpd >= 91 ? "CEASED" : "IN_COLLECTION";
    }

    /** 仅用于对账，缺失时退化为对客金额，避免 NOT NULL 列写空。 */
    private BigDecimal remainingAmount(JSONObject json, BigDecimal totalOutstanding) {
        BigDecimal remaining = json.getBigDecimal("remainingAmount");
        if (remaining != null) {
            return remaining;
        }
        return totalOutstanding == null ? BigDecimal.ZERO : totalOutstanding;
    }

    private LocalDate dueDate(Object raw, Long caseId, String field) {
        try {
            return CasePayloadMapper.parseDate(raw, field);
        } catch (PoisonMessageException e) {
            throw new PoisonMessageException("非法 " + field + "=" + raw + " caseId=" + caseId);
        }
    }

    /** 事实发生时间是投影新鲜度的唯一依据，缺失即无法判断数据是否迟到。 */
    private LocalDateTime occurredAt(JSONObject json, Long caseId) {
        return CasePayloadMapper.occurredAt(json, caseId, "caseEvent");
    }

    private String language(Object raw) {
        String value = raw == null ? null : StringUtils.trimToNull(raw.toString());
        return value == null ? "en" : value;
    }

    private BigDecimal decimal(Object raw) {
        return raw instanceof BigDecimal ? (BigDecimal) raw : null;
    }
}
