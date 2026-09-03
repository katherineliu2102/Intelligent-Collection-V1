package com.collection.ingestion.job;

import com.collection.common.enums.CancelReason;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.CollectableAmounts;
import com.collection.common.model.ContactPlan;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.service.CaseService;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import java.time.LocalDate;
import java.util.Collections;
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
 *   <li>有活跃计划且投影 stage 严重度更高 → 发 {@code STAGE_CHANGED}
 *   <li>有活跃计划且投影 stage 更低 → 不发（单调前进，避免与引擎 ESCALATE 降档 ping-pong）
 *   <li>无活跃计划，最近一份 {@code PLAN_COMPLETED} 且投影档更高 → 发 {@code STAGE_CHANGED}（档末日走完后次日建 S0→S1 … S3→S4）
 *   <li>无活跃计划，最近一份 {@code PLAN_CANCELLED}+{@code NO_DUE_BALANCE} 且投影已有应还余额 → 按当天档发 {@code
 *       STAGE_CHANGED}
 *   <li>其余 {@code PLAN_CANCELLED}（还款 / 停催 / {@code MANUAL_CLEANUP}）或同档 {@code PLAN_COMPLETED} → 不建档
 *   <li>dpd ≥ 91 且仍有活跃计划 → 发 {@code CASE_CEASED}
 *   <li>已结清（{@code SETTLED}）→ 跳过
 * </ul>
 *
 * <p><b>唯一来源</b>：上述两类事件只由本 Job 产出。外部 Pub/Sub 只投递 {@code CASE_INGESTED} / {@code REPAYMENT}
 * 与每日全量校准（见 {@link com.collection.ingestion.pubsub.AiCaseIngestionProcessor}）；若数仓也发阶段/停催事件，
 * 同一状态会被两个来源重复触发，导致计划被反复取消重建。
 *
 * <p><b>范围</b>：联调扫 {@code collection.ingestion.loan-id-whitelist}；生产全量 keyset 扫描须 {@code
 * daily-roll-full-scan-enabled=true}（见 C-X-02）。
 *
 * <p>生产由 Cloud Scheduler 在 03:35–05:55 PHT 每 5 分钟发调度消息，应用侧调度订阅消费后调 {@link #dailyRoll()}； 每次触发只推进一页
 * keyset，续跑依赖 Redis 游标与当日完成标记，不依赖消息重投。投影由接入层实时维护， 日切前须确认当日全量校准已消费完毕，否则扫描基线不完整。
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

    @Autowired(required = false)
    private OwnerReconcileHandler ownerReconcileHandler;

    /**
     * 供调度入口（生产：Cloud Scheduler → Pub/Sub 调度订阅；本地：{@code POST /mock/daily-roll}）调用。
     *
     * @return 本次处理的 loan_id 条数（供调度指标记录）；跳过时为 0
     */
    public int dailyRoll() {
        if (ownerReconcileHandler != null && !ownerReconcileHandler.completedToday()) {
            return ownerReconcileHandler.advance();
        }
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
        int[] counters = new int[3]; // [0]=stageChanged, [1]=ceased, [2]=rollbackSkipped
        for (Long loanId : loanIds) {
            try {
                rollOne(loanId, counters);
            } catch (Exception e) {
                log.warn("[DpdStageRollHandler] loanId={} 日切失败，跳过: {}", loanId, e.getMessage());
            }
        }
        log.info(
                "[DpdStageRollHandler] daily roll completed fullScan={} scanned={} stageChanged={} ceased={} rollbackSkipped={}",
                fullScan,
                loanIds.size(),
                counters[0],
                counters[1],
                counters[2]);
    }

    private void rollOne(Long loanId, int[] counters) {
        CaseInfo info = caseService.getCaseInfo(loanId);
        if (info == null || info.isRepaid()) {
            return; // 无案 / 已结清：不在催，跳过（结清由还款事件取消计划）
        }
        if (caseService.requiresOwnerDate()) {
            LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Manila"));
            if (info.getOwnerDate() == null || !today.equals(info.getOwnerDate())) {
                return;
            }
        }
        int dpd = info.getDpd();
        Stage newStage = info.getStage(); // 投影 stage 列（数仓口径），仅在该列为空时才退回 Stage.fromDpd
        List<ContactPlan> active = planRepository.findActivePlansByCase(loanId);
        if (active == null) {
            active = Collections.emptyList();
        }

        if (dpd >= 91) {
            if (!active.isEmpty() && acquireDailyRollEvent("ceased", loanId, dpd)) {
                ingestionService.caseCeased(loanId, dpd);
                counters[1]++;
                log.info("[DpdStageRollHandler] loanId={} dpd={} ≥91 → CASE_CEASED", loanId, dpd);
            }
            return;
        }

        if (!active.isEmpty()) {
            publishIfUpgrade(loanId, info, active.get(0).getStage(), newStage, dpd, counters, "");
            return;
        }

        ContactPlan last = latestPlan(loanId);
        if (last == null) {
            return;
        }
        if (last.getStatus() == PlanStatus.PLAN_COMPLETED) {
            publishIfUpgrade(
                    loanId,
                    info,
                    last.getStage(),
                    newStage,
                    dpd,
                    counters,
                    " (resume after PLAN_COMPLETED)");
            return;
        }
        if (last.getStatus() == PlanStatus.PLAN_CANCELLED
                && last.getCancelReason() == CancelReason.NO_DUE_BALANCE
                && hasPositiveOutstanding(info)
                && newStage != null
                && acquireDailyRollEvent("stage", loanId, dpd)) {
            publishStageChanged(
                    loanId,
                    info,
                    last.getStage(),
                    newStage,
                    dpd,
                    counters,
                    " (resume after NO_DUE_BALANCE)");
        }
    }

    private ContactPlan latestPlan(Long loanId) {
        List<ContactPlan> recent = planRepository.findRecentPlansByCase(loanId, 1);
        if (recent == null || recent.isEmpty()) {
            return null;
        }
        return recent.get(0);
    }

    private static boolean hasPositiveOutstanding(CaseInfo info) {
        return CollectableAmounts.hasCollectableBalance(info);
    }

    /** 仅当目标档严格高于比对档时发 {@code STAGE_CHANGED}。回退只计数，避免 ESCALATE 后被日切打回低档。 */
    private void publishIfUpgrade(
            Long loanId,
            CaseInfo info,
            Stage current,
            Stage newStage,
            int dpd,
            int[] counters,
            String resumeNote) {
        if (current == null || current == newStage) {
            return;
        }
        if (newStage == null) {
            log.info("[DpdStageRollHandler] loanId={} dpd={} 投影无 stage（未进入催收窗口），跳过升档", loanId, dpd);
            return;
        }
        if (newStage.compareTo(current) < 0) {
            counters[2]++;
            log.info(
                    "[DpdStageRollHandler] loanId={} dpd={} 投影 stage {} 低于计划 stage {}，按单调前进跳过回退",
                    loanId,
                    dpd,
                    newStage,
                    current);
            return;
        }
        if (!acquireDailyRollEvent("stage", loanId, dpd)) {
            return;
        }
        publishStageChanged(loanId, info, current, newStage, dpd, counters, resumeNote);
    }

    private void publishStageChanged(
            Long loanId,
            CaseInfo info,
            Stage current,
            Stage newStage,
            int dpd,
            int[] counters,
            String resumeNote) {
        ingestionService.changeStage(
                loanId,
                info.getUserId(),
                newStage,
                ingestionService.currentSnapshotFields(caseService.getContextSnapshot(loanId)));
        counters[0]++;
        log.info(
                "[DpdStageRollHandler] loanId={} dpd={} stage {}→{} → STAGE_CHANGED{}",
                loanId,
                dpd,
                current == null ? "-" : current,
                newStage,
                resumeNote == null ? "" : resumeNote);
    }

    private boolean acquireDailyRollEvent(String type, Long loanId, int dpd) {
        return dailyRollDeduplicator == null || dailyRollDeduplicator.acquire(type, loanId, dpd);
    }
}
