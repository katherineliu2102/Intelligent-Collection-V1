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
 * <p>实时查 DB 确认案件存在且未还款。与业务级 ExecutionGuard 的区别：
 * 本类是系统级不变量（案件还活着吗），失败 → 静默退出（不记录、不推进）。
 * <p>同时兼作"事务间隙取消检测"：基础设施读取失败向上抛出，由事件总线 NACK 重投，不能与业务性拦截混为一谈。
 */
@Component
public class PreFlightChecker {

    private static final Logger log = LoggerFactory.getLogger(PreFlightChecker.class);

    @Resource
    private CaseService caseService;

    /**
     * @return true=案件存活，可继续触达；false=案件不存在或已还款，静默退出
     */
    public boolean check(Long caseId) {
        CaseInfo info = caseService.getCaseInfo(caseId);
        if (info == null) {
            log.info("[PreFlight] caseId={} not found, skip", caseId);
            return false;
        }
        if (info.isRepaid()) {
            log.info("[PreFlight] caseId={} already repaid, skip", caseId);
            return false;
        }
        return true;
    }
}
