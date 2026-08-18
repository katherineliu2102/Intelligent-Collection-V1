package com.collection.service.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** t_ai_collection_inbox 持久化。event_id 唯一键即入站幂等边界。 */
@Mapper
public interface AiCollectionInboxMapper {

    @Insert(
            "INSERT INTO t_ai_collection_inbox (event_id, case_id, case_version, message_type, "
                    + "event_type, payload, projection_applied, publish_status, created_at, updated_at) VALUES "
                    + "(#{eventId}, #{caseId}, #{caseVersion}, #{messageType}, #{eventType}, "
                    + "CAST(#{payload} AS JSON), #{projectionApplied}, #{publishStatus}, NOW(), NOW()) "
                    + "ON DUPLICATE KEY UPDATE id = id")
    int insertIgnoreDuplicate(AiCollectionInboxRow row);

    @Select("SELECT publish_status FROM t_ai_collection_inbox WHERE event_id = #{eventId}")
    String selectPublishStatus(@Param("eventId") String eventId);

    @Update(
            "UPDATE t_ai_collection_inbox SET publish_status = 'PUBLISHED', published_at = NOW() "
                    + "WHERE event_id = #{eventId} AND publish_status = 'PENDING'")
    int markPublished(@Param("eventId") String eventId);

    @Select("SELECT COUNT(1) FROM t_ai_collection_inbox WHERE publish_status = 'PENDING'")
    long countPendingPublish();
}
