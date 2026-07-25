package com.collection.service.mapper;

import com.collection.common.model.ChannelCallbackAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface ChannelCallbackAuditMapper {

    @Insert(
            "INSERT INTO t_channel_callback_audit "
                    + "(plan_id, step_id, case_id, provider_msg_id, result, disposition, canonical_payload, signature, signature_valid, received_at) "
                    + "VALUES (#{planId}, #{stepId}, #{caseId}, #{providerMsgId}, #{result}, #{disposition}, #{canonicalPayload}, #{signature}, #{signatureValid}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChannelCallbackAudit audit);
}
