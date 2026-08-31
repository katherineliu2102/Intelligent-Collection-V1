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
import com.collection.common.service.ComplianceCounterService;
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
import org.slf4j.MDC;
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

    /** 可选：Guard 未预占配额（自定义实现或未配日限）时全程用不到，纯逻辑单测也不必注入。 */
    @Autowired(required = false)
    private ComplianceCounterService complianceCounterService;

    /** 字段默认值保证手工构造（纯逻辑单测）时不为 null；Spring 环境由容器覆盖为共享注册表。 */
    @Resource
    private com.collection.engine.metrics.CollectionMetrics metrics =
            com.collection.engine.metrics.CollectionMetrics.local();

    @Resource private com.collection.engine.config.EngineProperties props;

    public void executeStep(ContactPlan plan, ContactPlanStep step) {
        Map<String, String> priorMdc = putStepMdc(plan, step);
        try {
            executeStepWithMdc(plan, step);
        } finally {
            restoreMdc(priorMdc);
        }
    }

    /**
     * 管线全程带 plan/step/channel 的 MDC。
     *
     * <p>事件总线消费线程已按事件载荷写入 {@code planId}/{@code stepId}，但那条路径只在 Redis 实现下完整（{@code
     * InMemoryEventBus} 不写 stepId），且重试与 SPI 跨线程后要靠它续上。 这里按入参再写一次，使「Guard 拦截 / 渠道失败」的日志无论由谁触发都能关联到
     * plan/step ——T3o-O3 与 T5-R11 的断言即在此。
     */
    private Map<String, String> putStepMdc(ContactPlan plan, ContactPlanStep step) {
        Map<String, String> prior = new LinkedHashMap<>();
        putMdc(prior, "caseId", plan == null ? null : plan.getCaseId());
        putMdc(prior, "planId", plan == null ? null : plan.getId());
        putMdc(prior, "stepId", step == null ? null : step.getId());
        putMdc(prior, "stepOrder", step == null ? null : step.getStepOrder());
        putMdc(prior, "channel", step == null ? null : step.getChannelType());
        return prior;
    }

    private static void putMdc(Map<String, String> prior, String key, Object value) {
        prior.put(key, MDC.get(key));
        if (value != null) {
            MDC.put(key, String.valueOf(value));
        }
    }

    private static void restoreMdc(Map<String, String> prior) {
        prior.forEach(
                (key, value) -> {
                    if (value == null) {
                        MDC.remove(key);
                    } else {
                        MDC.put(key, value);
                    }
                });
    }

    private void executeStepWithMdc(ContactPlan plan, ContactPlanStep step) {
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

    /** 执行锁已获取后的管线。调用渠道前抛异常时，外层释放锁再 NACK，避免 PEL 重投被旧锁吸收； 一旦调用渠道，锁须保留到 TTL，由渠道幂等和 §7.3 处理外部副作用。 */
    private void executeStepAfterLock(
            ContactPlan plan, ContactPlanStep step, ExecutionState state) {
        // ── ② 系统级守卫（实时查 DB：案件存在 / 已还款） ──
        PreFlightResult preFlight = preFlightChecker.inspect(plan.getCaseId());
        if (!preFlight.isPassed()) {
            // prepareStepDue 已将步骤前置为 EXECUTING；业务性阻断必须收敛为计划终态，
            // 否则消息渠道没有 callback timeout 会永久滞留。案件不存在不写 timeline。
            planRepository.updatePlanStatus(
                    plan.getId(), PlanStatus.PLAN_CANCELLED, preFlight.getBlockingReason());
            int closed = planRepository.skipOpenSteps(plan.getId(), ContactResult.SKIPPED);
            log.info(
                    "[execStep] preflight blocked plan {} → PLAN_CANCELLED ({}) skippedOpen={}",
                    plan.getId(),
                    preFlight.getBlockingReason(),
                    closed);
            return;
        }

        // 调用渠道前的最后一道闸：prepareStepDue 提交后到此处之间，回调或超时路径可能已把
        // 步骤收敛为终态，此时继续执行就是一次重复触达。
        if (!planRepository.markStepExecuting(step.getId())) {
            log.info("[execStep] step {} 已终结，放弃执行（避免重复触达）", step.getId());
            return;
        }
        ExecutionContext context = contextAssembler.assemble(plan, step);
        refreshVolatileFields(context, preFlight.getCaseInfo());

        // ── ③ 业务级守卫（合规，硬超时见 engine.spi.execution-guard-timeout-ms，默认 50ms） ──
        GuardVerdict verdict;
        try {
            verdict =
                    spiInvoker.call(
                            SpiType.EXECUTION_GUARD, () -> executionGuard.evaluate(context));
        } catch (Exception e) {
            // fail-close：异常或超时均标记 SKIPPED + 告警，推进下一步（核心引擎规格 §5）
            log.warn("[execStep] ExecutionGuard failed (fail-close → SKIPPED): {}", e.getMessage());
            markSkipped(plan, step, ContactResult.COMPLIANCE_BLOCKED, "GUARD_ERROR");
            return;
        }
        // 非法 null 与抛异常同等处理：合规无法判断时宁可漏触达，也不得因 NPE 让事件反复重投直至 DLQ。
        if (verdict == null) {
            log.warn(
                    "[execStep] ExecutionGuard returned null (fail-close → SKIPPED) step {}",
                    step.getId());
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
        // Guard 已预占日频配额；下面每条「确认未发出」的出口都要归还，否则这次没送达的尝试
        // 会白扣客户当天该渠道的额度（生产日限为 1 时即当天零触达）。
        GuardVerdict.QuotaReservation reservation = verdict.getQuotaReservation();

        // ── ④ 步骤解析（零 DB I/O，硬超时 50ms） ──
        StepCommand command;
        long resolveStartNanos = System.nanoTime();
        try {
            command = spiInvoker.call(SpiType.STEP_RESOLVER, () -> stepResolver.resolve(context));
        } catch (Exception e) {
            // 异常 / 超时 → FAILED → 推进（核心引擎规格 §4.1）
            log.warn("[execStep] StepResolver failed → FAILED: {}", e.getMessage());
            releaseQuota(reservation, "RESOLVER_ERROR");
            markFailed(plan, step, "RESOLVER_ERROR");
            return;
        }
        // 返回 null = Resolver 策略性主动跳过该步 → SKIPPED 推进，不算失败且不写 timeline。
        // 空地址必须在前置 Guard 返回 NO_EMAIL / NO_PHONE / NO_TOKEN。
        if (command == null) {
            log.info(
                    "[execStep] StepResolver returned null → SKIPPED (no-op) step {}",
                    step.getId());
            releaseQuota(reservation, "RESOLVER_NO_OP");
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
            // retryable=true 是渠道对「请求未写给供应商」的证明（熔断未调用、凭证缺失、连接被拒、
            // 供应商显式拒绝受理），此时这次触达确定没出网，预占的配额必须还回去。
            // retryable=false 属结果未知（读超时、写后中断、5xx），宁可少发一次也不归还——
            // 重复骚扰是合规事故，漏一次只是少一次触达。
            if (result.isRetryable()) {
                releaseQuota(reservation, result.getErrorCode());
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

    /** 归还 Guard 的配额预占。失败只告警：少还一次配额偏保守，不值得让已收敛的步骤重新抛错。 */
    private void releaseQuota(GuardVerdict.QuotaReservation reservation, String reason) {
        if (reservation == null || complianceCounterService == null) {
            return;
        }
        try {
            complianceCounterService.release(
                    reservation.getUserId(), reservation.getChannel(), reservation.getDate());
            log.info(
                    "[execStep] released daily quota user={} channel={} date={} ({}, not dispatched)",
                    reservation.getUserId(),
                    reservation.getChannel(),
                    reservation.getDate(),
                    reason);
        } catch (RuntimeException e) {
            log.error(
                    "[execStep] failed to release daily quota user={} channel={} date={}",
                    reservation.getUserId(),
                    reservation.getChannel(),
                    reservation.getDate(),
                    e);
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
     * 提交后即时发布。事件已由状态迁移所在事务写入发件箱（核心引擎规格 §7.2），发布成功即销账； 发布抛错或进程在此处被杀，都只是留下一条待重发记录，由 {@code
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
     * <p>stage 跟当天投影：同一计划跨 DPD 边界时句子跟今天档，不跟建计划时冻住的 snapshot.stage。
     */
    private void refreshVolatileFields(ExecutionContext context, CaseInfo info) {
        if (context == null || info == null || context.getContextSnapshot() == null) {
            return;
        }
        CaseContext ctx = context.getContextSnapshot().getCaseContext();
        if (ctx == null) {
            return;
        }
        ctx.setStage(info.getStage());
        ctx.setDpd(info.getDpd());
        if (info.getTotalOutstanding() != null) {
            ctx.setTotalOutstanding(info.getTotalOutstanding());
        }
        if (info.getOverdueAmount() != null) {
            ctx.setOverdueAmount(info.getOverdueAmount());
        }
        if (info.getPenaltyAmount() != null) {
            ctx.setPenaltyAmount(info.getPenaltyAmount());
        }
        if (info.getUpcomingAmount() != null) {
            ctx.setUpcomingAmount(info.getUpcomingAmount());
        }
        if (info.getNextDueDate() != null) {
            ctx.setNextDueDate(info.getNextDueDate());
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
