package com.collection.service.mapper;

import com.collection.common.model.OutboxEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** t_event_outbox 持久化。 */
@Mapper
public interface EventOutboxMapper {

    /** 同 event_id 重入（事件重投导致同一派生事件再次入箱）时保持原记录不动，避免重置重发进度。 */
    @Insert(
            "INSERT INTO t_event_outbox (event_id, event_type, plan_id, case_id, payload, status, "
                    + "retry_count, next_retry_at, created_at, updated_at) VALUES "
                    + "(#{eventId}, #{eventType}, #{planId}, #{caseId}, CAST(#{payload} AS JSON), 'PENDING', "
                    + "0, #{nextRetryAt}, NOW(), NOW()) "
                    + "ON DUPLICATE KEY UPDATE id = id")
    int insertIgnoreDuplicate(OutboxEvent event);

    @Update(
            "UPDATE t_event_outbox SET status='PUBLISHED', published_at=NOW(), last_error=NULL "
                    + "WHERE event_id=#{eventId} AND status='PENDING'")
    int markPublished(@Param("eventId") String eventId);

    @Select(
            "SELECT * FROM t_event_outbox WHERE status='PENDING' AND next_retry_at <= #{now} "
                    + "ORDER BY next_retry_at LIMIT #{limit}")
    List<OutboxEvent> selectDueForRepublish(
            @Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update(
            "UPDATE t_event_outbox SET retry_count=retry_count+1, next_retry_at=#{nextRetryAt}, "
                    + "last_error=#{lastError} WHERE event_id=#{eventId} AND status='PENDING'")
    int scheduleRetry(
            @Param("eventId") String eventId,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("lastError") String lastError);

    @Update(
            "UPDATE t_event_outbox SET status='FAILED', last_error=#{lastError} "
                    + "WHERE event_id=#{eventId} AND status='PENDING'")
    int markFailed(@Param("eventId") String eventId, @Param("lastError") String lastError);

    @Select(
            "SELECT COUNT(1) FROM t_event_outbox WHERE status='PENDING' AND next_retry_at <= #{now}")
    long countPending(@Param("now") LocalDateTime now);
}
