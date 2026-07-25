package com.collection.service.repository;

import com.collection.common.model.ChannelCallbackAudit;
import com.collection.common.repository.ChannelCallbackAuditRepository;
import com.collection.service.mapper.ChannelCallbackAuditMapper;
import javax.annotation.Resource;
import org.springframework.stereotype.Repository;

@Repository
public class ChannelCallbackAuditRepositoryImpl implements ChannelCallbackAuditRepository {

    @Resource private ChannelCallbackAuditMapper mapper;

    @Override
    public void save(ChannelCallbackAudit audit) {
        mapper.insert(audit);
    }
}
