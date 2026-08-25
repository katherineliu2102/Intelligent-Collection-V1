package com.collection.admin.config;

import com.collection.admin.auth.AdminAuthenticator;
import com.collection.admin.web.WebhookSecurityProperties;
import com.collection.channel.config.ChannelProperties;
import com.collection.channel.gateway.MockChannelGateway;
import com.collection.channel.strategy.MockExecutionGuard;
import com.collection.channel.strategy.MsgScriptSlots;
import com.collection.channel.strategy.ScriptLibrary;
import com.collection.common.channel.ChannelGateway;
import com.collection.common.service.CaseService;
import com.collection.common.spi.ExecutionGuard;
import com.collection.engine.fault.EngineFaultInjector;
import com.collection.ingestion.config.IngestionProperties;
import com.collection.service.impl.AiCollectionCaseService;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Pilot 启动闸门：任何真实依赖、白名单或密钥缺失均禁止进程就绪。 */
@Slf4j
@Component
@Profile("pilot")
public class PilotReadinessValidator {

    private final CaseService caseService;
    private final ChannelGateway channelGateway;
    private final ExecutionGuard executionGuard;
    private final IngestionProperties ingestionProperties;
    private final WebhookSecurityProperties webhookProperties;
    private final ChannelProperties channelProperties;
    private final ScriptLibrary scriptLibrary;
    private final SchedulerProperties schedulerProperties;
    private final ScanProperties scanProperties;
    private final AdminAuthenticator adminAuthenticator;

    /** 字段注入而非构造参数：现有单测直接 new 本类，加必填参数会波及无关用例。 */
    @Autowired(required = false)
    private EngineFaultInjector faultInjector;

    public PilotReadinessValidator(
            CaseService caseService,
            ChannelGateway channelGateway,
            ExecutionGuard executionGuard,
            IngestionProperties ingestionProperties,
            WebhookSecurityProperties webhookProperties,
            ChannelProperties channelProperties,
            ScriptLibrary scriptLibrary,
            SchedulerProperties schedulerProperties,
            ScanProperties scanProperties,
            AdminAuthenticator adminAuthenticator) {
        this.caseService = caseService;
        this.channelGateway = channelGateway;
        this.executionGuard = executionGuard;
        this.ingestionProperties = ingestionProperties;
        this.webhookProperties = webhookProperties;
        this.channelProperties = channelProperties;
        this.scriptLibrary = scriptLibrary;
        this.schedulerProperties = schedulerProperties;
        this.scanProperties = scanProperties;
        this.adminAuthenticator = adminAuthenticator;
    }

    @PostConstruct
    public void validate() {
        require(
                caseService instanceof AiCollectionCaseService,
                "Pilot requires collection.case-service=ai");
        require(
                ingestionProperties.getLoanIdWhitelist() != null
                        && !ingestionProperties.getLoanIdWhitelist().isEmpty(),
                "Pilot requires a non-empty collection.ingestion.loan-id-whitelist");
        require(
                !(channelGateway instanceof MockChannelGateway)
                        && !channelProperties.isFallbackToMock(),
                "Pilot requires a real ChannelGateway and channel.fallback-to-mock=false");
        require(
                !(executionGuard instanceof MockExecutionGuard),
                "Pilot requires a real ExecutionGuard");
        require(
                webhookProperties.isSignatureRequired()
                        && StringUtils.isNotBlank(webhookProperties.getHmacSecret()),
                "Pilot requires collection.webhook signature verification and HMAC secret");
        require(
                adminAuthenticator.hasUsableAccount(),
                "Pilot requires at least one collection.admin.auth.accounts entry with a password hash");
        requireChannelDispatchReady();
        requireAllScriptSlots();
        requireSmsTestModeIsContained();
        warnActiveTestSwitches();
    }

