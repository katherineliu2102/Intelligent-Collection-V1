package com.collection.ingestion;

import com.collection.common.enums.CancelReason;
import com.collection.common.enums.EventType;
import com.collection.common.enums.Stage;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.service.CaseService;
import java.util.Map;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 数据接入服务。对应架构设计文档 §数据接入层、数据接入与事件规格。
 *
 * <p>生产职责：把已落库的入站事实与日切比对结果 publish 为内部领域事件。快照字段随事件 payload 带出， 引擎据 payload 组装快照，运行时不读旧库 {@code
 * t_collection}。CaseService 仅用于投影守卫与日切。
 *
 * <p>发布领域事件的最小能力，既供链路自测注入（{@code MockTriggerController}），也供 {@link
 * com.collection.ingestion.pubsub.AiCaseIngestionProcessor} 在投影事务提交后调用。本类只 publish、不写库； 投影写入与收件箱幂等归
 * Processor，ack/nack/路由归 {@link com.collection.ingestion.pubsub.PubSubCaseConsumer}。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    @Resource private CollectionEventBus eventBus;
    @Resource private CaseService caseService;

    /** 新案件入库 → 发布 CASE_INGESTED（不带快照字段；引擎降级 CaseService 兜底）。 */
    public void ingestCase(Long caseId, Long userId, Stage stage) {
        ingestCase(caseId, userId, stage, null);
    }

    /**
     * 携带快照字段发布 CASE_INGESTED。真实 PubSub 消费（B1）从 caseEvent 映射后调用本方法，引擎据 payload 组装
     * ContextSnapshot，<b>运行时不读旧库 t_collection</b>。
     *
     * @param snapshotFields key 用 {@link CollectionEvent} 快照常量（DPD/PRODUCT/TOTAL_OUTSTANDING/
     *     PENALTY_AMOUNT/UPCOMING_AMOUNT/NEXT_DUE_DATE/NAME/PHONE/EMAIL/JPUSH_TOKEN）；可选提醒和联系字段缺失时不阻断。
     */
    public void ingestCase(
            Long caseId, Long userId, Stage stage, Map<String, Object> snapshotFields) {
        Stage resolvedStage = stage;
        if (resolvedStage == null && snapshotFields != null) {
            resolvedStage = stageFromDpd(snapshotFields.get(CollectionEvent.DPD));
        }
        if (snapshotFields != null) {
            requireFinancialFields(caseId, snapshotFields);
            if (resolvedStage == null) {
                resolvedStage = stageFromDpd(snapshotFields.get(CollectionEvent.DPD));
            }
        }
        if (resolvedStage == null) {
            CaseInfo info = caseService.getCaseInfo(caseId);
            resolvedStage = info != null ? info.getStage() : Stage.S1;
        }
        CollectionEvent event =
                CollectionEvent.of(EventType.CASE_INGESTED)
                        .with(CollectionEvent.CASE_ID, caseId)
                        .with(CollectionEvent.USER_ID, userId == null ? caseId : userId)
                        .with(CollectionEvent.STAGE, resolvedStage.name());
        if (snapshotFields != null) {
            snapshotFields.forEach(
                    (k, v) -> {
                        if (v != null) {
                            event.with(k, v);
                        }
                    });
        }
        log.info(
                "[Ingestion] publish CASE_INGESTED case={} stage={} snapshotFields={}",
                caseId,
                resolvedStage,
                snapshotFields == null ? 0 : snapshotFields.size());
        eventBus.publish(event);
    }

    private Stage stageFromDpd(Object dpd) {
        return dpd instanceof Number ? Stage.fromDpd(((Number) dpd).intValue()) : null;
    }

    /** 将 t_ai_collection 运行态快照映射为内部事件字段，供日切完整快照事件复用。 */
    public Map<String, Object> currentSnapshotFields(ContextSnapshot snapshot) {
        if (snapshot == null || snapshot.getCaseContext() == null) {
            return null;
        }
        CaseContext context = snapshot.getCaseContext();
        java.util.HashMap<String, Object> fields = new java.util.HashMap<>();
        fields.put(CollectionEvent.DPD, context.getDpd());
        fields.put(CollectionEvent.PRODUCT, context.getProduct());
        fields.put(CollectionEvent.TOTAL_OUTSTANDING, context.getTotalOutstanding());
        fields.put(CollectionEvent.PENALTY_AMOUNT, context.getPenaltyAmount());
        fields.put(
                CollectionEvent.DUE_DATE,
                context.getDueDate() == null ? null : context.getDueDate().toString());
        if (snapshot.getUserProfile() != null && snapshot.getUserProfile().getBasic() != null) {
            fields.put(CollectionEvent.NAME, snapshot.getUserProfile().getBasic().getName());
            fields.put(
                    CollectionEvent.PHONE, snapshot.getUserProfile().getBasic().getPrimaryPhone());
            fields.put(CollectionEvent.EMAIL, snapshot.getUserProfile().getBasic().getEmail());
            fields.put(
                    CollectionEvent.LANGUAGE, snapshot.getUserProfile().getBasic().getLanguage());
        }
        if (snapshot.getUserProfile() != null && snapshot.getUserProfile().getDevice() != null) {
            fields.put(
                    CollectionEvent.JPUSH_TOKEN,
                    snapshot.getUserProfile().getDevice().getJpushToken());
        }
        return fields;
    }

    private boolean hasMissingFinancialField(Map<String, Object> fields) {
        return fields.get(CollectionEvent.DPD) == null
                || fields.get(CollectionEvent.PRODUCT) == null
                || fields.get(CollectionEvent.TOTAL_OUTSTANDING) == null
                || fields.get(CollectionEvent.PENALTY_AMOUNT) == null;
    }

    private void requireFinancialFields(Long caseId, Map<String, Object> fields) {
        if (hasMissingFinancialField(fields)) {
            throw new IllegalArgumentException(
                    "event payload missing required financial fields, caseId=" + caseId);
        }
    }

    /** 阶段变更 → 发布 STAGE_CHANGED。 */
    public void changeStage(Long caseId, Stage newStage) {
        changeStage(caseId, newStage, null, null);
    }

    /**
     * 阶段变更 → 发布 STAGE_CHANGED，附带日切读到的日变字段。
     *
     * <p>{@code dpd} / {@code totalOutstanding} 为可选：非空时引擎在 carry-forward 快照里一并刷新，
     * 使新计划的快照列不再停留在建计划时刻的旧值（缺省则仅刷新 stage，保持原语义）。
     */
    public void changeStage(
            Long caseId, Stage newStage, Integer dpd, java.math.BigDecimal totalOutstanding) {
        eventBus.publish(
                CollectionEvent.of(EventType.STAGE_CHANGED)
                        .with(CollectionEvent.CASE_ID, caseId)
                        .with(CollectionEvent.STAGE, newStage.name())
                        .with(CollectionEvent.DPD, dpd)
                        .with(CollectionEvent.TOTAL_OUTSTANDING, totalOutstanding));
        log.info(
                "[Ingestion] publish STAGE_CHANGED case={} stage={} dpd={}", caseId, newStage, dpd);
    }

    /** v2 完整快照阶段变更：不依赖旧计划 carry-forward 或旧库兜底。 */
    public void changeStage(
            Long caseId, Long userId, Stage newStage, Map<String, Object> snapshotFields) {
        if (newStage == null) {
            throw new IllegalArgumentException("CASE_STAGE_CHANGED missing stage");
        }
        requireFinancialFields(caseId, snapshotFields);
        CollectionEvent event =
                CollectionEvent.of(EventType.STAGE_CHANGED)
                        .with(CollectionEvent.CASE_ID, caseId)
                        .with(CollectionEvent.USER_ID, userId)
                        .with(CollectionEvent.STAGE, newStage.name());
        snapshotFields.forEach(
                (key, value) -> {
                    if (value != null) {
                        event.with(key, value);
                    }
                });
        eventBus.publish(event);
        log.info(
                "[Ingestion] publish STAGE_CHANGED full snapshot case={} stage={}",
                caseId,
                newStage);
    }

    /** 整笔 loan 全额结清 → 发布案件级 REPAYMENT_RECEIVED。 */
    public void repayment(Long caseId, Long userId) {
        eventBus.publish(
                CollectionEvent.of(EventType.REPAYMENT_RECEIVED)
                        .with(CollectionEvent.CASE_ID, caseId)
                        .with(CollectionEvent.USER_ID, userId)
                        .with(
                                CollectionEvent.CANCEL_REASON,
                                com.collection.common.enums.CancelReason.REPAID.name())
                        .with(CollectionEvent.CANCEL_SCOPE, "CASE"));
        log.info(
                "[Ingestion] publish REPAYMENT_RECEIVED case={} user={} reason=REPAID",
                caseId,
                userId);
    }

    /** 部分还款刷新后续触达所需的可变案件字段，不取消计划或改变渠道策略。 */
    public void balanceUpdated(
            Long caseId, Long userId, java.math.BigDecimal totalOutstanding, Integer status) {
        balanceUpdated(caseId, userId, null, null, totalOutstanding, null, null, null, null);
    }

    public void balanceUpdated(
            Long caseId,
            Long userId,
            Integer dpd,
            java.math.BigDecimal overdueAmount,
            java.math.BigDecimal totalOutstanding,
            java.math.BigDecimal penaltyAmount,
            java.math.BigDecimal upcomingAmount,
            java.time.LocalDate nextDueDate,
            String collectionStatus) {
        eventBus.publish(
                CollectionEvent.of(EventType.CASE_BALANCE_UPDATED)
                        .with(CollectionEvent.CASE_ID, caseId)
                        .with(CollectionEvent.USER_ID, userId)
                        .with(CollectionEvent.TOTAL_OUTSTANDING, totalOutstanding)
                        .with(CollectionEvent.DPD, dpd)
                        .with(CollectionEvent.OVERDUE_AMOUNT, overdueAmount)
                        .with(CollectionEvent.PENALTY_AMOUNT, penaltyAmount)
                        .with(CollectionEvent.UPCOMING_AMOUNT, upcomingAmount)
                        .with(CollectionEvent.NEXT_DUE_DATE, nextDueDate)
                        .with("collectionStatus", collectionStatus));
        log.info(
                "[Ingestion] publish CASE_BALANCE_UPDATED case={} amount={}",
                caseId,
                totalOutstanding);
    }

    /** D+91 完全停催 → 发布 CASE_CEASED（引擎 cancel plan，不再 create）。 */
    public void caseCeased(Long caseId, Integer maxDpd) {
        eventBus.publish(
                CollectionEvent.of(EventType.CASE_CEASED)
                        .with(CollectionEvent.CASE_ID, caseId)
                        .with(CollectionEvent.MAX_DPD, maxDpd == null ? 91 : maxDpd));
        log.info("[Ingestion] publish CASE_CEASED case={} maxDpd={}", caseId, maxDpd);
    }

    /** 当日 NEW 缺席迁出 → 发布 CASE_OWNER_RECONCILED。 */
    public void routedToLegacy(Long caseId, Long userId) {
        eventBus.publish(
                CollectionEvent.of(EventType.CASE_OWNER_RECONCILED)
                        .with(CollectionEvent.CASE_ID, caseId)
                        .with(CollectionEvent.USER_ID, userId == null ? caseId : userId)
                        .with(CollectionEvent.OWNER_ACTION, "LEAVE")
                        .with(CollectionEvent.CANCEL_REASON, CancelReason.ROUTED_TO_LEGACY.name()));
        log.info("[Ingestion] publish CASE_OWNER_RECONCILED LEAVE case={}", caseId);
    }

    /** PTP 到期 → 发布 PTP_EXPIRED。Phase 2 预留：Phase 1 引擎不消费此事件（核心引擎规格 §2.6）。 */
    public void ptpExpired(Long caseId, Long ptpId) {
        eventBus.publish(
                CollectionEvent.of(EventType.PTP_EXPIRED)
                        .with(CollectionEvent.CASE_ID, caseId)
                        .with(CollectionEvent.PTP_ID, ptpId));
        log.info("[Ingestion] publish PTP_EXPIRED case={} ptp={}", caseId, ptpId);
    }
}
