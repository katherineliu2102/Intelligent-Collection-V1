package com.collection.admin.web;

import com.collection.channel.adapter.NotificationPushAdapter;
import com.collection.channel.adapter.NotificationSmsAdapter;
import com.collection.channel.adapter.SendGridEmailAdapter;
import com.collection.channel.config.ChannelProperties;
import com.collection.channel.strategy.PushContent;
import com.collection.channel.strategy.ScriptLibrary;
import com.collection.channel.strategy.ScriptVars;
import com.collection.common.model.CaseContext;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.Stage;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import com.collection.common.service.CaseService;
import com.collection.common.spi.StepResolver;
import com.collection.ingestion.IngestionService;
import com.collection.service.impl.MockCaseService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 链路自测触发入口（Phase 1）。通过发布领域事件驱动核心引擎全链路。
 *
 * <p>这些 mock 端点仅用于无上游 PubSub 时验证链路；生产由 collection-ingestion 真实消费替代。
 */
@RestController
@RequestMapping("/mock")
public class MockTriggerController {

    @Resource
    private IngestionService ingestionService;
    @Resource
    private CaseService caseService;
    @Resource
    private StepResolver stepResolver;
    @Resource
    private SendGridEmailAdapter sendGridEmailAdapter;

    @Resource
    private NotificationSmsAdapter notificationSmsAdapter;

    @Resource
    private NotificationPushAdapter notificationPushAdapter;

    @Resource
    private ScriptLibrary scriptLibrary;

    @Resource
    private ChannelProperties channelProperties;

    /**
     * 直连 SendGrid 发一封 Email（不经 plan/DB）。
     * 用于 DB 不可用时的渠道冒烟；caseId 见 docs/email-templates/email-e2e-test-cases.md。
     */
    @PostMapping("/send-email")
    public Map<String, Object> sendEmail(@RequestParam Long caseId) {
        ContextSnapshot snapshot = caseService.getContextSnapshot(caseId);
        if (snapshot.getUserProfile() == null || snapshot.getUserProfile().getBasic() == null
                || snapshot.getUserProfile().getBasic().getEmail() == null) {
            return fail("NO_EMAIL", "caseId=" + caseId + " has no email in mock profile");
        }

        ContactPlan plan = new ContactPlan();
        plan.setId(0L);
        plan.setCaseId(caseId);
        plan.setUserId(caseId);
        if (snapshot.getCaseContext() != null) {
            plan.setStage(snapshot.getCaseContext().getStage());
        }

        ContactPlanStep step = new ContactPlanStep();
        step.setChannelType(ChannelType.EMAIL);
        step.setTemplateId(201L);
        step.setStepOrder(1);
        step.setRetryCount(0);

        ExecutionContext execCtx = ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(snapshot)
                .recentTimeline(Collections.emptyList())
                .build();

        StepCommand command = stepResolver.resolve(execCtx);
        StepResult result = sendGridEmailAdapter.send(command);

        Map<String, Object> m = new HashMap<>();
        m.put("ok", result.isSuccess());
        m.put("caseId", caseId);
        m.put("email", snapshot.getUserProfile().getBasic().getEmail());
        m.put("scriptSlot", command.getMetadata().get(StepCommand.META_SCRIPT_SLOT));
        m.put("result", result.isSuccess() ? "DELIVERED" : result.getErrorCode());
        m.put("providerMsgId", result.getProviderMsgId());
        if (!result.isSuccess()) {
            m.put("message", result.getErrorCode());
        }
        return m;
    }

