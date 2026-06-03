package com.collection.common.repository;

import com.collection.common.model.ContactRecord;

import java.util.List;

/**
 * 触达时间线持久层。对应基础设施规范 §5 writeTimeline / getContactHistory。
 * 表 t_contact_timeline（跨模块共写）。实现位于 collection-service。
 */
public interface TimelineRepository {

    void writeTimeline(ContactRecord record);

    /** 近期触达历史（按时间倒序），用于 ExecutionContext.recentTimeline。 */
    List<ContactRecord> getContactHistory(Long userId, int limit);
}
