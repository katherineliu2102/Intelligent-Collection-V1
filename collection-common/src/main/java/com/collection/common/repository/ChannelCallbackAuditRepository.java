package com.collection.common.repository;

import com.collection.common.model.ChannelCallbackAudit;

/** 保留供应商回调审计，不参与触达频控与步骤推进。 */
public interface ChannelCallbackAuditRepository {

    void save(ChannelCallbackAudit audit);

    /** 已成功验签并落过审计的 session / provider 键；用于 Facade session_id 幂等。 */
    boolean existsValidByProviderMsgId(String providerMsgId);
}
