package com.collection.service.repository;

import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.repository.CaseProjectionRepository;
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
    public void markEventPublished(String eventId) {
        inboxMapper.markPublished(eventId);
    }

    /** 行锁下比较版本：不存在则插入，更高版本才覆盖，其余一律判定为迟到消息。 */
    private boolean upsert(CaseProjection projection) {
        Long current = projectionMapper.selectVersionForUpdate(projection.getCaseId());
        if (current == null) {
            return projectionMapper.insert(projection) == 1;
        }
        if (current >= projection.getCaseVersion()) {
            return false;
        }
        return projectionMapper.updateIfNewer(projection) == 1;
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

    /** 未确认发布的收件箱条数；持续大于 0 说明领域事件补发链路有问题。 */
    public long countPendingPublish() {
        return inboxMapper.countPendingPublish();
    }
}
