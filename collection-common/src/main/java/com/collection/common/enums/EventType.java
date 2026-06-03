package com.collection.common.enums;

/**
 * 内部领域事件类型。对应领域模型 §6.6、核心引擎规格 §1.1。
 *
 * <p>注：CALLBACK_TIMEOUT 为引擎内部超时哨兵事件（基础设施规范 §4 callbackTimeoutHandler），
 * 与文档 8 类业务事件并列，用于异步回调超时兜底（核心引擎规格 §2.3.4）。
 */
public enum EventType {

    CASE_INGESTED,
    STAGE_CHANGED,
    REPAYMENT_RECEIVED,
    PLAN_STEP_DUE,
    CHANNEL_CALLBACK,
    PTP_EXPIRED,
    STEP_COMPLETED,
    PLAN_EXHAUSTED,
    CALLBACK_TIMEOUT
}
