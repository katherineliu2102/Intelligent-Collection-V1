package com.collection.common.spi;

import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.AdvancementDecision;

/**
 * SPI — 推进策略。对应核心引擎规格 §4.1、§2.3.2。
 *
 * <p>决策问题：下一步是什么？（推进 / 完成 / 穷尽）
 * <p>引擎应对：抛异常 → NACK 延迟重消费（计划不可无推进决策放任）。硬超时 10ms。
 * <p>null 语义：不允许返回 null。
 * <p>副作用约束：禁止写 DB / 发布事件 / 调用外部服务。
 */
public interface AdvancementPolicy {

    AdvancementDecision decide(ExecutionContext context, StepResult stepResult);
}
