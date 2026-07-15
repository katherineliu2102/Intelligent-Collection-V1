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
<<<<<<< HEAD
 * {name}/{amount}/{dpd}/{repaymentUrl}}； 未配置该槽时回退占位串。Push {@code data.deep_link} 取 repaymentUrl，缺失用
 * {@code push-default-deep-link} 兜底。
=======
 * {name}/{amount}/{dpd}}； 未配置该槽时回退占位串。Push {@code data.deep_link} 取 repaymentUrl，缺失用 {@code
 * push-default-deep-link} 兜底。
>>>>>>> origin/ca_branch
 */
@Component
public class DefaultStepResolver implements StepResolver {

    private static final DateTimeFormatter ASSIGNMENT_DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    @Resource private ChannelProperties channelProperties;

    @Resource private ScriptLibrary scriptLibrary;

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
<<<<<<< HEAD
                    throw new RuntimeException(
                            "L4a REBUILD test: invalid slot forces step failure → ExhaustionPolicy");
=======
                    throw new RuntimeException("L4a REBUILD test: invalid slot forces step failure → ExhaustionPolicy");
>>>>>>> origin/ca_branch
                }
                return null;
            }
            if (StringUtils.isBlank(extractEmail(snapshot))) {
                return null;
            }
        }

<<<<<<< HEAD
        ScriptVars vars = scriptLibrary.buildVars(snapshot);
=======
        ScriptVars vars = buildScriptVars(snapshot);
>>>>>>> origin/ca_branch
        Map<String, Object> metadata = new HashMap<>();

        if (context.getPlan().getStage() != null) {
            metadata.put(StepCommand.META_STAGE, context.getPlan().getStage().name());
        }
        metadata.put(StepCommand.META_LANGUAGE, resolveLanguage(snapshot));
        metadata.put(StepCommand.META_SCRIPT_SLOT, scriptSlot);

        Long caseId = context.getPlan().getCaseId();
        if (caseId != null) {
            metadata.put(StepCommand.META_CASE_ID, caseId);
        }

        fillChannelMetadata(step.getChannelType(), metadata, snapshot, scriptSlot, vars);

        if (step.getChannelType().isAsyncChannel()) {
            String callbackUrl = channelProperties.voiceCallbackUrl();
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
                .metadata(metadata)
                .build();
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

<<<<<<< HEAD
=======
    private static ScriptVars buildScriptVars(ContextSnapshot snapshot) {
        String name = null;
        BigDecimal amount = null;
        int dpd = 0;
        if (snapshot != null) {
            if (snapshot.getUserProfile() != null && snapshot.getUserProfile().getBasic() != null) {
                name = snapshot.getUserProfile().getBasic().getName();
            }
            if (snapshot.getCaseContext() != null) {
                amount = snapshot.getCaseContext().getTotalOutstanding();
                dpd = snapshot.getCaseContext().getDpd();
            }
        }
        return new ScriptVars(name, formatAmount(amount), dpd);
    }

    private static String formatAmount(BigDecimal amount) {
        BigDecimal v = amount != null ? amount : BigDecimal.ZERO;
        return String.format(Locale.US, "%,.2f", v);
    }

>>>>>>> origin/ca_branch
    private void fillChannelMetadata(
            ChannelType channel,
            Map<String, Object> metadata,
            ContextSnapshot snapshot,
            String scriptSlot,
            ScriptVars vars) {
        CaseContext caseCtx = snapshot != null ? snapshot.getCaseContext() : null;
        String repaymentUrl = caseCtx != null ? caseCtx.getRepaymentUrl() : null;

        if (channel == ChannelType.SMS) {
            String body = scriptLibrary.renderSms(scriptSlot, vars);
            metadata.put(
                    StepCommand.META_SMS_BODY,
                    body != null ? body : buildFallbackSmsBody(scriptSlot, repaymentUrl));
        } else if (channel == ChannelType.EMAIL) {
            metadata.put(
                    StepCommand.META_DYNAMIC_TEMPLATE_DATA,
                    buildEmailTemplateData(snapshot, scriptSlot));
        } else if (channel == ChannelType.PUSH) {
            PushContent push = scriptLibrary.renderPush(scriptSlot, vars);
            metadata.put(
                    StepCommand.META_TITLE,
                    push != null && push.getTitle() != null
                            ? push.getTitle()
                            : "MOCASA Payment Reminder");
            metadata.put(
                    StepCommand.META_BODY,
                    push != null && push.getBody() != null
                            ? push.getBody()
                            : "[MOCK] " + scriptSlot);
            metadata.put(
                    StepCommand.META_PUSH_DATA, buildPushDataJson(snapshot, scriptSlot, metadata));

            // Push 无 token → fallback SMS：复用同阶段 SMS 文案
            String smsSlot = deriveMsgScriptSlot(ChannelType.SMS, caseCtx);
            String fallbackBody = scriptLibrary.renderSms(smsSlot, vars);
            metadata.put(
                    StepCommand.META_FALLBACK_SMS_BODY,
                    fallbackBody != null
                            ? fallbackBody
                            : buildFallbackSmsBody(scriptSlot, repaymentUrl));
        }
    }

    private static String buildFallbackSmsBody(String scriptSlot, String repaymentUrl) {
        return "[MOCK] " + scriptSlot + (repaymentUrl != null ? " " + repaymentUrl : "");
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
        if (ctx.getTotalOutstanding() != null) {
            data.put("amount_due", ctx.getTotalOutstanding());
        } else {
            data.put("amount_due", BigDecimal.ZERO);
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
