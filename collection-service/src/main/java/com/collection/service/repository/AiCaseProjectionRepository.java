package com.collection.service.repository;

import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.repository.CaseProjectionRepository;
import com.collection.common.repository.MissingCaseBaselineException;
import com.collection.service.mapper.AiCollectionInboxMapper;
import com.collection.service.mapper.AiCollectionInboxRow;
import com.collection.service.mapper.AiCollectionProjectionMapper;
import javax.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** t_ai_collection 投影的真实写入者；启用 collection.case-service=ai。 */
@Repository
@Primary
@ConditionalOnProperty(prefix = "collection", name = "case-service", havingValue = "ai")
public class AiCaseProjectionRepository implements CaseProjectionRepository {

    private static final String PENDING = "PENDING";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String SKIPPED = "SKIPPED";

    @Resource private AiCollectionProjectionMapper projectionMapper;
    @Resource private AiCollectionInboxMapper inboxMapper;

    @Override
    @Transactional
    public Outcome apply(CaseProjectionCommand command) {
        String existing = inboxMapper.selectPublishStatus(command.getEventId());
        if (existing != null) {
            return PENDING.equals(existing) ? Outcome.PENDING_PUBLISH : Outcome.ALREADY_PROCESSED;
        }
        CaseProjection projection = command.getProjection();
        boolean applied = upsert(projection);
        String publishStatus = applied && command.isPublishRequired() ? PENDING : SKIPPED;
        inboxMapper.insertIgnoreDuplicate(row(command, applied, publishStatus));
        if (!applied) {
            return Outcome.STALE_VERSION;
        }
        return command.isPublishRequired() ? Outcome.APPLIED : Outcome.APPLIED_WITHOUT_EVENT;
    }

    @Override
    @Transactional
    public Outcome applyRepaymentDelta(CaseProjectionCommand command) {
        String existing = inboxMapper.selectPublishStatus(command.getEventId());
        if (existing != null) {
            return PENDING.equals(existing) ? Outcome.PENDING_PUBLISH : Outcome.ALREADY_PROCESSED;
        }
        CaseProjection delta = command.getProjection();
        CaseProjection current = projectionMapper.selectProjectionForUpdate(delta.getCaseId());
        if (current == null) {
            throw new MissingCaseBaselineException(delta.getCaseId());
        }
        if (delta.getUpdatedAt() != null
                && current.getUpdatedAt() != null
                && delta.getUpdatedAt().isBefore(current.getUpdatedAt())) {
            inboxMapper.insertIgnoreDuplicate(row(command, false, SKIPPED));
            return Outcome.STALE_VERSION;
        }
        mergeRepayment(current, delta);
        boolean applied = projectionMapper.updateRepaymentDelta(current) == 1;
        String publishStatus = applied && command.isPublishRequired() ? PENDING : SKIPPED;
        inboxMapper.insertIgnoreDuplicate(row(command, applied, publishStatus));
        return applied
                ? command.isPublishRequired() ? Outcome.APPLIED : Outcome.APPLIED_WITHOUT_EVENT
                : Outcome.STALE_VERSION;
    }

    @Override
    public void markEventPublished(String eventId) {
        inboxMapper.markPublished(eventId);
    }

    /**
     * 行锁下比较内容指纹与事实时间：不存在则插入，指纹相同或快照更旧都不覆盖。
     *
     * <p>时间比较不是为了防上游乱序（数仓按批次顺序发布），而是为了防 Pub/Sub at-least-once 造成的**重投乱序**： 先发的旧快照处理失败被
     * nack、后发的新快照先落库，旧消息重试回来时 `eventId` 去重帮不上忙（它从未成功处理过）， 只比指纹就会把旧数据盖回去。只拒绝**严格更早**的 {@code
     * updatedAt}，因此等时刻的每日快照刷新不受影响； {@code updatedAt} 来自 payload 的 {@code occurredAt}，缺失即在接入侧
     * poison，故此处可信。
     */
    private boolean upsert(CaseProjection projection) {
        CaseProjection current = projectionMapper.selectProjectionForUpdate(projection.getCaseId());
        if (current == null) {
            return projectionMapper.insert(projection) == 1;
        }
        if (current.getCaseVersion() != null
                && current.getCaseVersion().equals(projection.getCaseVersion())) {
            return false;
        }
        if (projection.getUpdatedAt() != null
                && current.getUpdatedAt() != null
                && projection.getUpdatedAt().isBefore(current.getUpdatedAt())) {
            return false;
        }
        return projectionMapper.updateIfChanged(projection) == 1;
    }

    private AiCollectionInboxRow row(
            CaseProjectionCommand command, boolean applied, String publishStatus) {
        AiCollectionInboxRow row = new AiCollectionInboxRow();
        row.setEventId(command.getEventId());
        row.setCaseId(command.getProjection().getCaseId());
        row.setCaseVersion(command.getProjection().getCaseVersion());
        row.setMessageType(command.getMessageType());
        row.setEventType(command.getEventType());
        row.setPayload(command.getPayload());
        row.setProjectionApplied(applied);
        row.setPublishStatus(publishStatus);
        return row;
    }

    /** 合并还款增量：dpd/stage 需通过自洽性护栏，否则保留基线值由日切纠正。 */
    private void mergeRepayment(CaseProjection current, CaseProjection delta) {
        current.setUserId(delta.getUserId());
        if (RepaymentConsistencyGuard.acceptsDpdAndStage(current, delta)) {
            current.setDpd(delta.getDpd());
            if (delta.isStagePresent()) {
                current.setStage(delta.getStage());
            }
        }
        current.setCollectionStatus(delta.getCollectionStatus());
        current.setOverdueAmount(delta.getOverdueAmount());
        current.setTotalOutstanding(delta.getTotalOutstanding());
        current.setPenaltyAmount(delta.getPenaltyAmount());
        current.setUpcomingAmount(delta.getUpcomingAmount());
        if (delta.isNextDueDatePresent()) {
            current.setNextDueDate(delta.getNextDueDate());
        }
        current.setUpdatedAt(delta.getUpdatedAt());
    }

    /** 未确认发布的收件箱条数；持续大于 0 说明领域事件补发链路有问题。 */
    public long countPendingPublish() {
        return inboxMapper.countPendingPublish();
    }
}
