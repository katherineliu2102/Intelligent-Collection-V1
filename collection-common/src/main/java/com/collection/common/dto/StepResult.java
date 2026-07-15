package com.collection.common.dto;

import com.collection.common.enums.ContactResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 步骤结果。ChannelGateway.dispatch() 的输出，同时作为 AdvancementPolicy.decide() 输入之一。
 * 对应领域模型 §5.5。
 *
 * <p>success 由渠道层根据 contactResult 设置：FAILED 类 → false，其余 → true。
 * 引擎仅读 success 决定是否进入故障降级；AdvancementPolicy 读 contactResult 做业务决策。
 */
@Getter
@Builder
@AllArgsConstructor
public class StepResult {

    private final boolean success;
    private final ContactResult contactResult;
    private final String errorCode;
    /** 网络超时=true；号码无效=false（仅 success=false 时有意义）。 */
    private final boolean retryable;
    /** 供应商消息/通话 ID，回调关联与对账。 */
    private final String providerMsgId;
}
