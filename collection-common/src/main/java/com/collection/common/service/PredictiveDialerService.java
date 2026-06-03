package com.collection.common.service;

/**
 * 预测式外呼服务（人工外呼平台 LTH 交互）。对应核心引擎规格 §2.4。
 *
 * <p>引擎在还款中断时调用 filterRepaidUser 把已还款用户移出排队名单；
 * 调用失败仅告警并继续（§5.1：计划已取消是核心目标）。Phase 1 由渠道编排层 Mock 实现。
 */
public interface PredictiveDialerService {

    void filterRepaidUser(Long userId);
}
