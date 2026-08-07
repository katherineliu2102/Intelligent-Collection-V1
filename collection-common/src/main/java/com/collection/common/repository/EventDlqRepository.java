package com.collection.common.repository;

import com.collection.common.model.EventDlq;
import java.util.List;

/** t_event_dlq 持久化与受控重放的并发状态迁移。 */
public interface EventDlqRepository {
    void upsert(EventDlq event);

    EventDlq findByEventId(String eventId);

    /** PENDING 原子占用为 REDRIVING；false 表示已被其他操作者处置。 */
    boolean claimForRedrive(String eventId, String reason, int maxRedriveCount);

    void markRedriven(String eventId);

    void markTerminated(String eventId, String reason);

    List<EventDlq> findByEventIds(List<String> eventIds);
}
