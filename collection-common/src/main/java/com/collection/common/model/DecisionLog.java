package com.collection.common.model;

import com.collection.common.enums.DecisionType;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 决策日志。供数仓分析与 Phase 2 模型训练，引擎只写不读。 Phase 1 仅在 StepResolver（④）解析成功后写一条 step 级记录（engineType=RULE /
 * confidence=1.0）； Guard 拦截、推进/穷尽决策 Phase 2 再补记。对应领域模型 §3.3 / 表 t_decision_log。
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
