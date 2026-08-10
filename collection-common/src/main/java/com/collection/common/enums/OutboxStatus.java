package com.collection.common.enums;

/** 发件箱记录状态。 */
public enum OutboxStatus {
    /** 已随业务事务落盘，尚未确认投递到总线。 */
    PENDING,
    /** 已确认投递到总线。 */
    PUBLISHED,
    /** 兜底重发次数耗尽，需人工介入（告警信号）。 */
    FAILED
}
