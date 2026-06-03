package com.collection.engine.lifecycle;

import com.collection.common.channel.ChannelGateway;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.*;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContactRecord;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.service.IdempotencyService;
import com.collection.common.spi.ExecutionGuard;
import com.collection.common.spi.StepResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 步骤执行骨架。对应核心引擎规格 §3.1 七步管线。
 *
 * <p>运行在<b>非事务上下文</b>（行锁已由 PlanLifecycleManager 短事务释放）。
 * 七步：幂等 → 系统守卫 → 业务守卫 → 解析 → 渠道调度 → 取消复检+故障降级 → 渠道分流。
 */
@Component
public class StepExecutionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StepExecutionOrchestrator.class);

    @Resource
    private IdempotencyService idempotencyService;
    @Resource
    private PreFlightChecker preFlightChecker;
    @Resource
    private ExecutionGuard executionGuard;
    @Resource
    private StepResolver stepResolver;
    @Resource
    private ChannelGateway channelGateway;
    @Resource
    private ContextAssembler contextAssembler;
    @Resource
    private ContactPlanRepository planRepository;
    @Resource
    private TimelineRepository timelineRepository;
    @Resource
    private CollectionEventBus eventBus;
    @Resource
    private com.collection.engine.config.EngineProperties props;

    public void executeStep(ContactPlan plan, ContactPlanStep step) {
        String idempotencyKey = buildIdempotencyKey(plan, step);

        // ── ① 幂等锁 ──
        if (!idempotencyService.acquire(idempotencyKey, props.getStep().getIdempotencyTtlMinutes())) {
            log.info("[execStep] duplicate event, key={} skipped", idempotencyKey);
            return;
        }

        // ── ② 系统级守卫（实时查 DB：还款/冻结/关闭） ──
        if (!preFlightChecker.check(plan.getCaseId())) {
            return; // 静默退出
        }

        planRepository.updateStepStatus(step.getId(), StepStatus.EXECUTING, null);
        ExecutionContext context = contextAssembler.assemble(plan, step);

        // ── ③ 业务级守卫（合规） ──
        GuardVerdict verdict;
        try {
            verdict = executionGuard.evaluate(context);
        } catch (Exception e) {
            // fail-close：标记 SKIPPED + 告警，推进下一步（核心引擎规格 §4.1）
            log.warn("[execStep] ExecutionGuard failed (fail-close → SKIPPED): {}", e.getMessage());
            markSkipped(plan, step, ContactResult.COMPLIANCE_BLOCKED, "GUARD_ERROR");
            return;
        }
        if (!verdict.isAllowed()) {
            log.info("[execStep] blocked by guard: {} / {}", verdict.getBlockedRuleType(), verdict.getBlockedReason());
            markSkipped(plan, step, ContactResult.COMPLIANCE_BLOCKED, verdict.getBlockedRuleType());
            return;
        }

        // ── ④ 步骤解析（零 DB I/O） ──
        StepCommand command;
        try {
            command = stepResolver.resolve(context);
            if (command == null) {
                throw new IllegalStateException("StepResolver returned null");
            }
        } catch (Exception e) {
            log.warn("[execStep] StepResolver failed → FAILED: {}", e.getMessage());
            markFailed(plan, step, "RESOLVER_ERROR");
            return;
        }

        // ── ⑤ 渠道调度（熔断/fallback 对引擎透明；抛异常一律视为 retryable） ──
        StepResult result;
        try {
            result = channelGateway.dispatch(command);
        } catch (RuntimeException e) {
            log.warn("[execStep] ChannelGateway threw, treated as retryable: {}", e.getMessage());
            result = StepResult.builder()
                    .success(false).contactResult(ContactResult.FAILED)
                    .errorCode("CHANNEL_EXCEPTION").retryable(true).build();
        }

        // ── ⑤½ 回写前取消检测 ──
        ContactPlan reloaded = planRepository.findById(plan.getId());
        if (reloaded == null || reloaded.isTerminal()) {
            log.info("[execStep] plan {} cancelled during dispatch, record only", plan.getId());
            writeTimeline(plan, step, command.getChannelType(), result.getContactResult(), result.getProviderMsgId());
            return; // 记录已发出触达，但不推进状态机
        }

        // ── ⑥ 故障降级 ──
        if (!result.isSuccess()) {
            if (result.isRetryable() && step.getRetryCount() < props.getStep().getMaxRetryCount()) {
                planRepository.incrementRetryCount(step.getId());
                int newCount = step.getRetryCount() + 1;
                long delaySec = computeBackoffSeconds(newCount);
                planRepository.updateStepTriggerTime(
                        step.getId(), LocalDateTime.now().plusSeconds(delaySec), StepStatus.PENDING);
                log.info("[execStep] retry step {} in {}s (attempt {})", step.getId(), delaySec, newCount);
                return; // plan 保持 STEP_EXECUTING
            }
            markFailed(plan, step, result.getErrorCode());
            return;
        }

        // ── ⑦ 渠道分流 ──
        writeTimeline(plan, step, command.getChannelType(), result.getContactResult(), result.getProviderMsgId());
        if (command.getChannelType().isMessageChannel()) {
            if (step.getObservationMinutes() > 0) {
                planRepository.updateStepTriggerTime(
                        step.getId(), LocalDateTime.now().plusMinutes(step.getObservationMinutes()), StepStatus.EXECUTING);
                planRepository.updatePlanStatus(plan.getId(), PlanStatus.STEP_WAITING, null);
                log.info("[execStep] step {} → STEP_WAITING ({}min)", step.getId(), step.getObservationMinutes());
            } else {
                planRepository.updateStepStatus(step.getId(), StepStatus.COMPLETED, result.getContactResult());
                publishStepCompleted(plan, step);
            }
        } else {
            // 电话/人工类：保持 STEP_EXECUTING，注册回调超时哨兵，等异步回调
            int timeout = resolveTimeoutMinutes(command);
            planRepository.updateStepTimeoutTime(step.getId(), LocalDateTime.now().plusMinutes(timeout));
            log.info("[execStep] async step {} → STEP_EXECUTING, callback timeout {}min", step.getId(), timeout);
        }
    }

    private void markSkipped(ContactPlan plan, ContactPlanStep step, ContactResult result, String rule) {
        planRepository.updateStepStatus(step.getId(), StepStatus.SKIPPED, result);
        writeTimeline(plan, step, step.getChannelType(), result, null);
        publishStepCompleted(plan, step);
    }

    private void markFailed(ContactPlan plan, ContactPlanStep step, String errorCode) {
        planRepository.updateStepStatus(step.getId(), StepStatus.FAILED, ContactResult.FAILED);
        writeTimeline(plan, step, step.getChannelType(), ContactResult.FAILED, errorCode);
        publishStepCompleted(plan, step); // 失败也推进，不卡死
    }

    private void publishStepCompleted(ContactPlan plan, ContactPlanStep step) {
        eventBus.publish(CollectionEvent.of(EventType.STEP_COMPLETED)
                .with(CollectionEvent.CASE_ID, plan.getCaseId())
                .with(CollectionEvent.USER_ID, plan.getUserId())
                .with(CollectionEvent.PLAN_ID, plan.getId())
                .with(CollectionEvent.STEP_ID, step.getId()));
    }

    private void writeTimeline(ContactPlan plan, ContactPlanStep step, ChannelType channel,
                               ContactResult result, String providerMsgId) {
        ContactRecord r = new ContactRecord();
        r.setCaseId(plan.getCaseId());
        r.setUserId(plan.getUserId());
        r.setPlanId(plan.getId());
        r.setStepId(step.getId());
        r.setChannel(channel);
        r.setDirection(Direction.OUT);
        r.setTemplateId(step.getTemplateId());
        r.setResult(result);
        r.setProviderMsgId(providerMsgId);
        r.setSource(DataSource.SYSTEM);
        timelineRepository.writeTimeline(r);
    }

    private long computeBackoffSeconds(int attempt) {
        com.collection.engine.config.EngineProperties.Step s = props.getStep();
        double delay = s.getRetryBaseIntervalSeconds() * Math.pow(s.getRetryBackoffFactor(), attempt);
        return (long) Math.min(delay, s.getRetryMaxIntervalSeconds());
    }

    private int resolveTimeoutMinutes(StepCommand command) {
        Object v = command.getMetadata().get(StepCommand.META_TIMEOUT_MINUTES);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return props.getStep().getCallbackTimeoutMinutes();
    }

    private String buildIdempotencyKey(ContactPlan plan, ContactPlanStep step) {
        return plan.getId() + ":" + step.getStepOrder() + ":" + step.getRetryCount();
    }
}
