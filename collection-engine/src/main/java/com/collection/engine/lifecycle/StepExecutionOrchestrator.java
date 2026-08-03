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
import com.collection.common.model.DecisionLog;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.DecisionLogRepository;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.service.IdempotencyService;
import com.collection.common.spi.ExecutionGuard;
import com.collection.common.spi.StepResolver;
import com.collection.common.util.JsonUtil;
import com.collection.engine.spi.SpiInvoker;
import com.collection.engine.spi.SpiType;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 步骤执行骨架。对应核心引擎规格 §3.1 七步管线。
 *
 * <p>运行在<b>非事务上下文</b>（行锁已由 PlanLifecycleManager 短事务释放）。 七步：幂等 → 系统守卫 → 业务守卫 → 解析 → 渠道调度 → 取消复检+故障降级
 * → 渠道分流。
 */
@Component
public class StepExecutionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StepExecutionOrchestrator.class);

    @Resource private IdempotencyService idempotencyService;
    @Resource private PreFlightChecker preFlightChecker;
    @Resource private ExecutionGuard executionGuard;
    @Resource private StepResolver stepResolver;
    @Resource private ChannelGateway channelGateway;
    @Resource private ContextAssembler contextAssembler;
    @Resource private ContactPlanRepository planRepository;
    @Resource private TimelineRepository timelineRepository;
    @Resource private StepOutcomeRecorder stepOutcomeRecorder;
    @Resource private DeliveryAuditMetadata deliveryAuditMetadata;
    @Resource private DecisionLogRepository decisionLogRepository;
    @Resource private CollectionEventBus eventBus;
    @Resource private SpiInvoker spiInvoker;
    @Resource private com.collection.engine.config.EngineProperties props;

    public void executeStep(ContactPlan plan, ContactPlanStep step) {
        String idempotencyKey = buildIdempotencyKey(plan, step);

        // ── ① 幂等锁 ──
        if (!idempotencyService.acquire(
                idempotencyKey, props.getStep().effectiveIdempotencyTtlMinutes())) {
            log.info("[execStep] duplicate event, key={} skipped", idempotencyKey);
            return;
        }

        // ── ② 系统级守卫（实时查 DB：案件存在 / 已还款） ──
        CancelReason preFlightBlock = preFlightChecker.blockingReason(plan.getCaseId());
        if (preFlightBlock != null) {
            // prepareStepDue 已将步骤前置为 EXECUTING；业务性阻断必须收敛为计划终态，
            // 否则消息渠道没有 callback timeout 会永久滞留。案件不存在不写 timeline。
            planRepository.updatePlanStatus(
                    plan.getId(), PlanStatus.PLAN_CANCELLED, preFlightBlock);
            log.info(
                    "[execStep] preflight blocked plan {} → PLAN_CANCELLED ({})",
                    plan.getId(),
                    preFlightBlock);
            return;
        }

        planRepository.markStepExecuting(step.getId());
        ExecutionContext context = contextAssembler.assemble(plan, step);

        // ── ③ 业务级守卫（合规，硬超时 20ms） ──
        GuardVerdict verdict;
        try {
            verdict =
                    spiInvoker.call(
                            SpiType.EXECUTION_GUARD, () -> executionGuard.evaluate(context));
        } catch (Exception e) {
            // fail-close：异常或超时均标记 SKIPPED + 告警，推进下一步（核心引擎规格 §4.1）
            log.warn("[execStep] ExecutionGuard failed (fail-close → SKIPPED): {}", e.getMessage());
            markSkipped(plan, step, ContactResult.COMPLIANCE_BLOCKED, "GUARD_ERROR");
            return;
        }
        if (!verdict.isAllowed()) {
            log.info(
                    "[execStep] blocked by guard: {} / {}",
                    verdict.getBlockedRuleType(),
                    verdict.getBlockedReason());
            if (verdict.getDeferUntil() != null) {
                planRepository.updateStepTriggerTime(
                        step.getId(), verdict.getDeferUntil(), StepStatus.PENDING);
                planRepository.updatePlanStatus(plan.getId(), PlanStatus.STEP_SCHEDULED, null);
                log.info(
                        "[execStep] deferred by guard until {}: {}",
                        verdict.getDeferUntil(),
                        verdict.getBlockedRuleType());
                return;
            }
            markSkipped(plan, step, ContactResult.COMPLIANCE_BLOCKED, verdict.getBlockedRuleType());
            return;
        }

        // ── ④ 步骤解析（零 DB I/O，硬超时 50ms） ──
        StepCommand command;
        long resolveStartNanos = System.nanoTime();
        try {
            command = spiInvoker.call(SpiType.STEP_RESOLVER, () -> stepResolver.resolve(context));
        } catch (Exception e) {
            // 异常 / 超时 → FAILED → 推进（核心引擎规格 §4.1）
            log.warn("[execStep] StepResolver failed → FAILED: {}", e.getMessage());
            markFailed(plan, step, "RESOLVER_ERROR");
            return;
        }
        // 返回 null = Resolver 策略性主动跳过该步 → SKIPPED 推进，不算失败且不写 timeline。
        // 空地址必须在前置 Guard 返回 NO_EMAIL / NO_PHONE / NO_TOKEN。
        if (command == null) {
            log.info(
                    "[execStep] StepResolver returned null → SKIPPED (no-op) step {}",
                    step.getId());
            markStrategySkipped(plan, step);
            return;
        }
        // 决策日志：记录 ④ 的渠道/话术决策（step 级，供数仓分析）。fail-open，不阻断触达。
        writeDecisionLog(plan, step, context, command, spiLatencyMs(resolveStartNanos));

        // ── ⑤ 渠道调度（熔断/fallback 对引擎透明；抛异常一律视为 retryable） ──
        StepResult result;
        try {
            result = channelGateway.dispatch(command);
        } catch (RuntimeException e) {
            log.warn("[execStep] ChannelGateway threw, treated as retryable: {}", e.getMessage());
            result =
                    StepResult.builder()
                            .success(false)
                            .contactResult(ContactResult.FAILED)
                            .errorCode("CHANNEL_EXCEPTION")
                            .retryable(true)
                            .build();
        }

        // ── ⑤½ 回写前取消检测 ──
        ContactPlan reloaded = planRepository.findById(plan.getId());
        if (reloaded == null || reloaded.isTerminal()) {
            log.info("[execStep] plan {} cancelled during dispatch, record only", plan.getId());
            writeTimeline(
                    plan,
                    step,
                    command.getChannelType(),
                    result.getContactResult(),
                    result.getProviderMsgId(),
                    command);
            return; // 记录已发出触达，但不推进状态机
        }

        // ── ⑥ 故障降级 ──
        if (!result.isSuccess()) {
            if (result.isRetryable() && step.getRetryCount() < props.getStep().getMaxRetryCount()) {
                planRepository.incrementRetryCount(step.getId());
                int newCount = step.getRetryCount() + 1;
                long delaySec = computeBackoffSeconds(newCount);
                planRepository.updateStepTriggerTime(
                        step.getId(),
                        LocalDateTime.now().plusSeconds(delaySec),
                        StepStatus.PENDING);
                log.info(
                        "[execStep] retry step {} in {}s (attempt {})",
                        step.getId(),
                        delaySec,
                        newCount);
                return; // plan 保持 STEP_EXECUTING
            }
            markFailed(plan, step, result.getErrorCode());
            return;
        }

        // ── ⑦ 渠道分流 ──
        if (command.getChannelType().isMessageChannel()) {
            stepOutcomeRecorder.prepareAudit(command);
            // Phase 1：SMS/PUSH/EMAIL 均同步完成；SMS 忽略 observationMinutes（不进 WAITING）
            // 观察期仅保留给 Phase 2 消息渠道（如 VIBER/WHATSAPP）在 PlanFactory 显式配置时使用
            boolean useObservation =
                    step.getObservationMinutes() > 0
                            && command.getChannelType() != ChannelType.SMS
                            && command.getChannelType() != ChannelType.PUSH
                            && command.getChannelType() != ChannelType.EMAIL;
            if (useObservation) {
                LocalDateTime observationEnd =
                        LocalDateTime.now().plusMinutes(step.getObservationMinutes());
                if (stepOutcomeRecorder.recordWaiting(
                        plan,
                        step,
                        command.getChannelType(),
                        result.getContactResult(),
                        result.getProviderMsgId(),
                        observationEnd)) {
                    log.info(
                            "[execStep] step {} → STEP_WAITING ({}min)",
                            step.getId(),
                            step.getObservationMinutes());
                }
            } else {
                if (stepOutcomeRecorder.recordTerminal(
                        plan,
                        step,
                        StepStatus.EXECUTING,
                        StepStatus.COMPLETED,
                        result.getContactResult(),
                        command.getChannelType(),
                        result.getProviderMsgId(),
                        null)) {
                    publishStepCompleted(plan, step);
                }
            }
        } else {
            // 电话/人工类：保持 STEP_EXECUTING，注册回调超时哨兵，等异步回调
            int timeout = resolveTimeoutMinutes(command);
            planRepository.updateStepTimeoutTime(
                    step.getId(), LocalDateTime.now().plusMinutes(timeout));
            log.info(
                    "[execStep] async step {} → STEP_EXECUTING, callback timeout {}min",
                    step.getId(),
                    timeout);
        }
    }

    private void markSkipped(
            ContactPlan plan, ContactPlanStep step, ContactResult result, String rule) {
        if (stepOutcomeRecorder.recordTerminal(
                plan,
                step,
                StepStatus.EXECUTING,
                StepStatus.SKIPPED,
                result,
                step.getChannelType(),
                null,
                JsonUtil.toJson(java.util.Collections.singletonMap("rule", rule)))) {
            publishStepCompleted(plan, step);
        }
    }

    /** 策略未选择该步骤，不代表一次触达或合规拦截，故不写 timeline。 */
    private void markStrategySkipped(ContactPlan plan, ContactPlanStep step) {
        if (planRepository.transitionStepStatus(
                step.getId(), StepStatus.EXECUTING, StepStatus.SKIPPED, ContactResult.SKIPPED)) {
            publishStepCompleted(plan, step);
        }
    }

    private void markFailed(ContactPlan plan, ContactPlanStep step, String errorCode) {
        if (stepOutcomeRecorder.recordTerminal(
                plan,
                step,
                StepStatus.EXECUTING,
                StepStatus.FAILED,
                ContactResult.FAILED,
                step.getChannelType(),
                null,
                JsonUtil.toJson(java.util.Collections.singletonMap("errorCode", errorCode)))) {
            publishStepCompleted(plan, step); // 失败也推进，不卡死
        }
    }

    private void publishStepCompleted(ContactPlan plan, ContactPlanStep step) {
        eventBus.publish(
                CollectionEvent.of(EventType.STEP_COMPLETED)
                        .with(CollectionEvent.CASE_ID, plan.getCaseId())
                        .with(CollectionEvent.USER_ID, plan.getUserId())
                        .with(CollectionEvent.PLAN_ID, plan.getId())
                        .with(CollectionEvent.STEP_ID, step.getId()));
    }

    private void writeTimeline(
            ContactPlan plan,
            ContactPlanStep step,
            ChannelType channel,
            ContactResult result,
            String providerMsgId,
            StepCommand command) {
        ContactRecord r = new ContactRecord();
        r.setCaseId(plan.getCaseId());
        r.setUserId(plan.getUserId());
        r.setPlanId(plan.getId());
        r.setStepId(step.getId());
        r.setAttemptKey(buildAttemptKey(plan, step));
        r.setChannel(channel);
        r.setDirection(Direction.OUT);
        r.setTemplateId(step.getTemplateId());
        r.setResult(result);
        r.setProviderMsgId(providerMsgId);
        r.setSource(DataSource.SYSTEM);
        if (deliveryAuditMetadata != null) {
            deliveryAuditMetadata.apply(r, command);
        }
        timelineRepository.writeTimeline(r);
    }

    /**
     * 写 StepResolver（④）决策日志。Phase 1：RULE 引擎、confidence=1.0；输入快照取 ExecutionContext， 输出取解析后的
     * StepCommand 关键字段。任何异常仅告警不上抛（决策日志只供数仓，绝不影响触达）。
     *
     * <p>注：input_snapshot 含 UserProfile 手机号/邮箱，与 t_contact_plan.context_snapshot 落库口径一致
     * （均为未脱敏快照）；如需脱敏应在此统一处理。
     */
    private void writeDecisionLog(
            ContactPlan plan,
            ContactPlanStep step,
            ExecutionContext context,
            StepCommand command,
            Integer latencyMs) {
        com.collection.engine.config.EngineProperties.DecisionLog cfg = props.getDecisionLog();
        if (!cfg.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("channelType", command.getChannelType());
            output.put("templateId", command.getTemplateId());
            output.put("scriptSlot", command.getMetadata().get(StepCommand.META_SCRIPT_SLOT));

            DecisionLog dl = new DecisionLog();
            dl.setCaseId(plan.getCaseId());
            dl.setPlanId(plan.getId());
            dl.setStepId(step.getId());
            dl.setDecisionType(DecisionType.CHANNEL_SELECT);
            dl.setEngineType("RULE");
            dl.setEngineVersion(cfg.getVersion());
            dl.setInputSnapshot(JsonUtil.toJson(context));
            dl.setOutputDecision(JsonUtil.toJson(output));
            dl.setConfidence(1.0);
            dl.setLatencyMs(latencyMs);
            decisionLogRepository.save(dl);
        } catch (Exception e) {
            log.warn(
                    "[execStep] decision log write failed (ignored) step {}: {}",
                    step.getId(),
                    e.getMessage());
        }
    }

    private Integer spiLatencyMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    private long computeBackoffSeconds(int attempt) {
        com.collection.engine.config.EngineProperties.Step s = props.getStep();
        double delay =
                s.getRetryBaseIntervalSeconds() * Math.pow(s.getRetryBackoffFactor(), attempt);
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
        return buildAttemptKey(plan, step);
    }

    private String buildAttemptKey(ContactPlan plan, ContactPlanStep step) {
        return plan.getId() + ":" + step.getStepOrder() + ":" + step.getRetryCount();
    }
}
