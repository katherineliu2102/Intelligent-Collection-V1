package com.collection.service.mapper;

import com.collection.common.model.DecisionLog;
import org.apache.ibatis.annotations.*;

/**
 * t_decision_log 持久化。
 */
@Mapper
public interface DecisionLogMapper {

    @Insert("INSERT INTO t_decision_log " +
            "(case_id, plan_id, step_id, decision_type, engine_type, engine_version, " +
            " input_snapshot, output_decision, reasoning, confidence, latency_ms, created_at) " +
            "VALUES " +
            "(#{caseId}, #{planId}, #{stepId}, #{decisionType}, #{engineType}, #{engineVersion}, " +
            " #{inputSnapshot}, #{outputDecision}, #{reasoning}, #{confidence}, #{latencyMs}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DecisionLog log);
}
