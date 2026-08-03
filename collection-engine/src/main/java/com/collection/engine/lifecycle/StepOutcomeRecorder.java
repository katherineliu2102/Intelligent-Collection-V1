package com.collection.engine.lifecycle;

import com.collection.common.dto.StepCommand;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.DataSource;
import com.collection.common.enums.Direction;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.StepStatus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContactRecord;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 外部渠道 I/O 完成后的短事务：只在状态迁移成功时记录最终触达事实。 事件由调用者在本事务提交返回后发布，避免消费者读取到未提交状态。 */
@Component
public class StepOutcomeRecorder {

    @Resource private ContactPlanRepository planRepository;
    @Resource private TimelineRepository timelineRepository;
    @Resource private DeliveryAuditMetadata deliveryAuditMetadata;
    private final ThreadLocal<StepCommand> pendingAuditCommand = new ThreadLocal<>();

    /** 在同一执行线程中暂存已解析命令，供后续状态落库时写入无 PII 审计字段。 */
    public void prepareAudit(StepCommand command) {
        if (command != null) {
            pendingAuditCommand.set(command);
        }
    }

    @Transactional
    public boolean recordTerminal(
            ContactPlan plan,
            ContactPlanStep step,
            List<StepStatus> expectedStatuses,
            StepStatus targetStatus,
            ContactResult result,
            ChannelType channel,
            String providerMsgId,
            String providerCallback) {
        return recordTerminal(
                plan,
                step,
                expectedStatuses,
                targetStatus,
                result,
                channel,
                providerMsgId,
                providerCallback,
                takePendingAuditCommand());
    }

    @Transactional
    public boolean recordTerminal(
            ContactPlan plan,
            ContactPlanStep step,
            List<StepStatus> expectedStatuses,
            StepStatus targetStatus,
            ContactResult result,
            ChannelType channel,
            String providerMsgId,
            String providerCallback,
            StepCommand command) {
        if (!planRepository.transitionStepStatus(
                step.getId(), expectedStatuses, targetStatus, result)) {
            return false;
        }
        ContactRecord record = new ContactRecord();
        record.setCaseId(plan.getCaseId());
        record.setUserId(plan.getUserId());
        record.setPlanId(plan.getId());
        record.setStepId(step.getId());
        record.setAttemptKey(plan.getId() + ":" + step.getStepOrder() + ":" + step.getRetryCount());
        record.setChannel(channel);
        record.setDirection(Direction.OUT);
        record.setTemplateId(step.getTemplateId());
        record.setResult(result);
        record.setProviderMsgId(providerMsgId);
        record.setProviderCallback(providerCallback);
        record.setSource(DataSource.SYSTEM);
        if (deliveryAuditMetadata != null) {
            deliveryAuditMetadata.apply(record, command);
        }
        timelineRepository.writeTimeline(record);
        return true;
    }

    public boolean recordTerminal(
            ContactPlan plan,
            ContactPlanStep step,
            StepStatus expectedStatus,
            StepStatus targetStatus,
            ContactResult result,
            ChannelType channel,
            String providerMsgId,
            String providerCallback) {
        return recordTerminal(
                plan,
                step,
                Arrays.asList(expectedStatus),
                targetStatus,
                result,
                channel,
                providerMsgId,
                providerCallback);
    }

    public boolean recordTerminal(
            ContactPlan plan,
            ContactPlanStep step,
            StepStatus expectedStatus,
            StepStatus targetStatus,
            ContactResult result,
            ChannelType channel,
            String providerMsgId,
            String providerCallback,
            StepCommand command) {
        return recordTerminal(
                plan,
                step,
                Arrays.asList(expectedStatus),
                targetStatus,
                result,
                channel,
                providerMsgId,
                providerCallback,
                command);
    }

    /** 消息观察期：投递事实、默认推进结果与计划等待态必须一起提交。 */
    @Transactional
    public boolean recordWaiting(
            ContactPlan plan,
            ContactPlanStep step,
            ChannelType channel,
            ContactResult deliveryResult,
            String providerMsgId,
            LocalDateTime observationEnd) {
        return recordWaiting(
                plan,
                step,
                channel,
                deliveryResult,
                providerMsgId,
                observationEnd,
                takePendingAuditCommand());
    }

    @Transactional
    public boolean recordWaiting(
            ContactPlan plan,
            ContactPlanStep step,
            ChannelType channel,
            ContactResult deliveryResult,
            String providerMsgId,
            LocalDateTime observationEnd,
            StepCommand command) {
        if (!planRepository.transitionStepStatus(
                step.getId(),
                StepStatus.EXECUTING,
                StepStatus.EXECUTING,
                ContactResult.SENT_NO_RESPONSE)) {
            return false;
        }
        ContactRecord record = new ContactRecord();
        record.setCaseId(plan.getCaseId());
        record.setUserId(plan.getUserId());
        record.setPlanId(plan.getId());
        record.setStepId(step.getId());
        record.setAttemptKey(plan.getId() + ":" + step.getStepOrder() + ":" + step.getRetryCount());
        record.setChannel(channel);
        record.setDirection(Direction.OUT);
        record.setTemplateId(step.getTemplateId());
        record.setResult(deliveryResult);
        record.setProviderMsgId(providerMsgId);
        record.setSource(DataSource.SYSTEM);
        if (deliveryAuditMetadata != null) {
            deliveryAuditMetadata.apply(record, command);
        }
        timelineRepository.writeTimeline(record);
        planRepository.updateStepTriggerTime(step.getId(), observationEnd, StepStatus.EXECUTING);
        planRepository.updatePlanStatus(plan.getId(), PlanStatus.STEP_WAITING, null);
        return true;
    }

    private StepCommand takePendingAuditCommand() {
        StepCommand command = pendingAuditCommand.get();
        pendingAuditCommand.remove();
        return command;
    }
}
