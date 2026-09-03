package com.collection.service.repository;

import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.repository.CaseProjectionRepository;
import com.collection.common.repository.MissingCaseBaselineException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 本地 / CI 默认投影仓储（与 {@code MockCaseService} 同层）。保留内容指纹比较与幂等语义， 使接入链路不接数据库也能跑通；{@code
 * collection.case-service=ai} 时由 {@link AiCaseProjectionRepository} 顶掉。
 */
@Repository
public class InMemoryCaseProjectionRepository implements CaseProjectionRepository {

    private final Map<Long, String> versions = new ConcurrentHashMap<>();
    private final Map<Long, CaseProjection> projections = new ConcurrentHashMap<>();
    private final Map<String, Boolean> inbox = new ConcurrentHashMap<>();

    @Override
    public synchronized Outcome apply(CaseProjectionCommand command) {
        Boolean pendingPublish = inbox.get(command.getEventId());
        if (pendingPublish != null) {
            return pendingPublish ? Outcome.PENDING_PUBLISH : Outcome.ALREADY_PROCESSED;
        }
        CaseProjection projection = command.getProjection();
        CaseProjection stored = projections.get(projection.getCaseId());
        boolean applied = applySnapshot(stored, projection);
        boolean awaitingPublish = applied && command.isPublishRequired();
        inbox.put(command.getEventId(), awaitingPublish);
        if (!applied) {
            return Outcome.STALE_VERSION;
        }
        return command.isPublishRequired() ? Outcome.APPLIED : Outcome.APPLIED_WITHOUT_EVENT;
    }

    private boolean applySnapshot(CaseProjection stored, CaseProjection projection) {
        if (stored == null) {
            versions.put(projection.getCaseId(), projection.getCaseVersion());
            projections.put(projection.getCaseId(), projection);
            return true;
        }
        if (projection.getOwnerDate() != null
                && stored.getOwnerDate() != null
                && projection.getOwnerDate().isBefore(stored.getOwnerDate())) {
            return false;
        }
        if (stored.getCaseVersion() != null
                && stored.getCaseVersion().equals(projection.getCaseVersion())) {
            stored.setOwner(projection.getOwner());
            stored.setOwnerDate(projection.getOwnerDate());
            return true;
        }
        if (isOlderThanStored(projection)) {
            return false;
        }
        versions.put(projection.getCaseId(), projection.getCaseVersion());
        projections.put(projection.getCaseId(), projection);
        return true;
    }

    @Override
    public synchronized Outcome applyRepaymentDelta(CaseProjectionCommand command) {
        Boolean pendingPublish = inbox.get(command.getEventId());
        if (pendingPublish != null) {
            return pendingPublish ? Outcome.PENDING_PUBLISH : Outcome.ALREADY_PROCESSED;
        }
        CaseProjection delta = command.getProjection();
        CaseProjection current = projections.get(delta.getCaseId());
        if (current == null) {
            throw new MissingCaseBaselineException(delta.getCaseId());
        }
        if (delta.getUpdatedAt() != null
                && current.getUpdatedAt() != null
                && delta.getUpdatedAt().isBefore(current.getUpdatedAt())) {
            inbox.put(command.getEventId(), false);
            return Outcome.STALE_VERSION;
        }
        // dpd/stage 需通过自洽性护栏：与 AiCaseProjectionRepository 同口径，否则 L4a 与 L4b 会对同一条重投给出不同结论
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
        inbox.put(command.getEventId(), command.isPublishRequired());
        return command.isPublishRequired() ? Outcome.APPLIED : Outcome.APPLIED_WITHOUT_EVENT;
    }

    /**
     * 与 {@link AiCaseProjectionRepository#upsert} 同口径：更旧的完整快照不得覆盖已落库的投影。
     *
     * <p>两处必须同构，否则 L4a（内存）与 L4b（真库）会对同一条重投消息给出不同结论，测试结果不可互推。
     */
    private boolean isOlderThanStored(CaseProjection projection) {
        CaseProjection stored = projections.get(projection.getCaseId());
        return stored != null
                && projection.getUpdatedAt() != null
                && stored.getUpdatedAt() != null
                && projection.getUpdatedAt().isBefore(stored.getUpdatedAt());
    }

    @Override
    public synchronized void markEventPublished(String eventId) {
        if (inbox.containsKey(eventId)) {
            inbox.put(eventId, false);
        }
    }
}
