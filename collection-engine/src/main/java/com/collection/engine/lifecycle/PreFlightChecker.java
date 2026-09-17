package com.collection.engine.lifecycle;

import com.collection.common.enums.CancelReason;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.CollectableAmounts;
import com.collection.common.service.CaseService;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 系统级实时守卫。对应核心引擎规格 §1.1、§3.1 步骤②。
 *
 * <p>实时查 DB 确认案件存在且未还款。与业务级 ExecutionGuard 的区别： 本类是系统级不变量（案件还活着吗），阻断 → 计划置 PLAN_CANCELLED 后退出（不写
 * timeline、不推进状态机）。
 *
 * <p>同时兼作"事务间隙取消检测"：基础设施读取失败向上抛出，由事件总线 NACK 重投，不能与业务性拦截混为一谈。
 */
@Component
public class PreFlightChecker {

    private static final Logger log = LoggerFactory.getLogger(PreFlightChecker.class);

    @Resource private CaseService caseService;

    /**
     * 守卫 + 带出本次实时读到的案件数据。放行时 {@code caseInfo} 必非空，Orchestrator 直接复用它刷新快照日变字段， 不再为同一案件二次读库。
     *
     * <p>业务性阻断必须有可持久化的终态原因（调用方据此置 PLAN_CANCELLED）； 基础设施读取异常仍向上抛以触发 NACK，绝不能降级为"用陈旧值继续发送"。
     */
    public PreFlightResult inspect(Long caseId) {
        CaseInfo info = caseService.getCaseInfo(caseId);
        if (info == null) {
            log.info("[PreFlight] caseId={} not found, skip", caseId);
            return PreFlightResult.blocked(CancelReason.CASE_NOT_FOUND, null);
        }
        if (caseService.requiresOwnerDate()) {
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Manila"));
            if (!caseService.isOwnerReconciledToday()
                    || info.getOwnerDate() == null
                    || !today.equals(info.getOwnerDate())) {
                log.info(
                        "[PreFlight] caseId={} owner gated reconciled={} ownerDate={}",
                        caseId,
                        caseService.isOwnerReconciledToday(),
                        info.getOwnerDate());
                return PreFlightResult.gated(info);
            }
        }
        if (info.isRepaid()) {
            log.info("[PreFlight] caseId={} already repaid, skip", caseId);
            return PreFlightResult.blocked(CancelReason.REPAID, info);
        }
        if (CollectableAmounts.shouldBlockNoDueBalance(info)) {
            log.info("[PreFlight] caseId={} has no collectable balance, skip", caseId);
            return PreFlightResult.blocked(CancelReason.NO_DUE_BALANCE, info);
        }
        return PreFlightResult.passed(info);
    }
}
