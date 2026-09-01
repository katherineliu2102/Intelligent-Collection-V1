package com.collection.service.mapper;

import com.collection.common.model.EmailSuppression;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * t_email_suppression 持久化。
 *
 * <p>{@code created_at} 同 {@link ContactTimelineMapper} 由调用方以 {@code Asia/Manila} 传入，不用 {@code
 * NOW()}：库端 {@code system_time_zone=UTC}，抑制发生时间是人工判读退信何时开始的依据。
 */
@Mapper
public interface EmailSuppressionMapper {

    /**
     * 首次抑制原因即最终原因：hard bounce 之后再来一条 spam_report 不应把「地址不存在」改写成「被投诉」， 前者才是需要人工核对的事实。故用 INSERT IGNORE
     * 而非 ON DUPLICATE KEY UPDATE。
     */
    @Insert(
            "INSERT IGNORE INTO t_email_suppression (email, reason, detail, case_id, created_at) "
                    + "VALUES (#{email}, #{reason}, #{detail}, #{caseId}, #{createdAt})")
    int insertIgnore(EmailSuppression suppression);

    @Select("SELECT COUNT(1) FROM t_email_suppression WHERE email = #{email}")
    int countByEmail(@Param("email") String email);
}
