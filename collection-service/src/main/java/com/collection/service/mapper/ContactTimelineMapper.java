package com.collection.service.mapper;

import com.collection.common.model.ContactRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * t_contact_timeline 持久化。
 */
@Mapper
public interface ContactTimelineMapper {

    @Insert("INSERT INTO t_contact_timeline " +
            "(case_id, user_id, plan_id, step_id, channel, direction, template_id, content_summary, " +
            " result, provider_msg_id, provider_callback, cost, source, created_at) " +
            "VALUES " +
            "(#{caseId}, #{userId}, #{planId}, #{stepId}, #{channel}, #{direction}, #{templateId}, #{contentSummary}, " +
            " #{result}, #{providerMsgId}, #{providerCallback}, #{cost}, #{source}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContactRecord record);

    @Select("SELECT * FROM t_contact_timeline WHERE user_id = #{userId} " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<ContactRecord> selectRecentByUser(@Param("userId") Long userId, @Param("limit") int limit);
}
