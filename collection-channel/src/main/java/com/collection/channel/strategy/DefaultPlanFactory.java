package com.collection.channel.strategy;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.spi.PlanFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Phase 1 简化版 PlanFactory —— 配置驱动（YAML plan-templates），不读 DB 模板表。
 *
 * <p>主架构临时代写，推进 L4a-全测试。编排同事回来后替换为读 t_contact_plan_template 的生产实现。
 *
 * <p>优先级：{@code channel.debug.single-step} > {@code legacy-three-step} > {@code plan-templates} 配置。
 */
@Primary
@Component
public class DefaultPlanFactory implements PlanFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlanFactory.class);

    @Resource private ChannelProperties channelProperties;

    @Resource private ConfigTemplateProvider templateProvider;

    @Override
    public ContactPlan create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot) {
        if (MockPlanFactory.shouldRejectPlan(caseInfo, snapshot)) {
            return null;
        }

        log.info(
                "[DefaultPlanFactory] build plan for case {} stage {}",
                caseInfo.getCaseId(),
                stage);

        List<ContactPlanStep> steps = buildSteps(stage, caseInfo.getCaseId());
        if (steps.isEmpty()) {
            log.warn("[DefaultPlanFactory] no steps resolved for stage {}, skip", stage);
            return null;
        }

        ContactPlan plan = new ContactPlan();
        plan.setCaseId(caseInfo.getCaseId());
        plan.setUserId(caseInfo.getUserId());
        plan.setStage(stage);
        plan.setSteps(steps);
        return plan;
    }

    private List<ContactPlanStep> buildSteps(Stage stage, Long caseId) {
        String singleStep = channelProperties.getDebug().getSingleStep();
        if (StringUtils.isNotBlank(singleStep)) {
            return buildSingleStep(singleStep.trim().toUpperCase(Locale.ROOT));
        }
        if (isGuardNoPhoneCase(caseId)) {
            return buildSingleStep("SMS");
        }
        if (isGuardFrequencyCase(caseId)) {
            List<ContactPlanStep> steps = new ArrayList<>();
            steps.add(buildStep(1, ChannelType.SMS, 0, 0, 101L));
            steps.add(buildStep(2, ChannelType.SMS, 0, 0, 101L));
            return steps;
        }
        if (isRebuildFailCase(caseId)) {
            List<ContactPlanStep> steps = new ArrayList<>();
            steps.add(buildStep(1, ChannelType.EMAIL, 0, 0, 201L));
            return steps;
        }
        if (isObservationCase(caseId, stage)) {
            List<ContactPlanStep> steps = new ArrayList<>();
            steps.add(buildStep(1, ChannelType.SMS, 0, observationMinutes(), 101L));
            return steps;
        }
        if (channelProperties.getDebug().isLegacyThreeStep()) {
            return buildLegacyThreeStep();
        }
        return buildFromTemplate(stage);
    }

    private boolean isGuardFrequencyCase(Long caseId) {
        return caseId != null
                && caseId.equals(channelProperties.getL4a().getGuardFrequencyCaseId());
    }

    private boolean isGuardNoPhoneCase(Long caseId) {
        return caseId != null && caseId.equals(channelProperties.getL4a().getGuardNoPhoneCaseId());
    }

    private boolean isRebuildFailCase(Long caseId) {
        return caseId != null && caseId.equals(channelProperties.getL4a().getRebuildFailCaseId());
    }

    private boolean isObservationCase(Long caseId, Stage stage) {
        ChannelProperties.L4a l4a = channelProperties.getL4a();
        return caseId != null && caseId == l4a.getObservationCaseId() && stage == Stage.S1;
    }

    private int observationMinutes() {
        int mins = channelProperties.getL4a().getObservationMinutes();
        return mins > 0 ? mins : 1;
    }

    private List<ContactPlanStep> buildFromTemplate(Stage stage) {
        String key = stage != null ? stage.name() : "S1";
        List<ChannelProperties.PlanStepDef> defs = resolveTemplateDefs(key);
        if (defs == null || defs.isEmpty()) {
            log.info("[DefaultPlanFactory] no template for stage={}, fallback PUSH→EMAIL", key);
            return buildFallbackFlow();
        }

        List<ContactPlanStep> steps = new ArrayList<>();
        int order = 1;
        for (ChannelProperties.PlanStepDef def : defs) {
            ChannelType channelType = parseChannel(def.getChannel());
            if (channelType == null || channelType == ChannelType.HUMAN_CALL) {
                continue;
            }
            steps.add(
                    buildStep(
                            order++,
                            channelType,
                            def.getDelayMin(),
                            def.getObserveMin(),
                            def.getTemplateId()));
        }
        return steps;
    }

    /** DB(t_contact_plan_template) 优先，未命中回落 YAML plan-templates（含 S1 兜底）。 */
    private List<ChannelProperties.PlanStepDef> resolveTemplateDefs(String stageKey) {
        List<ChannelProperties.PlanStepDef> dbDefs = templateProvider.getPlanSteps(stageKey);
        if (dbDefs != null && !dbDefs.isEmpty()) {
            return dbDefs;
        }
        Map<String, ChannelProperties.PlanTemplate> templates = channelProperties.getPlanTemplates();
        ChannelProperties.PlanTemplate tpl = templates.get(stageKey);
        if (tpl == null) {
            tpl = templates.get("S1");
        }
        return tpl == null ? null : tpl.getSteps();
    }

    private static ChannelType parseChannel(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        try {
            return ChannelType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<ContactPlanStep> buildFallbackFlow() {
        List<ContactPlanStep> steps = new ArrayList<>();
        steps.add(buildStep(1, ChannelType.PUSH, 0, 0, 102L));
        steps.add(buildStep(2, ChannelType.EMAIL, 1, 0, 201L));
        return steps;
    }

    private List<ContactPlanStep> buildLegacyThreeStep() {
        List<ContactPlanStep> steps = new ArrayList<>();
        steps.add(buildStep(1, ChannelType.SMS, 0, 0, 101L));
        steps.add(buildStep(2, ChannelType.PUSH, 1, 0, 102L));
        steps.add(buildStep(3, ChannelType.EMAIL, 1, 0, 201L));
        return steps;
    }

    private List<ContactPlanStep> buildSingleStep(String channelName) {
        ChannelType type;
        long templateId;
        switch (channelName) {
            case "SMS":
                type = ChannelType.SMS;
                templateId = 101L;
                break;
            case "PUSH":
                type = ChannelType.PUSH;
                templateId = 102L;
                break;
            case "EMAIL":
                type = ChannelType.EMAIL;
                templateId = 201L;
                break;
            case "AI_CALL":
                type = ChannelType.AI_CALL;
                templateId = 301L;
                break;
            default:
                log.warn("[DefaultPlanFactory] unknown single-step={}, use SMS", channelName);
                type = ChannelType.SMS;
                templateId = 101L;
        }
        List<ContactPlanStep> steps = new ArrayList<>();
        steps.add(buildStep(1, type, 0, 0, templateId));
        return steps;
    }

    private ContactPlanStep buildStep(
            int order, ChannelType channel, int delayMin, int obsMin, long templateId) {
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
