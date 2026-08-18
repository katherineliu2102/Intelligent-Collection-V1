package com.collection.common.dto;

import com.collection.common.enums.ContactResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 步骤结果。ChannelGateway.dispatch() 的输出，同时作为 AdvancementPolicy.decide() 输入之一。 对应领域模型 §5.5。
 *
 * <p>success 由渠道层根据 contactResult 设置：FAILED 类 → false，其余 → true。 引擎仅读 success
 * 决定是否进入故障降级；AdvancementPolicy 读 contactResult 做业务决策。
 */
@Getter
@Builder
@AllArgsConstructor
public class StepResult {

    private final boolean success;
    private final ContactResult contactResult;
    private final String errorCode;
    /**
     * 仅在渠道能<b>证明请求未写给供应商</b>时为 true——熔断未调用、凭证缺失、DNS/连接被拒、供应商显式 429 拒绝受理。
     *
     * <p>读超时、写后连接中断、供应商 5xx 均属结果未知，必须为 false：引擎重试会让 {@code idempotencyKey} 的 {@code retryCount}
     * 加一，供应商即便有去重也不会命中，重试等价于重复发送。
     */
    private final boolean retryable;
    /** 供应商消息/通话 ID，回调关联与对账。 */
    private final String providerMsgId;
}
