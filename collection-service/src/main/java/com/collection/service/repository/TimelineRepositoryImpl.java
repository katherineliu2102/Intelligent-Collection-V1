package com.collection.service.repository;

import com.collection.common.model.ContactRecord;
import com.collection.common.repository.TimelineRepository;
import com.collection.service.mapper.ContactTimelineMapper;
import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.stereotype.Repository;

@Repository
public class TimelineRepositoryImpl implements TimelineRepository {

    @Resource private ContactTimelineMapper timelineMapper;

    @Override
    public void writeTimeline(ContactRecord record) {
        timelineMapper.insert(record);
    }

    @Override
    public List<ContactRecord> getContactHistory(Long userId, int limit) {
        return timelineMapper.selectRecentByUser(userId, limit);
    }

    @Override
    public List<ContactRecord> getContactHistory(
            Long userId, LocalDateTime fromInclusive, int limit) {
        return timelineMapper.selectRecentByUserSince(userId, fromInclusive, limit);
    }
}
