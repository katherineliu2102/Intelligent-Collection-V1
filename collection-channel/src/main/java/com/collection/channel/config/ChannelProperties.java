package com.collection.channel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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

    /** 渠道 dispatch 幂等 TTL（小时），默认 24h。 */
    private int idempotencyTtlHours = 24;

    @Data
    public static class Debug {
        /** 空=正常；SMS|PUSH|EMAIL|AI_CALL|TTS 时仅生成单步 plan。 */
        private String singleStep = "";
        /** true 时走 SMS→PUSH→SMS 三步步（TC-REG-01 回归）。 */
        private boolean legacyThreeStep = false;
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
     * <p>SMS：{@code POST {baseUrl}/v1/sms/send}；App Push：{@code POST {baseUrl}/v1/app_notification/send}。
     * 鉴权 {@code sign = MD5(appCode + appKey + dateTime)}，见 Notification 对接说明 §1/§2。
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
         * 可见极光真实受理结果）；false → 异步 /v1/app_notification/send（生产，入队 code=0 即受理）。
         * 注意：Push 无免签名测试端点，无论同步/异步都需 appKey 签名。
         */
        private boolean pushSyncMode = false;
    }

    /**
     * SMS/Push 文案库（按 scriptSlot 存放，{@code DefaultStepResolver} 注入变量）。
     * 见 [渠道模板清单 §4.1/§5.1]。
     */
    @Data
    public static class Scripts {
        private Map<String, String> sms = new HashMap<>();
        private Map<String, PushScript> push = new HashMap<>();
        /** repaymentUrl 缺失时的兜底深链（到 App 还款页，待 App 确认）。 */
        private String pushDefaultDeepLink = "";
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
        return sg != null && sg.getApiKey() != null && !sg.getApiKey().isEmpty()
                && sg.getFromEmail() != null && !sg.getFromEmail().isEmpty();
    }

    public boolean isNotificationConfigured() {
        Notification n = notification;
        return n != null
                && n.getBaseUrl() != null && !n.getBaseUrl().isEmpty()
                && n.getAppCode() != null && !n.getAppCode().isEmpty()
                && n.getAppKey() != null && !n.getAppKey().isEmpty();
    }

    /** 测试端点（/v1/sms/testSend）免签名，仅需 base-url + app-code。 */
    public boolean isNotificationTestConfigured() {
        Notification n = notification;
        return n != null
                && n.getBaseUrl() != null && !n.getBaseUrl().isEmpty()
                && n.getAppCode() != null && !n.getAppCode().isEmpty();
    }
}
