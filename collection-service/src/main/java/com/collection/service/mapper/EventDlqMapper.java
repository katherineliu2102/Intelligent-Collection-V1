package com.collection.service.mapper;

import com.collection.common.model.EventDlq;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EventDlqMapper {
    @Insert(
            "INSERT INTO t_event_dlq (event_id,event_type,payload,failure_reason,delivery_count,"
                    + "first_failed_at,last_failed_at,status,created_at) VALUES "
                    + "(#{eventId},#{eventType},CAST(#{payload} AS JSON),#{failureReason},#{deliveryCount},"
                    + "NOW(),NOW(),'PENDING',NOW()) ON DUPLICATE KEY UPDATE "
                    + "failure_reason=VALUES(failure_reason), delivery_count=GREATEST(delivery_count,VALUES(delivery_count)),"
                    + "last_failed_at=NOW()")
    int upsert(EventDlq event);

    @Select("SELECT * FROM t_event_dlq WHERE event_id=#{eventId}")
    EventDlq selectByEventId(@Param("eventId") String eventId);

    @Update(
            "UPDATE t_event_dlq SET status='REDRIVING', redrive_reason=#{reason}, redrive_count=redrive_count+1 "
                    + "WHERE event_id=#{eventId} AND status='PENDING' AND redrive_count < #{maxRedriveCount}")
    int claimForRedrive(
            @Param("eventId") String eventId,
            @Param("reason") String reason,
            @Param("maxRedriveCount") int maxRedriveCount);

    @Update(
            "UPDATE t_event_dlq SET status='REDRIVEN', redriven_at=NOW() WHERE event_id=#{eventId} AND status='REDRIVING'")
    int markRedriven(@Param("eventId") String eventId);

    @Update(
            "UPDATE t_event_dlq SET status='TERMINATED', failure_reason=#{reason}, terminated_at=NOW() "
                    + "WHERE event_id=#{eventId}")
    int markTerminated(@Param("eventId") String eventId, @Param("reason") String reason);

    @Select({
        "<script>SELECT * FROM t_event_dlq WHERE event_id IN ",
        "<foreach item='id' collection='eventIds' open='(' separator=',' close=')'>#{id}</foreach></script>"
    })
    List<EventDlq> selectByEventIds(@Param("eventIds") List<String> eventIds);
}
