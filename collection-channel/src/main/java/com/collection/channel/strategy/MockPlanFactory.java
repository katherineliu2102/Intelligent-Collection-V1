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
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 1 Mock 实现 —— DefaultPlanFactory 的占位。
 *
 * <p>默认简单编排：PUSH → EMAIL；{@code channel.debug.legacy-three-step=true} 时 SMS→PUSH→EMAIL（L4a-1 /
 * TC-REG-01）。
 */
@Component
public class MockPlanFactory implements PlanFactory {

    private static final Logger log = LoggerFactory.getLogger(MockPlanFactory.class);

    @Resource private ChannelProperties channelProperties;

    @Override
    public ContactPlan create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot) {
        if (shouldRejectPlan(caseInfo, snapshot)) {
            return null;
        }

        log.info("[MockPlanFactory] build plan for case {} stage {}", caseInfo.getCaseId(), stage);

        ContactPlan plan = new ContactPlan();
        plan.setCaseId(caseInfo.getCaseId());
        plan.setUserId(caseInfo.getUserId());
        plan.setStage(stage);
        plan.setSteps(buildSteps());
        return plan;
    }

    /** §4.1 入口守卫：CEASED / SETTLED / D+91 / 尚未进入催收窗口，均拒建 plan（优先于 stage 匹配）。 */
    static boolean shouldRejectPlan(CaseInfo caseInfo, ContextSnapshot snapshot) {
        if (caseInfo == null) {
            return true;
        }
        if (isNotCollectible(caseInfo.getCaseStatus()) || caseInfo.isRepaid()) {
            return true;
        }
        if (snapshot != null && snapshot.getCaseContext() != null) {
            if (isNotCollectible(snapshot.getCaseContext().getCollectionStatus())) {
                return true;
            }
            int dpd = snapshot.getCaseContext().getDpd();
            if (dpd >= 91) {
                return true;
            }
            // 下界同样要拦：dpd < -3 表示下一个未还 dueDate 还有 3 天以上，数仓口径下此时 stage 为 null。
            // 缺了这条，提前还清的客户会因为 Stage 兜底被建出 S0 计划。
            if (dpd < Stage.S0.getMinDpd()) {
                return true;
            }
        }
        return false;
    }

    /**
     * SETTLED 与 CEASED 同样不可催。
     *
     * <p>SETTLED 必须单列：数仓口径下无尾款时 {@code dpd=0}，落在 S0 的 [-3,0] 区间内，既躲过 D+91 上界也躲过 dpd&lt;-3
     * 下界——只有结清状态本身能拦住它，否则已还清的客户会被建出 S0 计划并收到催收话术。
     */
    private static boolean isNotCollectible(String collectionStatus) {
        return "CEASED".equalsIgnoreCase(collectionStatus)
                || "SETTLED".equalsIgnoreCase(collectionStatus);
    }

    private List<ContactPlanStep> buildSteps() {
        String singleStep = channelProperties.getDebug().getSingleStep();
        if (StringUtils.isNotBlank(singleStep)) {
            return buildSingleStep(singleStep.trim().toUpperCase(Locale.ROOT));
        }
        if (channelProperties.getDebug().isLegacyThreeStep()) {
            return buildLegacyThreeStep();
        }
        return buildPushEmailFlow();
    }

    /** 简单 Email/Push 编排：PUSH(0) → EMAIL(1min)。 */
    private List<ContactPlanStep> buildPushEmailFlow() {
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
                log.warn("[MockPlanFactory] unknown single-step={}, use SMS", channelName);
                type = ChannelType.SMS;
                templateId = 101L;
        }
        List<ContactPlanStep> steps = new ArrayList<>();
        steps.add(buildStep(1, type, 0, 0, templateId));
        return steps;
    }

    private ContactPlanStep buildStep(
            int order, ChannelType channel, int delayMin, int obsMin, Long templateId) {
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
