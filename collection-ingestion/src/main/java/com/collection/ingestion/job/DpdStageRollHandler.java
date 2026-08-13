package com.collection.ingestion.job;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.service.CaseService;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import java.math.BigDecimal;
import java.util.List;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * DPD 日切处理器（对齐数据接入规格 §4 / C-D）。
 *
 * <p>只读 {@code t_ai_collection}（经 {@link CaseService}）：数仓预计算 {@code dpd} / {@code stage} / {@code
 * collection_status}，本 Job <b>不重算</b> DPD：
 *
 * <ul>
 *   <li>dpd 1~90 且新阶段 ≠ 计划当前阶段 → 发 {@code STAGE_CHANGED}
 *   <li>dpd ≥ 91 且仍有活跃计划 → 发 {@code CASE_CEASED}
 *   <li>无活跃 plan 且 {@code total_outstanding > 0} → 发 {@code CASE_INGESTED}（复活）
 *   <li>已结清（{@code SETTLED}）→ 跳过
 * </ul>
 *
 * <p><b>唯一来源</b>：上述三类事件只由本 Job 产出。外部 Pub/Sub 只投递 {@code CASE_INGESTED} / {@code REPAYMENT}
 * 与每日全量校准（见 {@link com.collection.ingestion.pubsub.AiCaseIngestionProcessor}）；若数仓也发阶段/停催事件，
 * 同一状态会被两个来源重复触发，导致计划被反复取消重建。
 *
 * <p><b>范围</b>：联调扫 {@code collection.ingestion.loan-id-whitelist}；生产全量 keyset 扫描须 {@code
 * daily-roll-full-scan-enabled=true}（见 C-X-02）。
 *
 * <p>生产由 Cloud Scheduler 在 03:35–05:55 PHT 每 5 分钟发调度消息，应用侧调度订阅消费后调 {@link #dailyRoll()}；
 * 每次触发只推进一页 keyset，续跑依赖 Redis 游标与当日完成标记，不依赖消息重投。投影由接入层实时维护，
 * 日切前须确认当日全量校准已消费完毕，否则扫描基线不完整。
 */
@Component
public class DpdStageRollHandler {

    private static final Logger log = LoggerFactory.getLogger(DpdStageRollHandler.class);

    @Resource private IngestionProperties props;
    @Resource private CaseService caseService;
    @Resource private ContactPlanRepository planRepository;
    @Resource private IngestionService ingestionService;

    @Autowired(required = false)
    private RedisDailyRollDeduplicator dailyRollDeduplicator;

    /**
     * 供调度入口（生产：Cloud Scheduler → Pub/Sub 调度订阅；本地：{@code POST /mock/daily-roll}）调用。
     *
     * @return 本次处理的 loan_id 条数（供调度指标记录）；跳过时为 0
     */
    public int dailyRoll() {
        List<Long> whitelist = props.getLoanIdWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            return dailyRollFullScan();
        }
        rollBatch(whitelist, false);
        return whitelist.size();
    }

    private int dailyRollFullScan() {
        if (!props.isDailyRollFullScanEnabled()) {
            log.warn(
                    "[DpdStageRollHandler] loan-id-whitelist 为空且 daily-roll-full-scan-enabled=false，跳过全量扫描");
            return 0;
        }
        if (dailyRollDeduplicator == null) {
            throw new IllegalStateException("全量日切需要 Redis 去重与游标存储");
        }
        if (dailyRollDeduplicator.completedToday()) {
            log.info("[DpdStageRollHandler] full scan already completed today; skip");
            return 0;
        }
        int limit = Math.max(1, props.getDailyRollBatchSize());
        List<Long> loanIds =
                caseService.findActiveCaseIdsAfter(dailyRollDeduplicator.currentCursor(), limit);
        if (loanIds.isEmpty()) {
            dailyRollDeduplicator.markCompletedToday();
            log.info("[DpdStageRollHandler] full scan reached end; marked completed");
            return 0;
        }
        rollBatch(loanIds, true);
        dailyRollDeduplicator.advanceCursor(loanIds.get(loanIds.size() - 1));
        if (loanIds.size() < limit) {
            dailyRollDeduplicator.markCompletedToday();
        }
        return loanIds.size();
    }

    private void rollBatch(List<Long> loanIds, boolean fullScan) {
        int[] counters = new int[2]; // [0]=stageChanged, [1]=ceased
        for (Long loanId : loanIds) {
            try {
                rollOne(loanId, counters);
            } catch (Exception e) {
                log.warn("[DpdStageRollHandler] loanId={} 日切失败，跳过: {}", loanId, e.getMessage());
            }
        }
        log.info(
                "[DpdStageRollHandler] daily roll completed fullScan={} scanned={} stageChanged={} ceased={}",
                fullScan,
                loanIds.size(),
                counters[0],
                counters[1]);
    }

    private void rollOne(Long loanId, int[] counters) {
        CaseInfo info = caseService.getCaseInfo(loanId);
        if (info == null || info.isRepaid()) {
            return; // 无案 / 已结清：不在催，跳过（结清由还款事件取消计划）
        }
        int dpd = info.getDpd();
        Stage newStage = info.getStage(); // = Stage.fromDpd(dpd)
        List<ContactPlan> active = planRepository.findActivePlansByCase(loanId);

        if (dpd >= 91) {
            if (!active.isEmpty() && acquireDailyRollEvent("ceased", loanId, dpd)) {
                ingestionService.caseCeased(loanId, dpd);
                counters[1]++;
                log.info("[DpdStageRollHandler] loanId={} dpd={} ≥91 → CASE_CEASED", loanId, dpd);
            }
            return;
        }

        // t_ai_collection 结清反转（或日切恢复后无活跃计划）：重新以完整快照入案。
        // 只对有已到期余额的在催案件执行，避免把正常完成或未到期零余额案件每日重复建计划。
        if (active.isEmpty()
                && newStage != null
                && info.getTotalOutstanding() != null
                && info.getTotalOutstanding().compareTo(BigDecimal.ZERO) > 0
                && acquireDailyRollEvent("reactivate", loanId, dpd)) {
            ingestionService.ingestCase(
                    loanId,
                    info.getUserId(),
                    newStage,
                    ingestionService.currentSnapshotFields(caseService.getContextSnapshot(loanId)));
            counters[0]++;
            log.info("[DpdStageRollHandler] loanId={} reactivated from t_ai_collection", loanId);
            return;
        }

        Stage current = active.isEmpty() ? null : active.get(0).getStage();
        if (current != null && current != newStage && acquireDailyRollEvent("stage", loanId, dpd)) {
            ingestionService.changeStage(
                    loanId,
                    info.getUserId(),
                    newStage,
                    ingestionService.currentSnapshotFields(caseService.getContextSnapshot(loanId)));
            counters[0]++;
            log.info(
                    "[DpdStageRollHandler] loanId={} dpd={} stage {}→{} → STAGE_CHANGED",
                    loanId,
                    dpd,
                    current,
                    newStage);
        }
    }

    private boolean acquireDailyRollEvent(String type, Long loanId, int dpd) {
        return dailyRollDeduplicator == null || dailyRollDeduplicator.acquire(type, loanId, dpd);
    }
}
