package com.collection.admin.job;

import com.collection.ingestion.job.DpdStageRollHandler;
import com.xxl.job.core.handler.annotation.XxlJob;
import javax.annotation.Resource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Pilot 唯一调度入口；三个方法名与 XXL 控制台任务名一一对应。 */
@Component
@Profile("pilot")
public class PilotXxlJobHandlers {

    @Resource private PlanStepTriggerPublisher triggerPublisher;
    @Resource private DpdStageRollHandler dailyRollHandler;

    @XxlJob("planStepDueHandler")
    public void planStepDueHandler() {
        triggerPublisher.publishDueSteps();
    }

    @XxlJob("callbackTimeoutHandler")
    public void callbackTimeoutHandler() {
        triggerPublisher.publishTimeoutSteps();
    }

    @XxlJob("dailyRoll")
    public void dailyRoll() {
        dailyRollHandler.dailyRoll();
    }
}
