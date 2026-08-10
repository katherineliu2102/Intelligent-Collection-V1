package com.collection.common.repository;

import com.collection.common.model.OutboxEvent;
import java.time.LocalDateTime;
import java.util.List;

/**
 * t_event_outbox 持久化。实现位于 collection-service。
 *
 * <p>{@link #enqueue} 必须在产生该事件的业务事务内调用，这是发件箱唯一的正确性前提。
 */
public interface EventOutboxRepository {

    /** 随业务事务落盘。事件已存在（同 eventId 重入）时视为成功，不覆盖既有记录。 */
    void enqueue(OutboxEvent event);

    /** 确认已投递：PENDING → PUBLISHED。返回 false 表示记录不存在或已被处置。 */
    boolean markPublished(String eventId);

    /** 到期未确认投递的记录，供兜底重发。带 LIMIT。 */
    List<OutboxEvent> findDueForRepublish(LocalDateTime now, int limit);

    /** 重发失败：累加次数并推迟下次重发。 */
    void scheduleRetry(String eventId, LocalDateTime nextRetryAt, String lastError);

    /** 重发次数耗尽：PENDING → FAILED，需人工介入。 */
    void markFailed(String eventId, String lastError);

    /** 待投递积压量，作为 gauge 暴露。 */
    long countPending(LocalDateTime now);
}
