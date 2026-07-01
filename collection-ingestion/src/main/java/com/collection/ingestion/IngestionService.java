package com.collection.ingestion;

import com.collection.common.enums.EventType;
import com.collection.common.enums.Stage;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.CaseInfo;
import com.collection.common.service.CaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 数据接入服务。对应架构设计文档 §数据接入层、数据接入与事件规格。
 *
 * <p>生产职责：消费 PubSub（case_push / repayment）→ 校验 / 对账（旧库只读）→ publish 领域事件。
 * 不回写旧库；<b>决策 B（2026-06-29）</b>：快照字段随 CASE_INGESTED payload 带出（源自 case_push），
 * 引擎据 payload 组装快照，运行时不读旧库 t_collection。CaseService 仅作兜底 / 对账。
 * <p>发布领域事件的最小能力，既供链路自测注入（{@code MockTriggerController}），也供真实 PubSub
 * 消费者 {@link com.collection.ingestion.pubsub.PubSubCaseConsumer}（B1）映射后调用。本类只 publish、
 * 不写库；ack/nack/幂等/路由归 Consumer。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    @Resource
    private CollectionEventBus eventBus;
    @Resource
    private CaseService caseService;

    /** 新案件入库 → 发布 CASE_INGESTED（不带快照字段；引擎降级 CaseService 兜底）。 */
    public void ingestCase(Long caseId, Long userId, Stage stage) {
        ingestCase(caseId, userId, stage, null);
    }

    /**
     * 决策 B（2026-06-29）：携带快照字段发布 CASE_INGESTED。真实 PubSub 消费（B1）从 case_push
     * 映射后调用本方法，引擎据 payload 组装 ContextSnapshot，<b>运行时不读旧库 t_collection</b>。
     *
     * @param snapshotFields key 用 {@link CollectionEvent} 快照常量（DPD/PRODUCT/TOTAL_OUTSTANDING/
     *     PENALTY_AMOUNT/DUE_DATE/FULL_REPAY_TIME/NAME/PHONE/EMAIL/JPUSH_TOKEN）；缺失字段做 null 防御。
     */
    public void ingestCase(
            Long caseId, Long userId, Stage stage, java.util.Map<String, Object> snapshotFields) {
        Stage resolvedStage = stage;
        if (resolvedStage == null && snapshotFields != null) {
            Object dpd = snapshotFields.get(CollectionEvent.DPD);
            if (dpd instanceof Number) {
                resolvedStage = Stage.fromDpd(((Number) dpd).intValue());
            }
        }
        if (resolvedStage == null) {
            CaseInfo info = caseService.getCaseInfo(caseId);
            resolvedStage = info != null ? info.getStage() : Stage.S1;
        }
        CollectionEvent event = CollectionEvent.of(EventType.CASE_INGESTED)
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

    /** 阶段变更 → 发布 STAGE_CHANGED。 */
    public void changeStage(Long caseId, Stage newStage) {
        eventBus.publish(CollectionEvent.of(EventType.STAGE_CHANGED)
                .with(CollectionEvent.CASE_ID, caseId)
                .with(CollectionEvent.STAGE, newStage.name()));
        log.info("[Ingestion] publish STAGE_CHANGED case={} stage={}", caseId, newStage);
    }

    /** 还款到账 → 发布 REPAYMENT_RECEIVED。 */
    public void repayment(Long userId) {
        eventBus.publish(CollectionEvent.of(EventType.REPAYMENT_RECEIVED)
                .with(CollectionEvent.USER_ID, userId));
        log.info("[Ingestion] publish REPAYMENT_RECEIVED user={}", userId);
    }

    /** D+91 完全停催 → 发布 CASE_CEASED（引擎 cancel plan，不再 create）。 */
    public void caseCeased(Long caseId, Integer maxDpd) {
        eventBus.publish(CollectionEvent.of(EventType.CASE_CEASED)
                .with(CollectionEvent.CASE_ID, caseId)
                .with(CollectionEvent.MAX_DPD, maxDpd == null ? 91 : maxDpd));
        log.info("[Ingestion] publish CASE_CEASED case={} maxDpd={}", caseId, maxDpd);
    }

    /** PTP 到期 → 发布 PTP_EXPIRED。Phase 2 预留：Phase 1 引擎不消费此事件（核心引擎规格 §2.6）。 */
    public void ptpExpired(Long caseId, Long ptpId) {
        eventBus.publish(CollectionEvent.of(EventType.PTP_EXPIRED)
                .with(CollectionEvent.CASE_ID, caseId)
                .with(CollectionEvent.PTP_ID, ptpId));
        log.info("[Ingestion] publish PTP_EXPIRED case={} ptp={}", caseId, ptpId);
    }
}
