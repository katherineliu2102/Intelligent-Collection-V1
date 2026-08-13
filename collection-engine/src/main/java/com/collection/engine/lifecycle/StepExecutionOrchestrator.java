package com.collection.engine.lifecycle;

import com.collection.common.channel.ChannelGateway;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.*;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
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
import com.collection.engine.outbox.OutboxEventSink;
import com.collection.engine.spi.SpiInvoker;
import com.collection.engine.spi.SpiType;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String STEP_LOCK_PREFIX = "lock:plan:";
    private static final ZoneId PHT = ZoneId.of("Asia/Manila");

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

    @Autowired(required = false)
    private OutboxEventSink outboxEventSink;

    /** 字段默认值保证手工构造（纯逻辑单测）时不为 null；Spring 环境由容器覆盖为共享注册表。 */
    @Resource
    private com.collection.engine.metrics.CollectionMetrics metrics =
            com.collection.engine.metrics.CollectionMetrics.local();

    @Resource private com.collection.engine.config.EngineProperties props;

    public void executeStep(ContactPlan plan, ContactPlanStep step) {
        String idempotencyKey = buildIdempotencyKey(plan, step);
        String executionLockKey = STEP_LOCK_PREFIX + idempotencyKey;

        if (!idempotencyService.acquire(
                executionLockKey, props.getStep().effectiveIdempotencyTtlMinutes())) {
            log.info("[execStep] duplicate event, key={} skipped", idempotencyKey);
            return;
        }

        ExecutionState state = new ExecutionState(executionLockKey);
        try {
            executeStepAfterLock(plan, step, state);
        } catch (RuntimeException e) {
            if (!state.dispatchStarted) {
                releaseExecutionLock(state.executionLockKey, idempotencyKey);
            }
            throw e;
        }
    }

    /** 执行锁已获取后的管线。调用渠道前抛异常时，外层释放锁再 NACK，避免 PEL 重投被旧锁吸收； 一旦调用渠道，锁须保留到 TTL，由渠道幂等和 §7.4 处理外部副作用。 */
    private void executeStepAfterLock(
            ContactPlan plan, ContactPlanStep step, ExecutionState state) {
        // ── ② 系统级守卫（实时查 DB：案件存在 / 已还款） ──
        PreFlightResult preFlight = preFlightChecker.inspect(plan.getCaseId());
        if (!preFlight.isPassed()) {
            // prepareStepDue 已将步骤前置为 EXECUTING；业务性阻断必须收敛为计划终态，
            // 否则消息渠道没有 callback timeout 会永久滞留。案件不存在不写 timeline。
            planRepository.updatePlanStatus(
                    plan.getId(), PlanStatus.PLAN_CANCELLED, preFlight.getBlockingReason());
            log.info(
                    "[execStep] preflight blocked plan {} → PLAN_CANCELLED ({})",
                    plan.getId(),
                    preFlight.getBlockingReason());
            return;
        }

        planRepository.markStepExecuting(step.getId());
        ExecutionContext context = contextAssembler.assemble(plan, step);
        refreshVolatileFields(context, preFlight.getCaseInfo());

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
                releaseExecutionLock(state.executionLockKey, buildIdempotencyKey(plan, step));
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

        // ── ⑤ 渠道调度（熔断/fallback 对引擎透明） ──
        StepResult result;
        long dispatchStartNanos = System.nanoTime();
        metrics.touch(command.getChannelType().name());
        state.dispatchStarted = true;
        try {
            result = channelGateway.dispatch(command);
        } catch (RuntimeException e) {
            log.error(
                    "[execStep] ChannelGateway threw after dispatch began; outcome unknown, no retry: {}",
                    e.getMessage());
            result =
                    StepResult.builder()
                            .success(false)
                            .contactResult(ContactResult.FAILED)
                            .errorCode("CHANNEL_OUTCOME_UNKNOWN")
                            .retryable(false)
                            .build();
        }
        metrics.stepDuration(
                command.getChannelType().name(), System.nanoTime() - dispatchStartNanos);

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
                        LocalDateTime.now(PHT).plusSeconds(delaySec),
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

        // 供应商已受理。executed_at 只说明引擎开始尝试，此处才是触达真正发出的时刻；
        // 二者的差值是排查"卡在调用前"与"已发出未回写"的唯一依据。
        planRepository.markStepDispatched(step.getId());

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
            // AI_CALL：先落渠道受理事实（含合作方任务标识），再注册超时哨兵并等待回调。
            // 该记录与回调/超时使用同一 attemptKey，回调终态会通过 timeline upsert 覆盖。
            writeTimeline(
                    plan,
                    step,
                    command.getChannelType(),
                    result.getContactResult(),
                    result.getProviderMsgId(),
                    command);
            int timeout = resolveTimeoutMinutes(command);
            planRepository.updateStepTimeoutTime(
                    step.getId(), LocalDateTime.now(PHT).plusMinutes(timeout));
            log.info(
                    "[execStep] async step {} → STEP_EXECUTING, callback timeout {}min",
                    step.getId(),
                    timeout);
        }
    }

    private void releaseExecutionLock(String executionLockKey, String idempotencyKey) {
        try {
            idempotencyService.release(executionLockKey);
        } catch (RuntimeException releaseError) {
            log.error(
                    "[execStep] failed to release pre-dispatch execution lock, key={}",
                    idempotencyKey,
                    releaseError);
        }
    }

    private static final class ExecutionState {
        private final String executionLockKey;
        private boolean dispatchStarted;

        private ExecutionState(String executionLockKey) {
            this.executionLockKey = executionLockKey;
        }
    }

    private void markSkipped(
            ContactPlan plan, ContactPlanStep step, ContactResult result, String rule) {
        metrics.stepSkipped(rule == null ? "UNKNOWN" : rule);
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
        if (stepOutcomeRecorder.recordStrategySkipped(plan, step)) {
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

    /**
     * 提交后即时发布。事件已由状态迁移所在事务写入发件箱（核心引擎规格 §7.4），发布成功即销账； 发布抛错或进程在此处被杀，都只是留下一条待重发记录，由 {@code
     * OutboxPublisher} 兜底。
     */
    private void publishStepCompleted(ContactPlan plan, ContactPlanStep step) {
        CollectionEvent event = EngineEvents.stepCompleted(plan, step);
        eventBus.publish(event);
        if (outboxEventSink != null) {
            outboxEventSink.markDelivered(event);
        }
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
    /**
     * 用步骤② 已读到的实时案件数据覆盖快照中的<b>日变字段</b>（仅内存，不回写 {@code context_snapshot}）。
     *
     * <p>文案里的逾期天数与金额必须是发送时刻的值：快照的 dpd 冻结于建计划时刻，而单个阶段最长跨 60 天 （S4 = DPD
     * 31–90），不刷新会连续数十天对用户播报错误的逾期天数；余额同理，仅靠 CASE_BALANCE_UPDATED 只能覆盖还款场景。
     *
     * <p><b>不覆盖 stage</b>：阶段决定模板与话术，必须与所属计划一致，否则同一计划内会串话术。 实际渲染用的数值随 {@code
     * t_decision_log.input_snapshot} 落库，保留审计能力。
     */
    private void refreshVolatileFields(ExecutionContext context, CaseInfo info) {
        if (context == null || info == null || context.getContextSnapshot() == null) {
            return;
        }
        CaseContext ctx = context.getContextSnapshot().getCaseContext();
        if (ctx == null) {
            return;
        }
        ctx.setDpd(info.getDpd());
        if (info.getTotalOutstanding() != null) {
            ctx.setTotalOutstanding(info.getTotalOutstanding());
        }
    }

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
