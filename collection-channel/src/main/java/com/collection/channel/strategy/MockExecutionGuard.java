package com.collection.channel.strategy;

import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.spi.ExecutionGuard;
import org.springframework.stereotype.Component;

/**
 * Phase 1 Mock 实现 —— ComplianceExecutionGuard 的占位。对应 SPI {@link ExecutionGuard}。
 *
 * <p>真实实现：渠道编排负责人基于 Redis 合规计数器做频率/时段/放弃率校验（硬超时 20ms，单次 Redis 交互）。
 * 本 Mock 恒放行。
 */
@Component
public class MockExecutionGuard implements ExecutionGuard {

    @Override
    public GuardVerdict evaluate(ExecutionContext context) {
        return GuardVerdict.allow();
    }
}