    /**
     * {@code sms-test-mode=true} 不构成投递隔离，必须另有名单约束才允许在 pilot 启动。
     *
     * <p>2026-08-24 实测：{@code /v1/sms/testSend} 返回的 {@code data.channel} 是 CreativeBlue / QHSms 等
     * <b>真实运营商通道</b>，通知中心不存在 Virtual 账号（显式指定报 {@code no valid account}）。 该开关只免签名，Adapter
     * 也从不替换手机号——payload 里是真实借款人号码，短信会真实送达。
     *
     * <p>此前它被当作"短信不发真实号码"的安全网，pilot 上一旦调度打开就是真实客户收到真实短信。 唯一有效的约束是扫描白名单，故在此把两者绑定：开着该开关就必须有非空白名单，
     * 否则拒绝启动而不是留一条容易被误读的告警。
     */
    private void requireSmsTestModeIsContained() {
        if (!channelProperties.getNotification().isSmsTestMode()) {
            return;
        }
        boolean contained =
                !scanProperties.isAllowFullScan()
                        && scanProperties.getCaseIdWhitelist() != null
                        && !scanProperties.getCaseIdWhitelist().isEmpty();
        require(
                contained,
                "channel.notification.sms-test-mode=true 不抑制投递（testSend 实际路由到 CreativeBlue/QHSms "
                        + "等真实运营商通道，且无 Virtual 账号），短信会真实送达 payload 中的号码。"
                        + "开启它必须同时用非空 collection.scan.case-id-whitelist 限定触达范围，"
                        + "且不得设 collection.scan.allow-full-scan=true。"
                        + "若本轮就是要对真实客户触达，请显式关闭 sms-test-mode 并配置 app-key 走 /v1/sms/send。");
    }

    /**
     * 启动时列出所有生效的触达测试开关。
     *
     * <p>这些开关让消息发往测试通道而非真实客户，演练期必需；但它们此前全是静默生效的。 转真实触达时若漏清，线上表现是"催收全程无效果"而非报错——和 2026-08-24
     * 那次"入案正常但零计划" 是同一类静默失败，只是方向相反。故启动时一律打出来，让它在日志首屏可见。
     */
    private void warnActiveTestSwitches() {
        List<String> active = new ArrayList<>();
        if (channelProperties.getNotification().isSmsTestMode()) {
            // 不要写成「短信不发真实号码」。2026-08-24 实测 /v1/sms/testSend 的返回 channel 是
            // CreativeBlue / QHSms 两个真实运营商通道，且通知中心无 Virtual 账号（指定即报
            // no valid account）。该开关只免签名，不改投递路由——手机号照样是 payload 里的真号。
            active.add(
                    "channel.notification.sms-test-mode=true（仅免签名走 /v1/sms/testSend；"
                            + "⚠ 路由仍是真实运营商，短信会真实送达 payload 中的号码）");
        }
        if (StringUtils.isNotBlank(channelProperties.getNotification().getPushTestToken())) {
            active.add("channel.notification.push-test-token 已配置（推送只发测试设备）");
        }
        if (StringUtils.isNotBlank(channelProperties.getNotification().getSmsTestRecipient())) {
            active.add(
                    "channel.notification.sms-test-recipient="
                            + channelProperties.getNotification().getSmsTestRecipient()
                            + "（短信全部改投该号码，不发借款人）");
        }
        if (StringUtils.isNotBlank(channelProperties.getSendgrid().getTestRecipient())) {
            active.add(
                    "channel.sendgrid.test-recipient="
                            + channelProperties.getSendgrid().getTestRecipient()
                            + "（邮件全部改投该地址，不发借款人）");
        }
        if (StringUtils.isNotBlank(channelProperties.getFacade().getTestCallee())) {
            active.add(
                    "channel.facade.test-callee="
                            + channelProperties.getFacade().getTestCallee()
                            + "（外呼不拨借款人）");
        }
        if (faultInjector != null && faultInjector.isEnabled()) {
            // 与上面几个开关方向相反：它不是"少发"，而是让事件人为失败、进 PEL 直至 DLQ。
            // T3o 取证期必需，但漏关进 T4 会把真实案件推进死信，且失败原因看起来完全正常。
            active.add(
                    "engine.fault-injection.enabled=true（可人为使事件失败并进 DLQ；"
                            + "T4 前必须置 false，并确认 /ops/fault-injection 返回 armed=false）");
        }
        if (active.isEmpty()) {
            log.info("[PilotReadiness] 无触达测试开关生效 —— 消息会真实发给客户");
            return;
        }
        log.warn("[PilotReadiness] 以下触达测试开关生效，转真实触达前必须逐项清空：");
        active.forEach(s -> log.warn("[PilotReadiness]   - {}", s));
    }

