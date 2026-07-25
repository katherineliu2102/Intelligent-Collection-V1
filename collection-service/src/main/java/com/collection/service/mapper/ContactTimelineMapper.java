package com.collection.service.mapper;

import com.collection.common.model.ContactRecord;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** t_contact_timeline 持久化。 */
@Mapper
public interface ContactTimelineMapper {

    @Insert(
            "INSERT INTO t_contact_timeline "
                    + "(case_id, user_id, plan_id, step_id, attempt_key, channel, direction, template_id, content_summary, "
                    + " result, provider_msg_id, provider_callback, cost, source, created_at) "
                    + "VALUES "
                    + "(#{caseId}, #{userId}, #{planId}, #{stepId}, #{attemptKey}, #{channel}, #{direction}, #{templateId}, #{contentSummary}, "
                    + " #{result}, #{providerMsgId}, #{providerCallback}, #{cost}, #{source}, NOW()) "
                    + "ON DUPLICATE KEY UPDATE result = VALUES(result), provider_msg_id = COALESCE(VALUES(provider_msg_id), provider_msg_id), "
                    + "provider_callback = COALESCE(VALUES(provider_callback), provider_callback)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContactRecord record);

    @Select(
            "SELECT * FROM t_contact_timeline WHERE user_id = #{userId} "
                    + "ORDER BY created_at DESC LIMIT #{limit}")
    List<ContactRecord> selectRecentByUser(@Param("userId") Long userId, @Param("limit") int limit);

    @Select(
            "SELECT * FROM t_contact_timeline WHERE user_id = #{userId} "
                    + "AND created_at >= #{fromInclusive} ORDER BY created_at DESC LIMIT #{limit}")
    List<ContactRecord> selectRecentByUserSince(
            @Param("userId") Long userId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("limit") int limit);
}
