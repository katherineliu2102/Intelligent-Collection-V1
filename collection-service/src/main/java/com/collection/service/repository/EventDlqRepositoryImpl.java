package com.collection.service.repository;

import com.collection.common.model.EventDlq;
import com.collection.common.repository.EventDlqRepository;
import com.collection.service.mapper.EventDlqMapper;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.stereotype.Repository;

@Repository
public class EventDlqRepositoryImpl implements EventDlqRepository {
    @Resource private EventDlqMapper mapper;

    @Override
    public void upsert(EventDlq event) {
        mapper.upsert(event);
    }

    @Override
    public EventDlq findByEventId(String eventId) {
        return mapper.selectByEventId(eventId);
    }

    @Override
    public boolean claimForRedrive(String eventId, String reason, int max) {
        return mapper.claimForRedrive(eventId, reason, max) == 1;
    }

    @Override
    public void markRedriven(String eventId) {
        mapper.markRedriven(eventId);
    }

    @Override
    public void markTerminated(String eventId, String reason) {
        mapper.markTerminated(eventId, reason);
    }

    @Override
    public List<EventDlq> findByEventIds(List<String> ids) {
        return mapper.selectByEventIds(ids);
    }
}
