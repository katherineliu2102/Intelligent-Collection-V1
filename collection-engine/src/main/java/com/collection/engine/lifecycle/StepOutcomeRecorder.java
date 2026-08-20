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
import com.collection.engine.outbox.OutboxEventSink;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 外部渠道 I/O 完成后的短事务：只在状态迁移成功时记录最终触达事实。 事件由调用者在本事务提交返回后发布，避免消费者读取到未提交状态。
 *
 * <p>步骤转终态即意味着 STEP_COMPLETED 已产生，因此该事件在本事务内一并入发件箱（核心引擎规格 §7.4）： 调用者随后的即时发布若失败，重投的原事件会因步骤已是终态而按
 * no-op 返回，事件不会被重新推导。 调用者用 {@link EngineEvents#stepCompleted} 构造要发布的事件，与入箱记录共享确定性 eventId。
 */
@Component
public class StepOutcomeRecorder {

    @Resource private ContactPlanRepository planRepository;
    @Resource private TimelineRepository timelineRepository;
    @Resource private DeliveryAuditMetadata deliveryAuditMetadata;

    @Autowired(required = false)
    private OutboxEventSink outboxEventSink;

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
        if (outboxEventSink != null) {
            outboxEventSink.enqueue(EngineEvents.stepCompleted(plan, step));
        }
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

    /** 策略未选择该步骤：不代表一次触达或合规拦截，故不写 timeline。 但状态迁移与 STEP_COMPLETED 入箱仍须同事务，否则跳过成功、事件丢失，计划照样停摆。 */
    @Transactional
    public boolean recordStrategySkipped(ContactPlan plan, ContactPlanStep step) {
        if (!planRepository.transitionStepStatus(
                step.getId(), StepStatus.EXECUTING, StepStatus.SKIPPED, ContactResult.SKIPPED)) {
            return false;
        }
        if (outboxEventSink != null) {
            outboxEventSink.enqueue(EngineEvents.stepCompleted(plan, step));
        }
        return true;
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
