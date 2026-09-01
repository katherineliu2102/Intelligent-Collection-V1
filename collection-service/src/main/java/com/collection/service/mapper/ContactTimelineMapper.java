package com.collection.service.mapper;

import com.collection.common.model.ContactRecord;
import java.util.List;
import org.apache.ibatis.annotations.*;

/**
 * t_contact_timeline 持久化。
 *
 * <p>{@code created_at} 由调用方以 {@code Asia/Manila} 传入（{@link
 * com.collection.service.support.ServiceClock}），不使用 {@code NOW()}：本列是当日触达频控的比较列， 库端会话时区一旦为 UTC，PHT
 * 00:00–08:00 的记录会被漏计，导致频控超发。
 */
@Mapper
public interface ContactTimelineMapper {

    @Insert(
            "INSERT INTO t_contact_timeline "
                    + "(case_id, user_id, plan_id, step_id, attempt_key, channel, direction, template_id, config_version, rendered_ref, content_summary, script_slot, template_version, content_hmac, content_key_id, "
                    + " result, provider_msg_id, provider_callback, cost, source, created_at) "
                    + "VALUES "
                    + "(#{caseId}, #{userId}, #{planId}, #{stepId}, #{attemptKey}, #{channel}, #{direction}, #{templateId}, #{configVersion}, #{renderedRef}, #{contentSummary}, #{scriptSlot}, #{templateVersion}, #{contentHmac}, #{contentKeyId}, "
                    + " #{result}, #{providerMsgId}, #{providerCallback}, #{cost}, #{source}, #{createdAt}) "
                    + "ON DUPLICATE KEY UPDATE result = VALUES(result), provider_msg_id = COALESCE(VALUES(provider_msg_id), provider_msg_id), "
                    + "provider_callback = COALESCE(VALUES(provider_callback), provider_callback), "
                    + "config_version = COALESCE(config_version, VALUES(config_version)), "
                    + "rendered_ref = COALESCE(rendered_ref, VALUES(rendered_ref)), "
                    + "script_slot = COALESCE(script_slot, VALUES(script_slot)), "
                    + "template_version = COALESCE(template_version, VALUES(template_version)), "
                    + "content_hmac = COALESCE(content_hmac, VALUES(content_hmac)), "
                    + "content_key_id = COALESCE(content_key_id, VALUES(content_key_id))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContactRecord record);

    /**
     * 沿升级链前进。{@code FIELD()} 把链序表达进 SQL，使「只升不降」不依赖先读后写。
     *
     * <p>{@code result IN (...)} 的白名单是必要的：{@code FIELD()} 对链外值（终态、脏值）返回 0， 单看 {@code 0 < 2} 会把一行
     * REJECTED 升成 READ。
     */
    @Update(
            "UPDATE t_contact_timeline SET result = #{result}, "
                    + "provider_msg_id = COALESCE(provider_msg_id, #{providerMsgId}), "
                    + "provider_callback = #{providerCallback} "
                    + "WHERE attempt_key = #{attemptKey} AND ("
                    + "  result IS NULL OR ("
                    + "    result IN ('DELIVERED','READ','CLICKED','REPLIED') AND "
                    + "    FIELD(result,'DELIVERED','READ','CLICKED','REPLIED') "
                    + "      < FIELD(#{result},'DELIVERED','READ','CLICKED','REPLIED')))")
    int upgradeResultByAttemptKey(
            @Param("attemptKey") String attemptKey,
            @Param("result") String result,
            @Param("providerMsgId") String providerMsgId,
            @Param("providerCallback") String providerCallback);

    /** 无条件改写，用于未送达类事实；重复投递写同一值，天然幂等。 */
    @Update(
            "UPDATE t_contact_timeline SET result = #{result}, "
                    + "provider_msg_id = COALESCE(provider_msg_id, #{providerMsgId}), "
                    + "provider_callback = #{providerCallback} "
                    + "WHERE attempt_key = #{attemptKey}")
    int overrideResultByAttemptKey(
            @Param("attemptKey") String attemptKey,
            @Param("result") String result,
            @Param("providerMsgId") String providerMsgId,
            @Param("providerCallback") String providerCallback);

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

    @Select(
            "SELECT * FROM t_contact_timeline WHERE case_id = #{caseId} "
                    + "AND created_at >= #{fromInclusive} ORDER BY created_at DESC LIMIT #{limit}")
    List<ContactRecord> selectRecentByCaseSince(
            @Param("caseId") Long caseId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("limit") int limit);
}
