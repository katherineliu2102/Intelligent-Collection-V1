package com.collection.common.repository;

import com.collection.common.model.DecisionLog;

/**
 * 决策日志持久层。表 t_decision_log。引擎只写不读。实现位于 collection-service。
 */
public interface DecisionLogRepository {

    void save(DecisionLog log);
}
