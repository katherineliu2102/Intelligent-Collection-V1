package com.collection.common.service;

/**
 * 幂等锁服务。对应核心引擎规格 §5 步骤①、基础设施规范 §3。
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

    /**
     * 释放调用方已成功获取、但尚未产生外部触达的执行锁。
     *
     * <p>仅用于执行前异常的 NACK 重投；渠道受理后不得释放，避免异步等待期的重复触达。
     */
    void release(String idempotencyKey);
}
