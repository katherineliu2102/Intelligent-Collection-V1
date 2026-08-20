package com.collection.common.repository;

import com.collection.common.model.ChannelCallbackAudit;

/** 保留供应商回调审计，不参与触达频控与步骤推进。 */
public interface ChannelCallbackAuditRepository {

    void save(ChannelCallbackAudit audit);
}