    /**
     * 外呼渠道就绪闸门 —— 仅在调度开启时生效。
     *
     * <p>pilot 下 {@link com.collection.admin.job.TriggerScanner} 不装配，步骤执行的唯一入口是调度订阅。
     * 关掉调度即没有任何步骤会执行，此时 Facade 配不配都影响不到客户，只验接入的演练不该被它挡住。
     *
     * <p>调度一开就是另一回事：计划模板每天铺 2 个 AI 外呼槽（编排规格 §7.11），占 S4 全部步骤四成以上。 Facade 未配时 Adapter 返回
     * NOT_CONFIGURED，这些步骤成批永久失败并触发 ExhaustionPolicy， 在监控上表现为触达率塌陷而非配置错误——所以必须拒启。
     */
    private void requireChannelDispatchReady() {
        if (!schedulerProperties.isEnabled()) {
            log.warn(
                    "[PilotReadiness] collection.scheduler.enabled=false：本实例只接入不触达，"
                            + "跳过 Facade 就绪校验。开启调度前必须补齐 channel.facade.base-url 与 api-key");
            return;
        }
        require(
                channelProperties.isFacadeConfigured(),
                "Pilot requires channel.facade.base-url and api-key (plan templates schedule AI_CALL slots)");
        // 回调验签用的是 Facade 账户级 secret，与我方 collection.webhook.hmac-secret 不是同一把。
        // 缺它时 /webhook/facade-callback 会把每一条真实回调判成验签失败并回 401，
        // 外呼照打但结果全数回不来，只能挂到 callbackTimeout —— 与"根本没配 Facade"同样致命，故拒启。
        require(
                StringUtils.isNotBlank(channelProperties.getFacade().getCallbackSecret()),
                "Pilot requires channel.facade.callback-secret to verify Facade webhooks");
        // AI_CALL 是异步渠道：没有回调地址，外呼结果回不来，步骤只能挂到 callbackTimeout 才收敛。
        // 公网入口尚未开通，故这里只告警不拒启——外呼照打，结果按超时收敛。
        if (StringUtils.isBlank(channelProperties.callbackUrl())) {
            log.warn(
                    "[PilotReadiness] channel.callback.base-url 未配置：AI_CALL 结果无法回传，"
                            + "外呼步骤会停在 STEP_EXECUTING 直到 callbackTimeout 收敛为失败。"
                            + "公网回调入口开通后必须补上，否则外呼成效数据不可用");
        }
    }

    /**
     * 文案完备性闸门。
     *
     * <p>Resolver 对缺槽 fail-close，漏配不会发出占位串，但会静默变成「客户没收到」—— 在 Pilot 里这等同于触达数据失真。故启动期把 {@link
     * MsgScriptSlots} 全集一次性校验，缺哪些槽直接列在异常里。
     */
    private void requireAllScriptSlots() {
        List<String> missing = new ArrayList<>();
        for (String slot : MsgScriptSlots.smsSlots()) {
            if (!scriptLibrary.hasSms(slot)) {
                missing.add("SMS:" + slot);
            }
        }
        for (String slot : MsgScriptSlots.pushSlots()) {
            if (!scriptLibrary.hasPush(slot)) {
                missing.add("PUSH:" + slot);
            }
        }
        require(
                missing.isEmpty(),
                "Pilot requires every SMS/Push script slot to be configured "
                        + "(t_script_template status=ACTIVE or channel.scripts); missing="
                        + missing);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
