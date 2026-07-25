package com.collection.common.repository;

import com.collection.common.model.ContactRecord;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 触达时间线持久层。对应基础设施规范 §5 writeTimeline / getContactHistory。 表 t_contact_timeline（跨模块共写）。实现位于
 * collection-service。
 */
public interface TimelineRepository {

    void writeTimeline(ContactRecord record);

    /** 近期触达历史（按时间倒序），用于 ExecutionContext.recentTimeline。 */
    List<ContactRecord> getContactHistory(Long userId, int limit);

    /** 按菲律宾业务窗口取历史；默认实现保留旧 Mock 兼容，生产实现必须在 SQL 过滤。 */
    default List<ContactRecord> getContactHistory(
            Long userId, LocalDateTime fromInclusive, int limit) {
        return getContactHistory(userId, limit);
    }
}
