package com.collection.channel.strategy;

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
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 1 Mock 实现 —— DefaultStepResolver 的占位。
 *
 * <p>Email：按 {@link CaseContext#getEmailScriptSlot()} 或 dpd 解析里程碑 scriptSlot。
 */
@Component
public class MockStepResolver implements StepResolver {

    private static final DateTimeFormatter ASSIGNMENT_DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    @Resource
    private ChannelProperties channelProperties;

    @Override
    public StepCommand resolve(ExecutionContext context) {
        ContactPlanStep step = context.getCurrentStep();
        if (step.getChannelType() == ChannelType.HUMAN_CALL) {
            throw new IllegalStateException("Phase 1 禁止 plan 内 HUMAN_CALL（对齐待办 E4）");
        }

        ContextSnapshot snapshot = context.getContextSnapshot();
        String scriptSlot = resolveScriptSlot(step, snapshot);
        Map<String, Object> metadata = new HashMap<>();

        if (context.getPlan().getStage() != null) {
            metadata.put(StepCommand.META_STAGE, context.getPlan().getStage().name());
        }
        metadata.put(StepCommand.META_LANGUAGE, "tl");
        metadata.put(StepCommand.META_SCRIPT_SLOT, scriptSlot);

        Long caseId = context.getPlan().getCaseId();
        if (caseId != null) {
            metadata.put(StepCommand.META_CASE_ID, caseId);
        }

        fillChannelMetadata(step.getChannelType(), metadata, snapshot, scriptSlot);

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
                .templateId(step.getTemplateId() == null ? "default" : String.valueOf(step.getTemplateId()))
                .idempotencyKey(context.getPlan().getId() + ":" + step.getStepOrder() + ":" + step.getRetryCount())
                .metadata(metadata)
                .build();
    }

    private void fillChannelMetadata(ChannelType channel, Map<String, Object> metadata,
                                     ContextSnapshot snapshot, String scriptSlot) {
        CaseContext caseCtx = snapshot != null ? snapshot.getCaseContext() : null;
        String repaymentUrl = caseCtx != null ? caseCtx.getRepaymentUrl() : null;

        if (channel == ChannelType.SMS) {
            metadata.put(StepCommand.META_SMS_BODY,
                    "[MOCK] " + scriptSlot + (repaymentUrl != null ? " " + repaymentUrl : ""));
        } else if (channel == ChannelType.EMAIL) {
            metadata.put(StepCommand.META_DYNAMIC_TEMPLATE_DATA, buildEmailTemplateData(snapshot, scriptSlot));
        } else if (channel == ChannelType.PUSH) {
            metadata.put(StepCommand.META_DYNAMIC_TEMPLATE_DATA, buildEmailTemplateData(snapshot, scriptSlot));
        }
    }

    private Map<String, Object> buildEmailTemplateData(ContextSnapshot snapshot, String scriptSlot) {
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

    private String resolveAddress(ChannelType channel, ContextSnapshot snapshot, Map<String, Object> metadata) {
        UserProfile profile = snapshot != null ? snapshot.getUserProfile() : null;
        UserProfile.BasicInfo basic = profile != null ? profile.getBasic() : null;
        UserProfile.DeviceInfo device = profile != null ? profile.getDevice() : null;

        String phone = basic != null ? basic.getPrimaryPhone() : null;
        String email = basic != null ? basic.getEmail() : null;
        String fcmToken = device != null ? device.getFcmToken() : null;

        switch (channel) {
            case EMAIL:
                return StringUtils.isNotBlank(email) ? email : "no-email@invalid.local";
            case PUSH:
                if (StringUtils.isNotBlank(fcmToken)) {
                    return fcmToken;
                }
                metadata.put("fallbackPhone", phone);
                return phone != null ? phone : "mock-address";
            case SMS:
            case AI_CALL:
            case TTS:
                return phone != null ? phone : "mock-address";
            default:
                return phone != null ? phone : "mock-address";
        }
    }

    private static String resolveScriptSlot(ContactPlanStep step, ContextSnapshot snapshot) {
        if (step.getChannelType() != ChannelType.EMAIL || snapshot == null || snapshot.getCaseContext() == null) {
            return "MOCK_" + step.getTemplateId();
        }
        CaseContext ctx = snapshot.getCaseContext();
        if (StringUtils.isNotBlank(ctx.getEmailScriptSlot())) {
            return ctx.getEmailScriptSlot();
        }
        String byDpd = EmailMilestoneScriptSlots.resolveByDpd(ctx.getDpd());
        return byDpd != null ? byDpd : "MOCK_" + step.getTemplateId();
    }
}
