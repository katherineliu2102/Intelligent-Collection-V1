package com.collection.ingestion.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DPD 日切处理器（对齐待办 E2 / 基础设施规范 §4）。
 *
 * <p>目标态（每日 0:05 PHT）：重算 Max DPD →
 * <ul>
 *   <li>1~90 且 Stage 变 → 发 STAGE_CHANGED</li>
 *   <li>≥91 且未 CEASED → 写 collection_status=CEASED + 发 CASE_CEASED（停催，对齐待办 E1）</li>
 * </ul>
 *
 * <p>Phase 1 骨架：占位。由数据接入负责人接 XXL-Job 调度并实现重算逻辑（引擎 Consumer 不改 Cron，只消费事件）。
 */
@Component
public class DpdStageRollHandler {

    private static final Logger log = LoggerFactory.getLogger(DpdStageRollHandler.class);

    /** 供 XXL-Job / 调度器调用。Phase 1 仅占位。 */
    public void dailyRoll() {
        log.info("[DpdStageRollHandler] daily DPD roll — TODO: 重算 Max DPD、发 STAGE_CHANGED / CASE_CEASED");
    }
}
