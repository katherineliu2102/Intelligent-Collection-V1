package com.collection.service.repository;

import com.collection.common.model.ContactRecord;
import com.collection.common.repository.TimelineRepository;
import com.collection.service.mapper.ContactTimelineMapper;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.List;

@Repository
public class TimelineRepositoryImpl implements TimelineRepository {

    @Resource
    private ContactTimelineMapper timelineMapper;

    @Override
    public void writeTimeline(ContactRecord record) {
        timelineMapper.insert(record);
    }

    @Override
    public List<ContactRecord> getContactHistory(Long userId, int limit) {
        return timelineMapper.selectRecentByUser(userId, limit);
    }
}
