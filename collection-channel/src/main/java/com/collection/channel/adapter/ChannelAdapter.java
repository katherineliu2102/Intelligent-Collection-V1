package com.collection.channel.adapter;

import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;

/**
 * 单渠道供应商适配器。Gateway 仅做路由与幂等；业务结果映射留在各 Adapter（见开发执行指南 §3.1）。
 */
public interface ChannelAdapter {

    ChannelType channelType();

    StepResult send(StepCommand command);
}
