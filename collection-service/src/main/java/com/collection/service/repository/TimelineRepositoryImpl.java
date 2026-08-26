package com.collection.service.repository;

import com.collection.common.enums.ContactResult;
import com.collection.common.model.ContactRecord;
import com.collection.common.repository.TimelineRepository;
import com.collection.service.mapper.ContactTimelineMapper;
import com.collection.service.support.ServiceClock;
import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.stereotype.Repository;

@Repository
public class TimelineRepositoryImpl implements TimelineRepository {

    @Resource private ContactTimelineMapper timelineMapper;

    @Override
    public void writeTimeline(ContactRecord record) {
        // 重复投递会走 ON DUPLICATE KEY UPDATE，不覆盖 created_at，故首写时间即为频控计数依据。
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(ServiceClock.now());
        }
        timelineMapper.insert(record);
    }

    @Override
    public int upgradeResult(
            String attemptKey,
            ContactResult result,
            String providerMsgId,
            String providerCallback) {
        return timelineMapper.upgradeResultByAttemptKey(
                attemptKey, result.name(), providerMsgId, providerCallback);
    }

    @Override
    public int overrideResult(
            String attemptKey,
            ContactResult result,
            String providerMsgId,
            String providerCallback) {
        return timelineMapper.overrideResultByAttemptKey(
                attemptKey, result.name(), providerMsgId, providerCallback);
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

    @Override
    public List<ContactRecord> getContactHistoryByCase(
            Long caseId, LocalDateTime fromInclusive, int limit) {
        return timelineMapper.selectRecentByCaseSince(caseId, fromInclusive, limit);
    }
}
