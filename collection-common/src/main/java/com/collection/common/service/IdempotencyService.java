package com.collection.common.service;

/**
 * 幂等锁服务。对应核心引擎规格 §3.1 步骤①、基础设施规范 §3。
 *
 * <p>Phase 1 提供内存版（链路验证）与 Redis SETNX 版（生产）两套实现。
 */
public interface IdempotencyService {

    /**
     * 基于 idempotencyKey 获取分布式锁（SETNX + TTL）。
     *
     * @return true=获取成功（首次）；false=已存在（重复事件，应静默退出）
     */
    boolean acquire(String idempotencyKey, int ttlMinutes);
}
