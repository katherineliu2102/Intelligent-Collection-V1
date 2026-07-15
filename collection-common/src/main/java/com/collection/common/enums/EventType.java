package com.collection.common.enums;

/**
 * 内部领域事件类型。对应领域模型 §2.6、核心引擎规格 §1.1。
 *
 * <p>Phase 1 引擎实际消费的事件为 §1.1 路由表 8 行 + 内部哨兵 CALLBACK_TIMEOUT = 9 种；
 * PTP_EXPIRED 为 Phase 2 预留，枚举值保留作前向兼容，Phase 1 不生产/不消费。
 *
 * <p>注：CALLBACK_TIMEOUT 为引擎内部超时哨兵事件（基础设施规范 §4 callbackTimeoutHandler），
 * 用于异步回调超时兜底（核心引擎规格 §2.3.4）。
 */
public enum EventType {

    CASE_INGESTED,
    STAGE_CHANGED,
    REPAYMENT_RECEIVED,
    PLAN_STEP_DUE,
    CHANNEL_CALLBACK,
    /** Phase 2 预留：Phase 1 引擎不生产、不消费、不入 §1.1 路由表（核心引擎规格 §2.6）。 */
    PTP_EXPIRED,
    STEP_COMPLETED,
    PLAN_EXHAUSTED,
    CALLBACK_TIMEOUT,
    /** D+91 完全停催；ingestion 日切或 mock 发布，引擎 cancel plan 且不再 create（对齐待办 E1）。 */
    CASE_CEASED
}
