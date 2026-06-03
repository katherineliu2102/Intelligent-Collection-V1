package com.collection.common.channel;

import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;

/**
 * 渠道网关（技术执行管道，无业务决策）。对应核心引擎规格 §4.1，骨架步骤⑤。
 *
 * <p>定义于 collection-common，是固定的技术执行管道（区别于可 Phase 2 替换的 5 个 SPI）。
 * 渠道层内部的熔断 / fallback 对引擎完全透明。
 * <p>引擎应对：dispatch 抛运行时异常 → 一律视为 retryable → 退避重试（核心引擎规格 §3.2）。
 */
public interface ChannelGateway {

    StepResult dispatch(StepCommand command);
}