    /**
     * 直连通知中心发一条 SMS（不经 plan/DB）。
     * caseId 见 MockSmsTestCases：94100=123456(testSend)、94101/94102=真号。
     */
    @PostMapping("/send-sms")
    public Map<String, Object> sendSms(@RequestParam Long caseId) {
        ContextSnapshot snapshot = caseService.getContextSnapshot(caseId);
        if (snapshot.getUserProfile() == null || snapshot.getUserProfile().getBasic() == null
                || snapshot.getUserProfile().getBasic().getPrimaryPhone() == null) {
            return fail("NO_PHONE", "caseId=" + caseId + " has no phone in mock profile");
        }

        ContactPlan plan = new ContactPlan();
        plan.setId(0L);
        plan.setCaseId(caseId);
        plan.setUserId(caseId);
        if (snapshot.getCaseContext() != null) {
            plan.setStage(snapshot.getCaseContext().getStage());
        }

        ContactPlanStep step = new ContactPlanStep();
        step.setChannelType(ChannelType.SMS);
        step.setTemplateId(101L);
        step.setStepOrder(1);
        step.setRetryCount(0);

        ExecutionContext execCtx = ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(snapshot)
                .recentTimeline(Collections.emptyList())
                .build();

        StepCommand command = stepResolver.resolve(execCtx);
        StepResult result = notificationSmsAdapter.send(command);

        Map<String, Object> m = new HashMap<>();
        m.put("ok", result.isSuccess());
        m.put("caseId", caseId);
        m.put("phone", snapshot.getUserProfile().getBasic().getPrimaryPhone());
        m.put("scriptSlot", command.getMetadata().get(StepCommand.META_SCRIPT_SLOT));
        m.put("smsBody", command.getMetadata().get(StepCommand.META_SMS_BODY));
        m.put("result", result.isSuccess() ? "DELIVERED" : result.getErrorCode());
        m.put("providerMsgId", result.getProviderMsgId());
        if (!result.isSuccess()) {
            m.put("message", result.getErrorCode());
        }
        return m;
    }

    /**
     * 直连通知中心发一条 App Push（不经 plan/DB）。
     * caseId 见 MockPushTestCases：94200=假 jpushToken 占位（走 push）、94201=无 token（验 SMS fallback）。
     * 同步/异步由 channel.notification.push-sync-mode 决定。
     */
    @PostMapping("/send-push")
    public Map<String, Object> sendPush(@RequestParam Long caseId) {
        ContextSnapshot snapshot = caseService.getContextSnapshot(caseId);

        ContactPlan plan = new ContactPlan();
        plan.setId(0L);
        plan.setCaseId(caseId);
        plan.setUserId(caseId);
        if (snapshot.getCaseContext() != null) {
            plan.setStage(snapshot.getCaseContext().getStage());
        }

        ContactPlanStep step = new ContactPlanStep();
        step.setChannelType(ChannelType.PUSH);
        step.setTemplateId(301L);
        step.setStepOrder(1);
        step.setRetryCount(0);

        ExecutionContext execCtx = ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(snapshot)
                .recentTimeline(Collections.emptyList())
                .build();

        StepCommand command = stepResolver.resolve(execCtx);
        StepResult result = notificationPushAdapter.send(command);

        boolean hasToken = snapshot.getUserProfile() != null && snapshot.getUserProfile().getDevice() != null
                && snapshot.getUserProfile().getDevice().getJpushToken() != null;

        Map<String, Object> m = new HashMap<>();
        m.put("ok", result.isSuccess());
        m.put("caseId", caseId);
        m.put("jpushToken", hasToken ? snapshot.getUserProfile().getDevice().getJpushToken() : null);
        m.put("targetAddress", command.getTargetAddress());
        m.put("scriptSlot", command.getMetadata().get(StepCommand.META_SCRIPT_SLOT));
        m.put("title", command.getMetadata().get(StepCommand.META_TITLE));
        m.put("body", command.getMetadata().get(StepCommand.META_BODY));
        m.put("pushData", command.getMetadata().get(StepCommand.META_PUSH_DATA));
        m.put("result", result.isSuccess() ? "DELIVERED" : result.getErrorCode());
        m.put("providerMsgId", result.getProviderMsgId());
        if (!result.isSuccess()) {
            m.put("message", result.getErrorCode());
        }
        return m;
    }

