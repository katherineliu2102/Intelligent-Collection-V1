package com.collection.channel.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 渠道模块 Nacos / 本地配置绑定（prefix = channel）。
 *
 * <p>密钥类（API Key、service account JSON）变更后建议重启；非密钥项可通过 {@link RefreshScope} 热更新。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "channel")
public class ChannelProperties {

    private Debug debug = new Debug();
    private Callback callback = new Callback();
    private Lth lth = new Lth();
    private SendGrid sendgrid = new SendGrid();
    private Notification notification = new Notification();
    private Scripts scripts = new Scripts();
    private Compliance compliance = new Compliance();
    private Map<String, PlanTemplate> planTemplates = new HashMap<>();
    private L4a l4a = new L4a();

    /** 渠道 dispatch 幂等 TTL（小时），默认 24h。 */
    private int idempotencyTtlHours = 24;

    /**
     * Adapter 未配密钥（*_NOT_CONFIGURED）时是否回退 MockChannelGateway 并标记成功。 true（默认）：纯 Mock 链路验收用，避免被密钥阻断。
     * false：联调/生产用 —— 未配密钥直接返回失败，不伪装成功（避免误报 DELIVERED）。
     */
    private boolean fallbackToMock = true;

    @Data
    public static class Debug {
        /** 空=正常；SMS|PUSH|EMAIL|AI_CALL|TTS 时仅生成单步 plan。 */
        private String singleStep = "";
        /** true 时走 SMS→PUSH→EMAIL 三步步（L4a-1 / TC-REG-01）。 */
        private boolean legacyThreeStep = false;
    }

    /** L4a 官方用例专用 caseId（与 *CaseRegistry / L4aCaseRegistry 对齐）。 */
    @Data
    public static class L4a {
        private long threeChannelCaseId = 94999L;
        private long observationCaseId = 94102L;
        private int observationMinutes = 1;
        private long guardNoPhoneCaseId = 94801L;
        private long rebuildFailCaseId = 94804L;
        private long guardFrequencyCaseId = 94805L;
        /** 仅对 {@link #guardFrequencyCaseId} 生效的 SMS 日上限（L4a-全 FREQUENCY 用例）。 */
        private int guardFrequencyDailyLimit = 1;
    }

    @Data
    public static class Callback {
        /** Webhook 根 URL，如 https://domain/webhook；Resolver 拼完整 callbackUrl。 */
        private String baseUrl = "";
    }

    @Data
    public static class Lth {
        private Voice voice = new Voice();

        @Data
        public static class Voice {
            private String url = "";
        }
    }

    /**
     * MOCASA 通知中心（common-notification）对接配置。
     *
     * <p>SMS：{@code POST {baseUrl}/v1/sms/send}；App Push：{@code POST
     * {baseUrl}/v1/app_notification/send}。 鉴权 {@code sign = MD5(appCode + appKey + dateTime)}，见
     * Notification 对接说明 §1/§2。
     */
    @Data
    public static class Notification {
        /** 通知中心服务根地址，如 https://notification.mocasa.internal。 */
        private String baseUrl = "";
        /** 调用方应用编码；催收固定 mocasa。 */
        private String appCode = "mocasa";
        /** 通知中心签发的密钥（待运维下发）。生产 /v1/sms/send 必需；测试 /testSend 免签名可空。 */
        private String appKey = "";
        /** SMS 固定内容类型，对应后台路由 contentType。 */
        private String smsContentType = "collection";
        /** true → SMS 走免签名测试端点 /v1/sms/testSend（联调用，appKey 可空）。 */
        private boolean smsTestMode = false;
        /** 测试端点可选指定的通道账号名（accountName），空=默认测试路由。 */
        private String smsTestAccountName = "";
        /**
         * true → App Push 走同步端点 /v1/app_notification/sync/send（联调，返回 requestSuccess/requestId，
         * 可见极光真实受理结果）；false → 异步 /v1/app_notification/send（生产，入队 code=0 即受理）。 注意：Push
         * 无免签名测试端点，无论同步/异步都需 appKey 签名。
         */
        private boolean pushSyncMode = false;
        /**
         * 测试 app 隔离开关：非空时所有 Push 强制打到该 jpushToken，跳过按用户取 token 与无 token→SMS fallback。 用于联调时把全部
         * stage 的 push 都投到一个测试 app，绝不触达真实用户。生产留空。
         */
        private String pushTestToken = "";
    }

    /** SMS/Push 文案库（按 scriptSlot 存放，{@code DefaultStepResolver} 注入变量）。 见 [渠道模板清单 §4.1/§5.1]。 */
    @Data
    public static class Scripts {
        private Map<String, String> sms = new HashMap<>();
        private Map<String, PushScript> push = new HashMap<>();
        /** repaymentUrl 缺失时的兜底深链（到 App 还款页，待 App 确认）。 */
        private String pushDefaultDeepLink = "";
        /** SMS 还款短链兜底（caseContext.repaymentUrl 缺失时使用）。 */
        private String smsDefaultRepaymentLink = "";
    }

    @Data
    public static class PushScript {
        private String title = "";
        private String body = "";
    }

    @Data
    public static class SendGrid {
        private String apiKey = "";
        private String fromEmail = "";
        private String fromName = "MOCASA Collections";
        private int unsubscribeGroupId = 0;
        /** scriptSlot → SendGrid Dynamic Template ID（d-xxx）；见 Email 模板清单文档。未命中则发信失败，无兜底。 */
        private Map<String, String> templates = new HashMap<>();
        /** 默认 https://api.sendgrid.com/v3/mail/send；单测可指向 WireMock。 */
        private String apiUrl = "https://api.sendgrid.com/v3/mail/send";
    }

    @Data
    public static class Compliance {
        private Map<String, Integer> dailyLimit = new HashMap<>();
        private String timezone = "Asia/Manila";
        private String quietHoursStart = "21:00";
        private String quietHoursEnd = "08:00";
        private String touchWindowStart = "08:00";
        private String touchWindowEnd = "21:00";
    }

    @Data
    public static class PlanTemplate {
        private List<PlanStepDef> steps = new ArrayList<>();
    }

    @Data
    public static class PlanStepDef {
        private String channel;
        private int delayMin = 0;
        private int observeMin = 0;
        private long templateId = 0;
    }

    /** 完整 Voice 回调 URL：baseUrl + /lth/voice */
    public String voiceCallbackUrl() {
        String base = callback.getBaseUrl();
        if (base == null || base.isEmpty()) {
            return "";
        }
        return base.endsWith("/") ? base + "lth/voice" : base + "/lth/voice";
    }

    public boolean isSendGridConfigured() {
        SendGrid sg = sendgrid;
        return sg != null
                && sg.getApiKey() != null
                && !sg.getApiKey().isEmpty()
                && sg.getFromEmail() != null
                && !sg.getFromEmail().isEmpty();
    }

    public boolean isNotificationConfigured() {
        Notification n = notification;
        return n != null
                && n.getBaseUrl() != null
                && !n.getBaseUrl().isEmpty()
                && n.getAppCode() != null
                && !n.getAppCode().isEmpty()
                && n.getAppKey() != null
                && !n.getAppKey().isEmpty();
    }

    /** 测试端点（/v1/sms/testSend）免签名，仅需 base-url + app-code。 */
    public boolean isNotificationTestConfigured() {
        Notification n = notification;
        return n != null
                && n.getBaseUrl() != null
                && !n.getBaseUrl().isEmpty()
                && n.getAppCode() != null
                && !n.getAppCode().isEmpty();
    }
}
