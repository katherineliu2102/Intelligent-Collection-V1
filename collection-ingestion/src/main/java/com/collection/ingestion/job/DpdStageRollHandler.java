package com.collection.ingestion.job;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.service.CaseService;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import java.util.List;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * DPD 日切处理器（对齐待办 E2 / 基础设施规范 §4 / 数据接入规格 C-D）。
 *
 * <p><b>并行期口径（2026-07-06 主架构拍板，C-D 联调确认）</b>：旧系统每日已重算并写 {@code t_collection.overdue_days}，本 Job
 * <b>只读不重算</b>——直接取 {@code overdue_days} 作 Max DPD （经 {@link CaseService#getCaseInfo}，其内部 {@code
 * selectByLoanId} 已按 {@code create_time DESC} 取最新行、 {@code full_repay_time}/{@code total_not_paid}
 * 判在催）：
 *
 * <ul>
 *   <li>dpd 1~90 且新阶段 ≠ 计划当前阶段 → 发 {@code STAGE_CHANGED}（引擎升/降档，carry-forward 快照）
 *   <li>dpd ≥ 91 且仍有活跃计划 → 发 {@code CASE_CEASED}（引擎 cancel plan，对齐 seed 99000005）
 *   <li>已结清（{@code repaid}）→ 跳过（还款事件另行取消计划）
 * </ul>
 *
 * <p><b>范围</b>：仅扫 {@code collection.ingestion.loan-id-whitelist} 名单（Phase 1 / L4b 隔离，避免对全量
 * 真实在催案件发事件）。名单为空时跳过全量扫描（生产全量扫 {@code t_collection} 属切量后，见 C-X-02）。
 *
 * <p>生产由 Cloud Scheduler 在 00:35–02:55 PHT 每 5 分钟发一条调度消息到调度专用 Pub/Sub 主题（账务数据落库至少 30 分钟后），
 * 应用侧调度订阅消费后调 {@link #dailyRoll()}；每次触发只推进一页 keyset，续跑依赖 Redis 游标与当日完成标记，不依赖消息重投。
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

        Stage current = active.isEmpty() ? null : active.get(0).getStage();
        if (current != null && current != newStage && acquireDailyRollEvent("stage", loanId, dpd)) {
            ingestionService.changeStage(loanId, newStage);
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
