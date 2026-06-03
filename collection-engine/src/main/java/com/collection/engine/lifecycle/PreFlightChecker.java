package com.collection.engine.lifecycle;

import com.collection.common.model.CaseInfo;
import com.collection.common.service.CaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 系统级实时守卫。对应核心引擎规格 §1.1、§3.1 步骤②。
 *
 * <p>实时查 DB 确认案件未还款 / 未冻结 / 未关闭。与业务级 ExecutionGuard 的区别：
 * 本类是系统级不变量（案件还活着吗），失败 → 静默退出（不记录、不推进）。
 * <p>同时兼作"事务间隙取消检测"：基础设施读取失败按 fail-close 处理（核心引擎规格 §5.1）。
 */
@Component
public class PreFlightChecker {

    private static final Logger log = LoggerFactory.getLogger(PreFlightChecker.class);

    @Resource
    private CaseService caseService;

    /**
     * @return true=案件存活，可继续触达；false=已还款/冻结/关闭或读取失败，静默退出
     */
    public boolean check(Long caseId) {
        try {
            CaseInfo info = caseService.getCaseInfo(caseId);
            if (info == null) {
                log.info("[PreFlight] caseId={} not found, skip", caseId);
                return false;
            }
            if (info.isRepaid()) {
                log.info("[PreFlight] caseId={} already repaid, skip", caseId);
                return false;
            }
            if (info.isFrozen()) {
                log.info("[PreFlight] caseId={} frozen, skip", caseId);
                return false;
            }
            return true;
        } catch (Exception e) {
            // fail-close：MySQL 不可达 → 静默退出，不执行触达（核心引擎规格 §5.1 Orchestrator②）
            log.warn("[PreFlight] caseId={} check failed (fail-close, skip): {}", caseId, e.getMessage());
            return false;
        }
    }
}
