package com.collection.engine.reaper;

import com.collection.common.repository.ContactPlanRepository;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.metrics.CollectionMetrics;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 停摆计划巡检。对应核心引擎规格 §7.4。
 *
 * <p>发件箱覆盖的是「事件产生了但没发出去」。它盖不住的是另一类：一致性被别的原因破坏后， 计划非终态却已经没有任何步骤能被 due / timeout
 * 扫描拾取——两个扫描是计划推进的唯一驱动源， 都捞不到就意味着这个案件永远不会再被催收，而且没有任何报错。
 *
 * <p><b>只告警，不自动修复。</b>自动重建步骤或重发触达意味着在判断依据不足时替用户做出不可回滚的外部动作； 误判的代价（重复外呼、监管投诉）远高于人工介入的延迟。
 */
@Component
public class StuckPlanReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckPlanReaper.class);

    @Autowired(required = false)
    private ContactPlanRepository planRepository;

    @Resource private EngineProperties props;
    @Resource private CollectionMetrics metrics;

    /** 时钟源。生产默认系统时钟；测试可注入固定时钟以消除时基竞态。 */
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${engine.reaper.interval-ms:300000}")
    public void detectStuckPlans() {
        EngineProperties.Reaper cfg = props.getReaper();
        if (planRepository == null || !cfg.isEnabled()) {
            return;
        }
        LocalDateTime idleBefore = LocalDateTime.now(clock).minusMinutes(cfg.getIdleMinutes());
        List<Long> stuck;
        try {
            stuck = planRepository.findStuckPlanIds(idleBefore, cfg.getBatchSize());
        } catch (Exception e) {
            log.error("[reaper] stuck plan scan failed", e);
            return;
        }
        if (stuck.isEmpty()) {
            return;
        }
        metrics.planStuck(stuck.size());
        log.error(
                "[reaper] {} plan(s) non-terminal but unreachable by due/timeout scan, idle >{}min,"
                        + " manual intervention required: {}",
                stuck.size(),
                cfg.getIdleMinutes(),
                stuck);
    }
}
