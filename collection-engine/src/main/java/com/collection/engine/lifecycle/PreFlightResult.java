package com.collection.engine.lifecycle;

import com.collection.common.enums.CancelReason;
import com.collection.common.model.CaseInfo;
import lombok.Data;

/**
 * 步骤② 系统级守卫的产出（核心引擎规格 §5 ②）。
 *
 * <p>除阻断原因外一并带出本次实时读到的 {@link CaseInfo}，供 Orchestrator 在渲染前刷新快照中的日变字段 （见 {@code
 * StepExecutionOrchestrator#refreshVolatileFields}），避免为同一案件重复读库。
 */
@Data
public class PreFlightResult {

    private boolean gated;
    private CancelReason blockingReason;
    private CaseInfo caseInfo;

    public static PreFlightResult passed(CaseInfo caseInfo) {
        PreFlightResult r = new PreFlightResult();
        r.caseInfo = caseInfo;
        return r;
    }

    public static PreFlightResult gated(CaseInfo caseInfo) {
        PreFlightResult r = new PreFlightResult();
        r.gated = true;
        r.caseInfo = caseInfo;
        return r;
    }

    public static PreFlightResult blocked(CancelReason reason, CaseInfo caseInfo) {
        PreFlightResult r = new PreFlightResult();
        r.blockingReason = reason;
        r.caseInfo = caseInfo;
        return r;
    }

    public boolean isPassed() {
        return blockingReason == null && !gated;
    }

    public boolean isGated() {
        return gated;
    }
}