    /**
     * 按 {@code channel.scripts.sms} 已配置槽位逐条下发（联调验收全阶段文案）。
     * 默认 caseId=94102（9451373897）；每条间隔约 1s，避免瞬时打满供应商。
     */
    @PostMapping("/send-all-sms")
    public Map<String, Object> sendAllSms(@RequestParam(defaultValue = "94102") Long caseId) {
        ContextSnapshot snapshot = caseService.getContextSnapshot(caseId);
        if (snapshot.getUserProfile() == null || snapshot.getUserProfile().getBasic() == null
                || snapshot.getUserProfile().getBasic().getPrimaryPhone() == null) {
            return fail("NO_PHONE", "caseId=" + caseId + " has no phone in mock profile");
        }
        String phone = snapshot.getUserProfile().getBasic().getPrimaryPhone();
        ScriptVars vars = buildScriptVars(snapshot);
        List<Map<String, Object>> items = new ArrayList<>();
        int ok = 0;
        for (String scriptSlot : channelProperties.getScripts().getSms().keySet()) {
            String body = scriptLibrary.renderSms(scriptSlot, vars);
            if (StringUtils.isBlank(body)) {
                items.add(slotSkip(scriptSlot, "SMS_TEMPLATE_EMPTY"));
                continue;
            }
            StepCommand command = StepCommand.builder()
                    .channelType(ChannelType.SMS)
                    .targetAddress(phone)
                    .templateId("bulk-sms")
                    .idempotencyKey("bulk:sms:" + caseId + ":" + scriptSlot)
                    .metadata(buildSmsMetadata(caseId, scriptSlot, body))
                    .build();
            StepResult result = notificationSmsAdapter.send(command);
            items.add(slotResult(scriptSlot, body, result));
            if (result.isSuccess()) {
                ok++;
            }
            sleepBriefly();
        }
        Map<String, Object> m = new HashMap<>();
        m.put("ok", ok == items.size() && !items.isEmpty());
        m.put("caseId", caseId);
        m.put("phone", phone);
        m.put("total", items.size());
        m.put("success", ok);
        m.put("items", items);
        return m;
    }

