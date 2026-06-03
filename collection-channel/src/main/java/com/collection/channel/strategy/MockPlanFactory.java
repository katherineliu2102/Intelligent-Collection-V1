package com.collection.channel.strategy;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.spi.PlanFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 Mock 实现 —— DefaultPlanFactory 的占位。对应 SPI {@link PlanFactory}。
 *
 * <p>真实实现：渠道编排负责人按 t_contact_plan_template（stage × product）匹配模板并实例化。
 * 本 Mock 固定生成 3 步消息类计划（SMS → PUSH → SMS），使全链路无需外部回调即可跑通完成。
 *
 * <p>约束遵循：禁止副作用（只构造对象）；Phase 1 不输出 HUMAN_CALL（对齐待办 E4）。
 */
@Component
public class MockPlanFactory implements PlanFactory {

    private static final Logger log = LoggerFactory.getLogger(MockPlanFactory.class);

    @Override
    public ContactPlan create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot) {
        if (caseInfo == null) {
            return null;
        }
        log.info("[MockPlanFactory] build plan for case {} stage {}", caseInfo.getCaseId(), stage);

        ContactPlan plan = new ContactPlan();
        plan.setCaseId(caseInfo.getCaseId());
        plan.setUserId(caseInfo.getUserId());
        plan.setStage(stage);

        List<ContactPlanStep> steps = new ArrayList<>();
        steps.add(buildStep(1, ChannelType.SMS, 0, 0, 101L));
        steps.add(buildStep(2, ChannelType.PUSH, 1, 0, 102L));
        steps.add(buildStep(3, ChannelType.SMS, 1, 0, 103L));
        plan.setSteps(steps);
        return plan;
    }

    private ContactPlanStep buildStep(int order, ChannelType channel, int delayMin, int obsMin, Long templateId) {
        ContactPlanStep step = new ContactPlanStep();
        step.setStepOrder(order);
        step.setChannelType(channel);
        step.setDelayMinutes(delayMin);
        step.setObservationMinutes(obsMin);
        step.setTemplateId(templateId);
        step.setStatus(StepStatus.PENDING);
        step.setRetryCount(0);
        return step;
    }
}
