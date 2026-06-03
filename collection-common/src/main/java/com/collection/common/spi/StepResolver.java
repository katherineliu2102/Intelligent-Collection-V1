package com.collection.common.spi;

import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.StepCommand;

/**
 * SPI — 步骤解析。对应核心引擎规格 §4.1，骨架步骤④。
 *
 * <p>决策问题：具体发什么、用什么渠道？基于 context_snapshot 生成 StepCommand（零 DB I/O）。
 * <p>引擎应对：抛异常 → 标记 FAILED → publish(STEP_COMPLETED) → 由 AdvancementPolicy 推进。硬超时 50ms。
 * <p>null 语义：不允许返回 null（须抛异常触发 FAILED）。
 * <p>副作用约束：禁止写 DB / 发布事件 / 调用外部服务。Phase 1 永不输出 channelType=HUMAN_CALL（对齐待办 E4）。
 */
public interface StepResolver {

    StepCommand resolve(ExecutionContext context);
}