    /**
     * 按 {@code channel.scripts.push} 已配置槽位逐条下发（联调验收全阶段 Push 文案）。
     * 默认 caseId=94200（真实 jpushToken）；无 token 则失败。
     */
    @PostMapping("/send-all-push")
    public Map<String, Object> sendAllPush(@RequestParam(defaultValue = "94200") Long caseId) {
        ContextSnapshot snapshot = caseService.getContextSnapshot(caseId);
        UserProfile.DeviceInfo device = snapshot.getUserProfile() != null
                ? snapshot.getUserProfile().getDevice() : null;
        String token = device != null ? device.getJpushToken() : null;
        if (StringUtils.isBlank(token)) {
            return fail("NO_JPUSH_TOKEN", "caseId=" + caseId + " has no jpushToken");
        }
        ScriptVars vars = buildScriptVars(snapshot);
        List<Map<String, Object>> items = new ArrayList<>();
        int ok = 0;
        for (String scriptSlot : channelProperties.getScripts().getPush().keySet()) {
            PushContent push = scriptLibrary.renderPush(scriptSlot, vars);
            if (push == null || (StringUtils.isBlank(push.getTitle()) && StringUtils.isBlank(push.getBody()))) {
                items.add(slotSkip(scriptSlot, "PUSH_TEMPLATE_EMPTY"));
                continue;
            }
            Map<String, Object> meta = buildPushMetadata(caseId, snapshot, scriptSlot, push);
            StepCommand command = StepCommand.builder()
                    .channelType(ChannelType.PUSH)
                    .targetAddress(token)
                    .templateId("bulk-push")
                    .idempotencyKey("bulk:push:" + caseId + ":" + scriptSlot)
                    .metadata(meta)
                    .build();
            StepResult result = notificationPushAdapter.send(command);
            items.add(slotResult(scriptSlot, push.getTitle() + " | " + push.getBody(), result));
            if (result.isSuccess()) {
                ok++;
            }
            sleepBriefly();
        }
        Map<String, Object> m = new HashMap<>();
        m.put("ok", ok == items.size() && !items.isEmpty());
        m.put("caseId", caseId);
        m.put("jpushToken", token);
        m.put("total", items.size());
        m.put("success", ok);
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> buildSmsMetadata(Long caseId, String scriptSlot, String body) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_SCRIPT_SLOT, scriptSlot);
        meta.put(StepCommand.META_SMS_BODY, body);
        if (caseId != null) {
            meta.put(StepCommand.META_CASE_ID, caseId);
        }
        return meta;
    }

    private Map<String, Object> buildPushMetadata(Long caseId, ContextSnapshot snapshot,
                                                  String scriptSlot, PushContent push) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(StepCommand.META_SCRIPT_SLOT, scriptSlot);
        meta.put(StepCommand.META_TITLE, push.getTitle());
        meta.put(StepCommand.META_BODY, push.getBody());
        if (caseId != null) {
            meta.put(StepCommand.META_CASE_ID, caseId);
        }
        String deepLink = scriptLibrary.defaultDeepLink();
        CaseContext ctx = snapshot != null ? snapshot.getCaseContext() : null;
        if (ctx != null && StringUtils.isNotBlank(ctx.getRepaymentUrl())) {
            deepLink = ctx.getRepaymentUrl();
        }
        Map<String, String> data = new HashMap<>();
        data.put("scene", "collection");
        data.put("script_slot", scriptSlot);
        if (caseId != null) {
            data.put("case_id", String.valueOf(caseId));
        }
        if (StringUtils.isNotBlank(deepLink)) {
            data.put("deep_link", deepLink);
        }
        meta.put(StepCommand.META_PUSH_DATA, com.alibaba.fastjson.JSON.toJSONString(data));
        return meta;
    }

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
        BigDecimal v = amount != null ? amount : BigDecimal.ZERO;
        String amountStr = String.format(Locale.US, "%,.2f", v);
        return new ScriptVars(name, amountStr, dpd);
    }

    private static Map<String, Object> slotResult(String scriptSlot, String preview, StepResult result) {
        Map<String, Object> row = new HashMap<>();
        row.put("scriptSlot", scriptSlot);
        row.put("preview", preview);
        row.put("ok", result.isSuccess());
        row.put("result", result.isSuccess() ? "DELIVERED" : result.getErrorCode());
        row.put("providerMsgId", result.getProviderMsgId());
        return row;
    }

    private static Map<String, Object> slotSkip(String scriptSlot, String reason) {
        Map<String, Object> row = new HashMap<>();
        row.put("scriptSlot", scriptSlot);
        row.put("ok", false);
        row.put("result", reason);
        return row;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 注入新案件，触发建计划 → 步骤执行全链路。 */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam Long caseId,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) Stage stage,
                                      @RequestParam(required = false) Boolean legacyThreeStep) {
        boolean prevLegacy = channelProperties.getDebug().isLegacyThreeStep();
        if (legacyThreeStep != null) {
            channelProperties.getDebug().setLegacyThreeStep(legacyThreeStep);
        }
        try {
            ingestionService.ingestCase(caseId, userId, stage);
        } finally {
            if (legacyThreeStep != null) {
                channelProperties.getDebug().setLegacyThreeStep(prevLegacy);
            }
        }
        return ok("CASE_INGESTED published, caseId=" + caseId);
    }

    /** 模拟还款到账：标记 mock 案件已还款 + 发布 REPAYMENT_RECEIVED（应取消该用户活跃计划）。 */
    @PostMapping("/repayment")
    public Map<String, Object> repayment(@RequestParam Long userId,
                                         @RequestParam(required = false) Long caseId) {
        if (caseId != null && caseService instanceof MockCaseService) {
            ((MockCaseService) caseService).markRepaid(caseId);
        }
        ingestionService.repayment(userId);
        return ok("REPAYMENT_RECEIVED published, userId=" + userId);
    }

    /** 模拟阶段变更：取消旧阶段计划 + 创建新阶段计划。 */
    @PostMapping("/stage-changed")
    public Map<String, Object> stageChanged(@RequestParam Long caseId, @RequestParam Stage stage) {
        ingestionService.changeStage(caseId, stage);
        return ok("STAGE_CHANGED published, caseId=" + caseId + " stage=" + stage);
    }

    /** 模拟 PTP 到期。Phase 2 预留：Phase 1 引擎不消费 PTP_EXPIRED，此端点仅发布事件、无消费方（核心引擎规格 §2.6）。 */
    @PostMapping("/ptp-expired")
    public Map<String, Object> ptpExpired(@RequestParam Long caseId, @RequestParam Long ptpId) {
        ingestionService.ptpExpired(caseId, ptpId);
        return ok("PTP_EXPIRED published (Phase 2 预留, no engine consumer in Phase 1), caseId=" + caseId);
    }

    /**
     * 模拟 D+91 完全停催：标记 mock 案件 CEASED + 发布 CASE_CEASED（取消活跃计划）。
     * 用于 TC-CEASED-01；生产由 ingestion 日切 Job 发布。
     */
    @PostMapping("/case-ceased")
    public Map<String, Object> caseCeased(@RequestParam Long caseId,
                                          @RequestParam(required = false, defaultValue = "91") Integer maxDpd) {
        if (caseService instanceof MockCaseService) {
            ((MockCaseService) caseService).markCeased(caseId);
        }
        ingestionService.caseCeased(caseId, maxDpd);
        return ok("CASE_CEASED published, caseId=" + caseId + " maxDpd=" + maxDpd);
    }

    private Map<String, Object> ok(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }

    private Map<String, Object> fail(String code, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", false);
        m.put("result", code);
        m.put("message", msg);
        return m;
    }
}
