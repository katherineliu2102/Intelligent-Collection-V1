package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSONObject;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.CaseProjection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 把 v2 完整快照映射为 {@link CaseProjection}。金融与联系人字段一律取自 {@link
 * CasePayloadMapper.AiSnapshot#snapshotFields}，使写入 t_ai_collection 的值与发布给引擎的 payload 完全一致。
 */
@Component
public class CaseProjectionAssembler {

    private static final ZoneId PHT = ZoneId.of("Asia/Manila");

    public CaseProjection assemble(JSONObject json, CasePayloadMapper.AiSnapshot snapshot) {
        Map<String, Object> fields = snapshot.snapshotFields;
        CaseProjection projection = new CaseProjection();
        projection.setCaseId(snapshot.caseId);
        projection.setUserId(snapshot.userId);
        projection.setCaseVersion(snapshot.caseVersion);
        projection.setDpd((Integer) fields.get(CollectionEvent.DPD));
        projection.setStage(snapshot.stage == null ? null : snapshot.stage.name());
        projection.setCollectionStatus(requireStatus(json, snapshot.caseId));
        projection.setProduct((String) fields.get(CollectionEvent.PRODUCT));
        projection.setTotalOutstanding(decimal(fields.get(CollectionEvent.TOTAL_OUTSTANDING)));
        projection.setPenaltyAmount(decimal(fields.get(CollectionEvent.PENALTY_AMOUNT)));
        projection.setRemainingAmount(remainingAmount(json, projection.getTotalOutstanding()));
        projection.setDueDate(dueDate(fields.get(CollectionEvent.DUE_DATE), snapshot.caseId));
        projection.setBorrowerName((String) fields.get(CollectionEvent.NAME));
        projection.setBorrowerPhone((String) fields.get(CollectionEvent.PHONE));
        projection.setBorrowerEmail((String) fields.get(CollectionEvent.EMAIL));
        projection.setBorrowerLanguage(language(fields.get(CollectionEvent.LANGUAGE)));
        projection.setPushToken((String) fields.get(CollectionEvent.JPUSH_TOKEN));
        projection.setUpdatedAt(occurredAt(json, snapshot.caseId));
        return projection;
    }

    private String requireStatus(JSONObject json, Long caseId) {
        String status = StringUtils.trimToNull(json.getString("collectionStatus"));
        if (status == null) {
            throw new PoisonMessageException("v2 payload 缺 collectionStatus，caseId=" + caseId);
        }
        return status;
    }

    /** 仅用于对账，缺失时退化为对客金额，避免 NOT NULL 列写空。 */
    private BigDecimal remainingAmount(JSONObject json, BigDecimal totalOutstanding) {
        BigDecimal remaining = json.getBigDecimal("remainingAmount");
        if (remaining != null) {
            return remaining;
        }
        return totalOutstanding == null ? BigDecimal.ZERO : totalOutstanding;
    }

    private LocalDate dueDate(Object raw, Long caseId) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.toString().trim());
        } catch (DateTimeParseException e) {
            throw new PoisonMessageException("非法 dueDate=" + raw + " caseId=" + caseId);
        }
    }

    /** 事实发生时间是投影新鲜度的唯一依据，缺失即无法判断数据是否迟到。 */
    private LocalDateTime occurredAt(JSONObject json, Long caseId) {
        String raw = StringUtils.trimToNull(json.getString("occurredAt"));
        if (raw == null) {
            throw new PoisonMessageException("v2 payload 缺 occurredAt，caseId=" + caseId);
        }
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(PHT).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new PoisonMessageException("非法 occurredAt=" + raw + " caseId=" + caseId);
        }
    }

    private String language(Object raw) {
        String value = raw == null ? null : StringUtils.trimToNull(raw.toString());
        return value == null ? "en" : value;
    }

    private BigDecimal decimal(Object raw) {
        return raw instanceof BigDecimal ? (BigDecimal) raw : null;
    }
}
