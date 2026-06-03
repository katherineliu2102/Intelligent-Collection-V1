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
 * <p>生产职责：消费上游 PubSub（case_push / repayment / assign）→ 清洗入库 →
 * 生成 context_snapshot → 发布 Redis Stream 领域事件。
 * <p>Phase 1 骨架：提供"发布领域事件"的最小能力，供链路自测注入（不含真实 PubSub 消费）。
 * 真实实现由数据接入负责人补全 PubSub Consumer 与快照生成。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    @Resource
    private CollectionEventBus eventBus;
    @Resource
    private CaseService caseService;

    /** 新案件入库 → 发布 CASE_INGESTED。 */
    public void ingestCase(Long caseId, Long userId, Stage stage) {
        Stage resolvedStage = stage;
        if (resolvedStage == null) {
            CaseInfo info = caseService.getCaseInfo(caseId);
            resolvedStage = info != null ? info.getStage() : Stage.S1;
        }
        CollectionEvent event = CollectionEvent.of(EventType.CASE_INGESTED)
                .with(CollectionEvent.CASE_ID, caseId)
                .with(CollectionEvent.USER_ID, userId == null ? caseId : userId)
                .with(CollectionEvent.STAGE, resolvedStage.name());
        log.info("[Ingestion] publish CASE_INGESTED case={} stage={}", caseId, resolvedStage);
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

    /** PTP 到期 → 发布 PTP_EXPIRED。 */
    public void ptpExpired(Long caseId, Long ptpId) {
        eventBus.publish(CollectionEvent.of(EventType.PTP_EXPIRED)
                .with(CollectionEvent.CASE_ID, caseId)
                .with(CollectionEvent.PTP_ID, ptpId));
        log.info("[Ingestion] publish PTP_EXPIRED case={} ptp={}", caseId, ptpId);
    }
}
