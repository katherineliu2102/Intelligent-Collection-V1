package com.collection.common.enums;

/**
 * 内部领域事件类型。对应领域模型 §2.6、核心引擎规格 §2。
 *
 * <p>Phase 1 引擎消费路由表 11 行（含 {@code CASE_OWNER_RECONCILED}）；{@code PTP_EXPIRED} 为 Phase 2
 * 预留，枚举值保留作前向兼容，Phase 1 不生产/不消费。
 *
 * <p>注：CALLBACK_TIMEOUT 为引擎内部超时哨兵事件（基础设施规范 §4 callbackTimeoutHandler）， 用于异步回调超时兜底（核心引擎规格 §4.3.4）。
 */
public enum EventType {
    CASE_INGESTED,
    STAGE_CHANGED,
    REPAYMENT_RECEIVED,
    /** 部分还款后的余额刷新；不取消计划、不改变步骤或话术。 */
    CASE_BALANCE_UPDATED,
    PLAN_STEP_DUE,
    CHANNEL_CALLBACK,
    /** Phase 2 预留：Phase 1 引擎不生产、不消费、不入 §2.1 路由表。 */
    PTP_EXPIRED,
    STEP_COMPLETED,
    PLAN_EXHAUSTED,
    CALLBACK_TIMEOUT,
    /** D+91 完全停催；ingestion 日切或 mock 发布，引擎 cancel plan 且不再 create。 */
    CASE_CEASED,
    /** 当日 NEW 缺席迁出；ingestion owner 对账发布，引擎 cancel plan 且不再 create。 */
    CASE_OWNER_RECONCILED
}
