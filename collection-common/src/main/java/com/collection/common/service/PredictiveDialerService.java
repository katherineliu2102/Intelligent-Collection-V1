package com.collection.common.service;

/**
 * 外呼排队名单服务。对应核心引擎规格 §2.4。
 *
 * <p>引擎在还款中断时调用 filterRepaidCase 把已结清借据移出排队名单； 调用失败仅告警并继续（核心引擎规格 §5：计划已取消是核心目标）。Phase 1 由渠道编排层 Mock
 * 实现。
 *
 * <p><b>不绑定具体供应商</b>：`AI_CALL` 由独立的 AI Call 合作方承接，该合作方底层复用谁的拨号线路（LTH / SIP / 其他） 对引擎不可见。实现方只需保证「按
 * caseId 移出排队名单」这一语义。
 */
public interface PredictiveDialerService {

    void filterRepaidCase(Long userId, Long caseId);
}
