package com.collection.service.repository;

import com.collection.common.model.DecisionLog;
import com.collection.common.repository.DecisionLogRepository;
import com.collection.service.mapper.DecisionLogMapper;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;

@Repository
public class DecisionLogRepositoryImpl implements DecisionLogRepository {

    @Resource
    private DecisionLogMapper decisionLogMapper;

    @Override
    public void save(DecisionLog log) {
        decisionLogMapper.insert(log);
    }
}
