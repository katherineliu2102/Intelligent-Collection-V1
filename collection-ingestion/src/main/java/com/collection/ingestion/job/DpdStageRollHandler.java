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
import org.springframework.stereotype.Component;

/**
 * DPD 日切处理器（对齐待办 E2 / 基础设施规范 §4 / 数据接入规格 C-D）。
 *
 * <p><b>并行期口径（2026-07-06 主架构拍板，C-D 联调确认）</b>：旧系统每日已重算并写
 * {@code t_collection.overdue_days}，本 Job <b>只读不重算</b>——直接取 {@code overdue_days} 作 Max DPD
 * （经 {@link CaseService#getCaseInfo}，其内部 {@code selectByLoanId} 已按 {@code create_time DESC} 取最新行、
 * {@code full_repay_time}/{@code total_not_paid} 判在催）：
 *
 * <ul>
 *   <li>dpd 1~90 且新阶段 ≠ 计划当前阶段 → 发 {@code STAGE_CHANGED}（引擎升/降档，carry-forward 快照）</li>
 *   <li>dpd ≥ 91 且仍有活跃计划 → 发 {@code CASE_CEASED}（引擎 cancel plan，对齐 seed 99000005）</li>
 *   <li>已结清（{@code repaid}）→ 跳过（还款事件另行取消计划）</li>
 * </ul>
 *
 * <p><b>范围</b>：仅扫 {@code collection.ingestion.loan-id-whitelist} 名单（Phase 1 / L4b 隔离，避免对全量
 * 真实在催案件发事件）。名单为空时跳过全量扫描（生产全量扫 {@code t_collection} 属切量后，见 C-X-02）。
 *
 * <p>由 XXL-Job 每日 0:35 PHT（账务数据落库至少 30 分钟后）调 {@link #dailyRoll()}（注册见 L4b 交接清单 O3）。
 */
@Component
public class DpdStageRollHandler {

    private static final Logger log = LoggerFactory.getLogger(DpdStageRollHandler.class);

    @Resource private IngestionProperties props;
    @Resource private CaseService caseService;
    @Resource private ContactPlanRepository planRepository;
    @Resource private IngestionService ingestionService;

    /** 供 XXL-Job / 调度器调用。 */
    public void dailyRoll() {
        List<Long> whitelist = props.getLoanIdWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            log.warn(
                    "[DpdStageRollHandler] loan-id-whitelist 为空，Phase 1 / L4b 跳过全量扫描"
                            + "（生产全量扫 t_collection 见 C-X-02）");
            return;
        }
        int[] counters = new int[2]; // [0]=stageChanged, [1]=ceased
        for (Long loanId : whitelist) {
            try {
                rollOne(loanId, counters);
            } catch (Exception e) {
                log.warn("[DpdStageRollHandler] loanId={} 日切失败，跳过: {}", loanId, e.getMessage());
            }
        }
        log.info(
                "[DpdStageRollHandler] daily roll 完成 scanned={} stageChanged={} ceased={}",
                whitelist.size(),
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
            if (!active.isEmpty()) {
                ingestionService.caseCeased(loanId, dpd);
                counters[1]++;
                log.info("[DpdStageRollHandler] loanId={} dpd={} ≥91 → CASE_CEASED", loanId, dpd);
            }
            return;
        }

        Stage current = active.isEmpty() ? null : active.get(0).getStage();
        if (current != null && current != newStage) {
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
}
