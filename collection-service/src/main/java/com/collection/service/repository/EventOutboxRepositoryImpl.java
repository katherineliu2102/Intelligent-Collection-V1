package com.collection.service.repository;

import com.collection.common.model.OutboxEvent;
import com.collection.common.repository.EventOutboxRepository;
import com.collection.service.mapper.EventOutboxMapper;
import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EventOutboxRepositoryImpl implements EventOutboxRepository {

    @Resource private EventOutboxMapper mapper;

    /** 必须并入调用方的业务事务：单独提交就退化成"提交后发布"，发件箱失去意义。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OutboxEvent event) {
        mapper.insertIgnoreDuplicate(event);
    }

    @Override
    public boolean markPublished(String eventId) {
        return mapper.markPublished(eventId) == 1;
    }

    @Override
    @Transactional
    public List<OutboxEvent> claimDueForRepublish(
            LocalDateTime now, LocalDateTime leaseUntil, int limit) {
        List<OutboxEvent> candidates = mapper.selectClaimableForRepublish(now, limit);
        return candidates.stream()
                .filter(row -> mapper.claimForRepublish(row.getEventId(), now, leaseUntil) == 1)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public void scheduleRetry(String eventId, LocalDateTime nextRetryAt, String lastError) {
        mapper.scheduleRetry(eventId, nextRetryAt, lastError);
    }

    @Override
    public void markFailed(String eventId, String lastError) {
        mapper.markFailed(eventId, lastError);
    }

    @Override
    public long countPending(LocalDateTime now) {
        return mapper.countPending(now);
    }
}
