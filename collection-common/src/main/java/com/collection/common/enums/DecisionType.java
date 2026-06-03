package com.collection.common.enums;

/**
 * 决策类型。对应领域模型 §6.5。写入 t_decision_log.decision_type。
 */
public enum DecisionType {
    ASSIGNMENT,
    CHANNEL_SELECT,
    SCRIPT_SELECT,
    TIMING,
    CHANNEL_MODE_SELECT
}
