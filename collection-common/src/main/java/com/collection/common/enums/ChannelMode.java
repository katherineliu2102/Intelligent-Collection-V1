package com.collection.common.enums;

/**
 * 渠道模式。对应领域模型 §2.8。
 */
public enum ChannelMode {
    /** 自动触达：系统直接调 API，秒级完成。 */
    AUTOMATED,
    /** 人工辅助：需要坐席参与，以批量任务为单位。 */
    AGENT_ASSISTED
}
