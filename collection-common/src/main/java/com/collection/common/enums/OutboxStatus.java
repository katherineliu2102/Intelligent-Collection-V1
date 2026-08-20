package com.collection.common.enums;

/** 发件箱记录状态。 */
public enum OutboxStatus {
    /** 已随业务事务落盘，尚未确认投递到总线。 */
    PENDING,
    /**
     * 已被某个发布器短暂认领，防止多实例并发重复投递。
     *
     * <p>认领租约到期仍未销账时回到可扫描状态；该状态不改变事件的投递语义。
     */
    PROCESSING,
    /** 已确认投递到总线。 */
    PUBLISHED,
    /** 兜底重发次数耗尽，需人工介入（告警信号）。 */
    FAILED
}
