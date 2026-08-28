package com.collection.channel.adapter;

import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 波次起批的节拍来源。
 *
 * <p>用应用内定时器而不是新增 Cloud Scheduler job：起批只关心「攒够了没」，不需要跨实例的调度契约，多实例单飞由 {@link FacadeBatchCoordinator}
 * 内的 Redis 锁保证。聚合开关关闭时每轮直接返回。
 */
@Component
public class FacadeBatchFlusher {

    private static final Logger log = LoggerFactory.getLogger(FacadeBatchFlusher.class);

    @Resource private FacadeBatchCoordinator coordinator;

    @Scheduled(
            fixedDelayString = "${channel.facade.batch-aggregation.poll-interval-ms:5000}",
            initialDelayString = "${channel.facade.batch-aggregation.poll-interval-ms:5000}")
    public void flush() {
        try {
            coordinator.flushDueWaves();
        } catch (RuntimeException e) {
            log.error("[FacadeBatch] 起批轮询异常", e);
        }
    }
}
