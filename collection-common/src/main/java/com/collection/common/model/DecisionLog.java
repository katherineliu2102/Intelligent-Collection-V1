package com.collection.common.model;

import com.collection.common.enums.DecisionType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 决策日志。每次 SPI 调用后写入，供数仓分析与 Phase 2 模型训练。
 * 引擎只写不读。对应领域模型 §2.3 / 表 t_decision_log。
 */
@Data
public class DecisionLog {

    private Long id;
    private Long caseId;
    private Long planId;
    private Long stepId;
    private DecisionType decisionType;
    /** RULE / LLM。 */
    private String engineType;
    private String engineVersion;
    /** ExecutionContext 的 JSON 序列化。 */
    private String inputSnapshot;
    /** 决策结果 JSON。 */
    private String outputDecision;
    private String reasoning;
    private double confidence;
    private Integer latencyMs;
    private LocalDateTime createdAt;
}
