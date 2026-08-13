package com.collection.service.repository;

import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.repository.CaseProjectionRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 本地 / CI 默认投影仓储（与 {@code MockCaseService} 同层）。保留版本比较与幂等语义，
 * 使接入链路不接数据库也能跑通；{@code collection.case-service=ai} 时由 {@link AiCaseProjectionRepository} 顶掉。
 */
@Repository
public class InMemoryCaseProjectionRepository implements CaseProjectionRepository {

    private final Map<Long, Long> versions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> inbox = new ConcurrentHashMap<>();

    @Override
    public synchronized Outcome apply(CaseProjectionCommand command) {
        Boolean pendingPublish = inbox.get(command.getEventId());
        if (pendingPublish != null) {
            return pendingPublish ? Outcome.PENDING_PUBLISH : Outcome.ALREADY_PROCESSED;
        }
        CaseProjection projection = command.getProjection();
        Long current = versions.get(projection.getCaseId());
        boolean applied = current == null || current < projection.getCaseVersion();
        if (applied) {
            versions.put(projection.getCaseId(), projection.getCaseVersion());
        }
        boolean awaitingPublish = applied && command.isPublishRequired();
        inbox.put(command.getEventId(), awaitingPublish);
        if (!applied) {
            return Outcome.STALE_VERSION;
        }
        return command.isPublishRequired() ? Outcome.APPLIED : Outcome.APPLIED_WITHOUT_EVENT;
    }

    @Override
    public synchronized void markEventPublished(String eventId) {
        if (inbox.containsKey(eventId)) {
            inbox.put(eventId, false);
        }
    }
}
