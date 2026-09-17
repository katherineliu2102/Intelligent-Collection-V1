package com.collection.ingestion.pubsub;

import com.alibaba.fastjson.JSONObject;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.model.ContactPlan;
import com.collection.common.repository.CaseProjectionRepository;
import com.collection.common.repository.CaseProjectionRepository.Outcome;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.MissingCaseBaselineException;
import com.collection.common.service.CaseService;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.metrics.IngestionMetrics;
import java.util.List;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * v2 入站事实的唯一处理管道（数据接入规格 §3）。数仓直发完整、持久、可重放的 Pub/Sub 事实流， 本类负责把它落成案件投影并驱动引擎：
 *
 * <ol>
 *   <li>事务内写收件箱 + 按 {@code caseVersion} 内容指纹条件 upsert {@code t_ai_collection}；
 *   <li>提交后再 publish 内部领域事件，成功才标记收件箱已发布。
 * </ol>
 *
 * <p>顺序不可颠倒：先发事件后写投影会让引擎的实时守卫与日切读到旧快照。publish 失败时抛出异常由 {@link PubSubCaseConsumer} nack，重投命中 {@link
 * Outcome#PENDING_PUBLISH} 只补发事件、不重复写投影。
 *
 * <p>外部 Topic 只受理 {@code CASE_INGESTED} 与 {@code REPAYMENT}；阶段变更与 D+91 停催由 {@code
 * DpdStageRollHandler} 读投影后独占产出，避免同一状态被两个来源重复触发。
 */
@Component
public class AiCaseIngestionProcessor {

    private static final Logger log = LoggerFactory.getLogger(AiCaseIngestionProcessor.class);

    private static final String MESSAGE_TYPE_CASE = "caseEvent";
    private static final String MESSAGE_TYPE_REPAYMENT = "repaymentEvent";
    private static final String EVENT_CASE_INGESTED = "CASE_INGESTED";
    private static final String EVENT_REPAYMENT = "REPAYMENT";

    @Resource private CasePayloadMapper mapper;
    @Resource private CaseProjectionAssembler assembler;
    @Resource private CaseProjectionRepository projectionRepository;
    @Resource private IngestionService ingestionService;
    @Resource private CaseService caseService;
    @Resource private IngestionDedupStore dedup;
    @Resource private IngestionFaultInjector faultInjector;
    @Resource private IngestionMetrics metrics;

    @Autowired(required = false)
    private ContactPlanRepository planRepository;

    public void handleCaseEvent(JSONObject json, String rawPayload) {
        String eventId = requireEventId(json, MESSAGE_TYPE_CASE);
        if (dedup.isMessageProcessed(eventId)) {
            metrics.deduped(MESSAGE_TYPE_CASE);
            return;
        }
        String eventType = json.getString("eventType");
        if (eventType != null && !EVENT_CASE_INGESTED.equals(eventType)) {
            throw new PoisonMessageException(
                    "外部 caseEvent 仅受理 CASE_INGESTED，阶段/停催由日切产出；收到 eventType=" + eventType);
        }
        eventType = EVENT_CASE_INGESTED;
        CasePayloadMapper.AiSnapshot snapshot = mapper.mapAiSnapshot(json);
        recordDueDateSource(json, snapshot);
        // L4b-7：在投影落库之前注入瞬态失败，使重投走完整的收件箱补发路径（默认关闭，仅白名单案可命中）
        faultInjector.failIfArmed(snapshot.caseId);
        CaseProjection projection = assembler.assemble(json, snapshot);
        Outcome outcome =
                projectionRepository.apply(
                        command(
                                eventId,
                                MESSAGE_TYPE_CASE,
                                eventType,
                                rawPayload,
                                false,
                                projection));
        if (outcome == Outcome.PENDING_PUBLISH) {
            confirmPublished(eventId);
            return;
        }
        if (outcome == Outcome.APPLIED_WITHOUT_EVENT
                || outcome == Outcome.APPLIED
                || outcome == Outcome.ALREADY_PROCESSED) {
            if (shouldCatchUpIngest(projection)) {
                ingestionService.ingestCase(
                        snapshot.caseId, snapshot.userId, snapshot.stage, snapshot.snapshotFields);
                dedup.markIngested(snapshot.caseId);
            }
            dedup.markMessageProcessed(eventId);
            return;
        }
        if (!shouldPublish(outcome, eventId, snapshot.caseId)) {
            return;
        }
        confirmPublished(eventId);
    }

    private boolean shouldCatchUpIngest(CaseProjection projection) {
        if (projection.getOwnerDate() == null) {
            return false;
        }
        try {
            if (!caseService.isOwnerReconciledToday() || !caseService.requiresOwnerDate()) {
                return false;
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        CaseInfo info = caseService.getCaseInfo(projection.getCaseId());
        return info != null
                && !info.isRepaid()
                && info.getOwnerDate() != null
                && info.getOwnerDate().equals(projection.getOwnerDate())
                && "IN_COLLECTION".equalsIgnoreCase(info.getCaseStatus())
                && !hasActivePlan(projection.getCaseId());
    }

    private boolean hasActivePlan(Long caseId) {
        if (planRepository == null) {
            return false;
        }
        List<ContactPlan> plans = planRepository.findActivePlansByCase(caseId);
        return plans != null && !plans.isEmpty();
    }

    public void handleRepaymentEvent(JSONObject json, String rawPayload) {
        String eventId = requireEventId(json, MESSAGE_TYPE_REPAYMENT);
        if (dedup.isMessageProcessed(eventId)) {
            metrics.deduped(MESSAGE_TYPE_REPAYMENT);
            return;
        }
        CasePayloadMapper.RepaymentDelta delta = mapper.mapRepaymentDelta(json);
        boolean fullCleared = delta.fullCleared;
        CaseProjection projection = assembler.assembleRepaymentDelta(delta);
        Outcome outcome;
        try {
            outcome =
                    projectionRepository.applyRepaymentDelta(
                            command(
                                    eventId,
                                    MESSAGE_TYPE_REPAYMENT,
                                    EVENT_REPAYMENT,
                                    rawPayload,
                                    true,
                                    projection));
        } catch (MissingCaseBaselineException e) {
            // §3.3：缺基线不是瞬态失败，重投不会补出基线，必须 ack + 告警而非 nack 死循环
            throw new PoisonMessageException(e.getMessage());
        }
        if (!shouldPublish(outcome, eventId, delta.caseId)) {
            return;
        }
        if (fullCleared) {
            ingestionService.repayment(delta.caseId, delta.userId);
            dedup.clearIngested(delta.caseId);
        } else {
            ingestionService.balanceUpdated(
                    delta.caseId,
                    delta.userId,
                    projection.getDpd(),
                    projection.getOverdueAmount(),
                    projection.getTotalOutstanding(),
                    projection.getPenaltyAmount(),
                    projection.getUpcomingAmount(),
                    projection.getNextDueDate(),
                    projection.getCollectionStatus());
        }
        confirmPublished(eventId);
    }

    /**
     * 记录计划排期锚点的来源。dayBlocks 靠 {@code dueDate + dpdDay} 换算日历日，缺锚点则一个槽位都排不出来， 表现为案件入库正常但静默没有任何触达 ——
     * 2026-08-24 T3o 演练即因此 38 案全量建不出计划。
     */
    private void recordDueDateSource(JSONObject json, CasePayloadMapper.AiSnapshot snapshot) {
        if (!snapshot.snapshotFields.containsKey(CollectionEvent.DUE_DATE)) {
            metrics.dueDateSource("ABSENT");
            log.warn(
                    "[Ingestion] caseId={} 无 dueDate 且无法反推（缺 dpd 或 occurredAt），该案排不出任何触达槽位",
                    snapshot.caseId);
            return;
        }
        metrics.dueDateSource(json.containsKey(CollectionEvent.DUE_DATE) ? "UPSTREAM" : "DERIVED");
    }

    private boolean shouldPublish(Outcome outcome, String eventId, Long caseId) {
        if (outcome == Outcome.APPLIED || outcome == Outcome.PENDING_PUBLISH) {
            return true;
        }
        log.info("[Ingestion] 投影未产生领域事件 outcome={} eventId={} caseId={}", outcome, eventId, caseId);
        return false;
    }

    private void confirmPublished(String eventId) {
        projectionRepository.markEventPublished(eventId);
        dedup.markMessageProcessed(eventId);
    }

    private String requireEventId(JSONObject json, String messageType) {
        String eventId = mapper.eventId(json);
        if (eventId == null) {
            throw new PoisonMessageException(messageType + " 缺 eventId");
        }
        return eventId;
    }

    private CaseProjectionCommand command(
            String eventId,
            String messageType,
            String eventType,
            String rawPayload,
            boolean publishRequired,
            CaseProjection projection) {
        CaseProjectionCommand command = new CaseProjectionCommand();
        command.setEventId(eventId);
        command.setMessageType(messageType);
        command.setEventType(eventType);
        command.setPayload(rawPayload);
        command.setPublishRequired(publishRequired);
        command.setProjection(projection);
        return command;
    }
}
