package com.collection.channel.gateway;

import com.collection.common.service.PredictiveDialerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 1 Mock 实现 —— 预测式外呼名单过滤占位。对应 {@link PredictiveDialerService}。
 *
 * <p>真实实现：渠道编排负责人对接 LTH，把已还款用户移出排队名单。
 */
@Component
public class MockPredictiveDialerService implements PredictiveDialerService {

    private static final Logger log = LoggerFactory.getLogger(MockPredictiveDialerService.class);

    @Override
    public void filterRepaidUser(Long userId) {
        log.info("[MockPredictiveDialer] filter repaid user {} from dial queue (noop)", userId);
    }
}
