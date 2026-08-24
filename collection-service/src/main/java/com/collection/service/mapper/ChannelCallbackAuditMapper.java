package com.collection.service.mapper;

import com.collection.common.model.ChannelCallbackAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChannelCallbackAuditMapper {

    @Insert(
            "INSERT INTO t_channel_callback_audit "
                    + "(plan_id, step_id, case_id, provider_msg_id, result, disposition, canonical_payload, signature, signature_valid, received_at) "
                    + "VALUES (#{planId}, #{stepId}, #{caseId}, #{providerMsgId}, #{result}, #{disposition}, #{canonicalPayload}, #{signature}, #{signatureValid}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChannelCallbackAudit audit);

    /** 已成功发出 CHANNEL_CALLBACK 的审计：验签通过且已解析出 plan/step（身份未解析的重试不得被当成幂等命中）。 */
    @Select(
            "SELECT COUNT(1) FROM t_channel_callback_audit "
                    + "WHERE provider_msg_id = #{providerMsgId} AND signature_valid = 1 "
                    + "AND plan_id IS NOT NULL AND step_id IS NOT NULL")
    int countValidByProviderMsgId(@Param("providerMsgId") String providerMsgId);
}
