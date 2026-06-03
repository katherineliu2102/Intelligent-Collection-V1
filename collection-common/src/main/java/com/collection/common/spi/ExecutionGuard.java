package com.collection.common.spi;

import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;

/**
 * SPI — 执行守卫（业务级合规校验）。对应核心引擎规格 §4.1，骨架步骤③。
 *
 * <p>决策问题：这一步允许执行吗？（合规频率 / 时段 / 放弃率）
 * <p>引擎应对：抛异常 → fail-close（标记 SKIPPED + 告警，推进下一步）。硬超时 20ms。
 * <p>null 语义：不允许返回 null。
 * <p>副作用约束：禁止写 DB / 发布事件 / 调用外部服务（可读 Redis 合规计数器）。
 */
public interface ExecutionGuard {

    GuardVerdict evaluate(ExecutionContext context);
}
