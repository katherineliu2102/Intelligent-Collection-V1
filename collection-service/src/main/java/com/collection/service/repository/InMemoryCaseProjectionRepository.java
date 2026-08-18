package com.collection.service.repository;

import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.repository.CaseProjectionRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 本地 / CI 默认投影仓储（与 {@code MockCaseService} 同层）。保留内容指纹比较与幂等语义，
 * 使接入链路不接数据库也能跑通；{@code collection.case-service=ai} 时由 {@link AiCaseProjectionRepository} 顶掉。
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
        String current = versions.get(projection.getCaseId());
        boolean applied = current == null || !current.equals(projection.getCaseVersion());
        if (applied) {
            versions.put(projection.getCaseId(), projection.getCaseVersion());
            projections.put(projection.getCaseId(), projection);
        }
        boolean awaitingPublish = applied && command.isPublishRequired();
        inbox.put(command.getEventId(), awaitingPublish);
        if (!applied) {
            return Outcome.STALE_VERSION;
        }
        return command.isPublishRequired() ? Outcome.APPLIED : Outcome.APPLIED_WITHOUT_EVENT;
    }

    @Override
    public synchronized Outcome applyRepaymentDelta(CaseProjectionCommand command) {
        Boolean pendingPublish = inbox.get(command.getEventId());
        if (pendingPublish != null) {
            return pendingPublish ? Outcome.PENDING_PUBLISH : Outcome.ALREADY_PROCESSED;
        }
        CaseProjection current = projections.get(command.getProjection().getCaseId());
        if (current == null) {
            throw new IllegalStateException(
                    "repaymentEvent 缺完整 caseEvent 基线，caseId="
                            + command.getProjection().getCaseId());
        }
        CaseProjection delta = command.getProjection();
        current.setUserId(delta.getUserId());
        current.setDpd(delta.getDpd());
        current.setStage(delta.getStage());
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

    @Override
    public synchronized void markEventPublished(String eventId) {
        if (inbox.containsKey(eventId)) {
            inbox.put(eventId, false);
        }
    }
}
