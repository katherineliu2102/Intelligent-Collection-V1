package com.collection.channel.strategy;

import com.alibaba.fastjson.JSON;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.StepCommand;
import com.collection.common.email.EmailMilestoneScriptSlots;
import com.collection.common.enums.ChannelType;
import com.collection.common.model.CaseContext;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.spi.StepResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 默认 StepResolver —— 由 ContextSnapshot 解析出 StepCommand（零 DB I/O）。
 *
 * <p>scriptSlot 解析：
 *
 * <ul>
 *   <li>EMAIL：{@link CaseContext#getEmailScriptSlot()} 或 dpd → 里程碑槽；
 *   <li>SMS/PUSH：由 {@code Stage + 渠道 + strategyTone(+dpd)} 推导（见 {@link #deriveMsgScriptSlot}）， S2+
 *       且 {@code strategyTone=FIRM} 选 {@code *_FIRM}；
 *   <li>其余（AI_CALL）：{@code MOCK_<templateId>} 占位。
 * </ul>
 *
 * <p>SMS/Push 文案从 {@code channel.scripts}（{@link ScriptLibrary}）读取并注入 {@code
 * {name}/{amount}/{dpd}/{repaymentUrl}}； 未配置该槽时返回 {@code null}（引擎 SKIPPED），与 EMAIL 一致。Push {@code
 * data.deep_link} 取 repaymentUrl，缺失用 {@code push-default-deep-link} 兜底。
 */
@Slf4j
@Component
public class DefaultStepResolver implements StepResolver {

    private static final DateTimeFormatter ASSIGNMENT_DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    @Resource private ChannelProperties channelProperties;

    @Resource private ScriptLibrary scriptLibrary;

    @Resource private ConfigTemplateProvider templateProvider;

    @Override
    public StepCommand resolve(ExecutionContext context) {
        ContactPlanStep step = context.getCurrentStep();
        if (step.getChannelType() == ChannelType.HUMAN_CALL) {
            throw new IllegalStateException("Phase 1 禁止 plan 内 HUMAN_CALL（对齐待办 E4）");
        }

        ContextSnapshot snapshot = context.getContextSnapshot();
        String scriptSlot = resolveScriptSlot(step, snapshot);

        // EMAIL 主动跳过（返回 null → 引擎 SKIPPED 推进，不 FAILED）：
        //   #1 非里程碑 DPD（无 Phase1 Email 槽）；#5 无有效邮箱（不发占位地址给 SendGrid）
        // 例外：L4a-全-C REBUILD 测试专用 slot → 抛异常让引擎走 FAILED 路径触发 ExhaustionPolicy
        if (step.getChannelType() == ChannelType.EMAIL) {
            if (!EmailMilestoneScriptSlots.isPhase1Active(scriptSlot)) {
                if ("INVALID_L4A_REBUILD_SLOT".equals(scriptSlot)) {
                    throw new RuntimeException(
                            "L4a REBUILD test: invalid slot forces step failure → ExhaustionPolicy");
                }
                return null;
            }
            if (StringUtils.isBlank(extractEmail(snapshot))) {
                return null;
            }
        }

        // SMS/PUSH 同样 fail-close：槽位在 DB 与 YAML 都取不到文案时跳过，不发占位串。
        // 曾经的兜底是 "[MOCK] <slot> <url>" 并照发，一次漏配就等于把内部槽位名与测试域名
        // 投递到真实客户；而漏配是运营动作（新增 stage / 改槽位命名）必然复现的失误。
        if (isMessageChannel(step.getChannelType())
                && !hasScript(step.getChannelType(), scriptSlot, snapshot)) {
            log.error(
                    "[StepResolver] 缺少文案，跳过该步骤 channel={} scriptSlot={} caseId={} —— "
                            + "请补 t_script_template(ACTIVE) 或 channel.scripts",
                    step.getChannelType(),
                    scriptSlot,
                    context.getPlan().getCaseId());
            return null;
        }

        ScriptVars vars = scriptLibrary.buildVars(snapshot);
        Map<String, Object> metadata = new HashMap<>();

        if (context.getPlan().getStage() != null) {
            metadata.put(StepCommand.META_STAGE, context.getPlan().getStage().name());
        }
        metadata.put(StepCommand.META_LANGUAGE, resolveLanguage(snapshot));
        metadata.put(StepCommand.META_SCRIPT_SLOT, scriptSlot);
        long configVersion =
                templateProvider != null ? templateProvider.getCurrentConfigVersion() : 0L;
        metadata.put(StepCommand.META_CONFIG_VERSION, configVersion);
        metadata.put(
                StepCommand.META_TEMPLATE_VERSION,
                resolveTemplateVersion(step.getChannelType(), scriptSlot));

        Long caseId = context.getPlan().getCaseId();
        if (caseId != null) {
            metadata.put(StepCommand.META_CASE_ID, caseId);
        }
        if (context.getPlan().getId() != null) {
            metadata.put("plan_id", context.getPlan().getId());
        }
        if (step.getId() != null) {
            metadata.put("step_id", step.getId());
        }

        fillChannelMetadata(step.getChannelType(), metadata, snapshot, scriptSlot, vars);

        if (step.getChannelType().isAsyncChannel()) {
            String callbackUrl = channelProperties.callbackUrl();
            if (StringUtils.isBlank(callbackUrl)) {
                callbackUrl = channelProperties.getCallback().getBaseUrl();
            }
            metadata.put(StepCommand.META_CALLBACK_URL, callbackUrl);
            metadata.put(StepCommand.META_TIMEOUT_MINUTES, 60);
        }

        return StepCommand.builder()
                .channelType(step.getChannelType())
                .targetAddress(resolveAddress(step.getChannelType(), snapshot, metadata))
                .templateId(
                        step.getTemplateId() == null
                                ? "default"
                                : String.valueOf(step.getTemplateId()))
                .idempotencyKey(
                        context.getPlan().getId()
                                + ":"
                                + step.getStepOrder()
                                + ":"
                                + step.getRetryCount())
                .providerIdempotencyKey(context.getPlan().getId() + ":" + step.getStepOrder())
                .metadata(metadata)
                .build();
    }

    /**
     * 按<b>该槽位实际的内容来源</b>标记 {@code template_version}，而非「DB 配置源是否可用」。
     *
     * <p>{@link ScriptLibrary} 是逐槽位 DB→YAML 回落的：库里没有 ACTIVE 行的槽位仍会发出 YAML 文案。 若按全局 epoch 一律标 {@code
     * db:N}，事后审计会指向错误的配置表，无法复原当时发的内容。
     *
     * <ul>
     *   <li>SMS / PUSH 命中 DB → {@code db:<该行 config_version>}，未命中 → {@code nacos:<releaseVersion>}
     *   <li>EMAIL 正文托管在 SendGrid，本地只传 dynamic data → {@code sendgrid:<模板 ID>}（与 {@code
     *       SendGridEmailAdapter.resolveTemplateId} 同一份映射）
     * </ul>
     */
    private String resolveTemplateVersion(ChannelType channel, String scriptSlot) {
        if (channel == ChannelType.EMAIL) {
            String templateId = channelProperties.getSendgrid().getTemplates().get(scriptSlot);
            if (StringUtils.isNotBlank(templateId)) {
                return "sendgrid:" + templateId;
            }
            return nacosScriptVersion();
        }
        Long dbVersion = null;
        if (templateProvider != null) {
            if (channel == ChannelType.SMS) {
                dbVersion = templateProvider.getSmsVersion(scriptSlot);
            } else if (channel == ChannelType.PUSH) {
                dbVersion = templateProvider.getPushVersion(scriptSlot);
            }
        }
        return dbVersion != null ? "db:" + dbVersion : nacosScriptVersion();
    }

    private String nacosScriptVersion() {
        return "nacos:" + channelProperties.getScripts().getReleaseVersion();
    }

    private static String extractEmail(ContextSnapshot snapshot) {
        if (snapshot != null
                && snapshot.getUserProfile() != null
                && snapshot.getUserProfile().getBasic() != null) {
            return snapshot.getUserProfile().getBasic().getEmail();
        }
        return null;
    }

    private static String resolveLanguage(ContextSnapshot snapshot) {
        if (snapshot != null
                && snapshot.getUserProfile() != null
                && snapshot.getUserProfile().getBasic() != null) {
            String language = snapshot.getUserProfile().getBasic().getLanguage();
            if (StringUtils.isNotBlank(language)) {
                return language;
            }
        }
        return "en";
    }

    private void fillChannelMetadata(
            ChannelType channel,
            Map<String, Object> metadata,
            ContextSnapshot snapshot,
            String scriptSlot,
            ScriptVars vars) {
        CaseContext caseCtx = snapshot != null ? snapshot.getCaseContext() : null;

        // SMS/PUSH 的文案完备性已由 resolve() 的 fail-close 前置保证，此处不再有占位兜底。
        if (channel == ChannelType.AI_CALL) {
            fillAiCallMetadata(metadata, snapshot);
        } else if (channel == ChannelType.SMS) {
            metadata.put(StepCommand.META_SMS_BODY, scriptLibrary.renderSms(scriptSlot, vars));
        } else if (channel == ChannelType.EMAIL) {
            metadata.put(
                    StepCommand.META_DYNAMIC_TEMPLATE_DATA,
                    buildEmailTemplateData(snapshot, scriptSlot));
        } else if (channel == ChannelType.PUSH) {
            PushContent push = scriptLibrary.renderPush(scriptSlot, vars);
            metadata.put(StepCommand.META_TITLE, push.getTitle());
            metadata.put(StepCommand.META_BODY, push.getBody());
            metadata.put(
                    StepCommand.META_PUSH_DATA, buildPushDataJson(snapshot, scriptSlot, metadata));

            // Push 无 token → fallback SMS：复用同阶段 SMS 文案
            String smsSlot = deriveMsgScriptSlot(ChannelType.SMS, caseCtx);
            metadata.put(
                    StepCommand.META_FALLBACK_SMS_BODY, scriptLibrary.renderSms(smsSlot, vars));
        }
    }

    /** Facade AI 外呼的必填业务上下文。缺失金额或姓名时不伪造值，Adapter 会安全拒绝该 dispatch。 */
    private static void fillAiCallMetadata(Map<String, Object> metadata, ContextSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        CaseContext caseCtx = snapshot.getCaseContext();
        if (caseCtx != null) {
            if (caseCtx.getOverdueAmount() != null) {
                metadata.put("overdue_amount", caseCtx.getOverdueAmount().toPlainString());
            }
            metadata.put("dpd", String.valueOf(caseCtx.getDpd()));
            if (caseCtx.getDueDate() != null) {
                metadata.put("due_date", caseCtx.getDueDate().toString());
            }
        }
        if (snapshot.getUserProfile() != null
                && snapshot.getUserProfile().getBasic() != null
                && StringUtils.isNotBlank(snapshot.getUserProfile().getBasic().getName())) {
            metadata.put("borrower_name", snapshot.getUserProfile().getBasic().getName());
        }
    }

    private static boolean isMessageChannel(ChannelType channel) {
        return channel == ChannelType.SMS || channel == ChannelType.PUSH;
    }

    /**
     * 该步骤能否渲染出真实文案。
     *
     * <p>PUSH 额外要求同阶段 SMS 槽位存在：无 token 或投递失败时 PushAdapter 会改发 SMS（[渠道编排规格 §7.1]）， 缺 SMS 文案时那条
     * fallback 同样会退化成占位串。
     */
    private boolean hasScript(ChannelType channel, String scriptSlot, ContextSnapshot snapshot) {
        if (channel == ChannelType.SMS) {
            return scriptLibrary.hasSms(scriptSlot);
        }
        String fallbackSlot =
                deriveMsgScriptSlot(
                        ChannelType.SMS, snapshot != null ? snapshot.getCaseContext() : null);
        return scriptLibrary.hasPush(scriptSlot) && scriptLibrary.hasSms(fallbackSlot);
    }

    private String buildPushDataJson(
            ContextSnapshot snapshot, String scriptSlot, Map<String, Object> metadata) {
        Map<String, String> data = new HashMap<>();
        data.put("scene", "collection");
        data.put("script_slot", scriptSlot);

        String repaymentUrl = null;
        if (snapshot != null && snapshot.getCaseContext() != null) {
            CaseContext ctx = snapshot.getCaseContext();
            if (ctx.getCaseId() != null) {
                data.put("case_id", String.valueOf(ctx.getCaseId()));
            }
            repaymentUrl = ctx.getRepaymentUrl();
        }
        Object caseId = metadata.get(StepCommand.META_CASE_ID);
        if (caseId != null && !data.containsKey("case_id")) {
            data.put("case_id", String.valueOf(caseId));
        }

        String deepLink =
                StringUtils.isNotBlank(repaymentUrl)
                        ? repaymentUrl
                        : scriptLibrary.defaultDeepLink();
        if (StringUtils.isNotBlank(deepLink)) {
            data.put("deep_link", deepLink);
        }
        return JSON.toJSONString(data);
    }

    private Map<String, Object> buildEmailTemplateData(
            ContextSnapshot snapshot, String scriptSlot) {
        Map<String, Object> data = new HashMap<>();
        data.put("script_slot", scriptSlot);
        if (snapshot == null || snapshot.getCaseContext() == null) {
            return data;
        }
        CaseContext ctx = snapshot.getCaseContext();
        if (ctx.getRepaymentUrl() != null) {
            data.put("payment_link", ctx.getRepaymentUrl());
        }
        BigDecimal amountDue = ScriptLibrary.resolveAmount(ctx);
        if (amountDue != null) {
            data.put("amount_due", amountDue);
        }
        data.put("overdue_days", ctx.getDpd());
        if (snapshot.getUserProfile() != null && snapshot.getUserProfile().getBasic() != null) {
            data.put("borrower_name", snapshot.getUserProfile().getBasic().getName());
        }
        if ("S4_EMAIL_PRE_CLOSE".equals(scriptSlot)) {
            data.put("assignment_date", formatAssignmentDate(ctx.getDueDate()));
        }
        return data;
    }

    /** 对外 final review 日 = dueDate + 91（内部 D+91 对齐）。 */
    static String formatAssignmentDate(LocalDate dueDate) {
        LocalDate base = dueDate != null ? dueDate : LocalDate.now();
        return base.plusDays(91).format(ASSIGNMENT_DATE_FMT);
    }

    private String resolveAddress(
            ChannelType channel, ContextSnapshot snapshot, Map<String, Object> metadata) {
        UserProfile profile = snapshot != null ? snapshot.getUserProfile() : null;
        UserProfile.BasicInfo basic = profile != null ? profile.getBasic() : null;
        UserProfile.DeviceInfo device = profile != null ? profile.getDevice() : null;

        String phone = basic != null ? basic.getPrimaryPhone() : null;
        String email = basic != null ? basic.getEmail() : null;
        String jpushToken = device != null ? device.getJpushToken() : null;

        switch (channel) {
            case EMAIL:
                // 无邮箱时 resolve() 已返回 null 跳过；此处直接回邮箱（理论上非空）
                return email;
            case PUSH:
                if (StringUtils.isNotBlank(jpushToken)) {
                    return jpushToken;
                }
                metadata.put("fallbackPhone", phone);
                return phone != null ? phone : "mock-address";
            case SMS:
            case AI_CALL:
                return phone != null ? phone : "mock-address";
            default:
                return phone != null ? phone : "mock-address";
        }
    }

    private static String resolveScriptSlot(ContactPlanStep step, ContextSnapshot snapshot) {
        ChannelType channel = step.getChannelType();
        CaseContext ctx = snapshot != null ? snapshot.getCaseContext() : null;

        if (channel == ChannelType.SMS || channel == ChannelType.PUSH) {
            return deriveMsgScriptSlot(channel, ctx);
        }
        if (channel == ChannelType.EMAIL && ctx != null) {
            if (StringUtils.isNotBlank(ctx.getEmailScriptSlot())) {
                return ctx.getEmailScriptSlot();
            }
            String byDpd = EmailMilestoneScriptSlots.resolveByDpd(ctx.getDpd());
            if (byDpd != null) {
                return byDpd;
            }
        }
        return "MOCK_" + step.getTemplateId();
    }

    /**
     * SMS/Push scriptSlot 推导：Stage + 渠道 + strategyTone(+dpd)。 S0 按 dpd 细分提醒槽；S2+ 且 SMS+FIRM 选
     * {@code *_FIRM}（Push 仅 STANDARD）。
     */
    static String deriveMsgScriptSlot(ChannelType channel, CaseContext ctx) {
        String chTag = channel == ChannelType.SMS ? "SMS" : "PUSH";
        if (ctx == null || ctx.getStage() == null) {
            return "S1_" + chTag + "_STANDARD";
        }
        boolean smsFirm =
                channel == ChannelType.SMS && "FIRM".equalsIgnoreCase(ctx.getStrategyTone());
        switch (ctx.getStage()) {
            case S0:
                int dpd = ctx.getDpd();
                if (dpd <= -2) {
                    return "S0_REMINDER";
                }
                if (dpd == -1) {
                    return "S0_REMINDER_URGENT";
                }
                return "S0_DUE_TODAY";
            case S1:
                return "S1_" + chTag + "_STANDARD";
            case S2:
                return smsFirm ? "S2_SMS_FIRM" : "S2_" + chTag + "_STANDARD";
            case S3:
                return smsFirm ? "S3_SMS_FIRM" : "S3_" + chTag + "_STANDARD";
            case S4:
                return smsFirm ? "S4_SMS_FIRM" : "S4_" + chTag + "_STANDARD";
            default:
                return "S1_" + chTag + "_STANDARD";
        }
    }
}
