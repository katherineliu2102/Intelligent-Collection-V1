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
    private Fcm fcm = new Fcm();
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
        private Sms sms = new Sms();
        private Voice voice = new Voice();

        @Data
        public static class Sms {
            private String url = "";
            private String senderId = "";
        }

        @Data
        public static class Voice {
            private String url = "";
        }
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
    public static class Fcm {
        private String projectId = "";
        /** Firebase 服务账号 JSON 全文（Nacos 多行字符串）。 */
        private String serviceAccountJson = "";
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

    public boolean isLthSmsConfigured() {
        Lth.Sms sms = lth.getSms();
        return sms != null && sms.getUrl() != null && !sms.getUrl().isEmpty();
    }

    public boolean isSendGridConfigured() {
        SendGrid sg = sendgrid;
        return sg != null && sg.getApiKey() != null && !sg.getApiKey().isEmpty()
                && sg.getFromEmail() != null && !sg.getFromEmail().isEmpty();
    }

    public boolean isFcmConfigured() {
        Fcm f = fcm;
        return f != null && f.getProjectId() != null && !f.getProjectId().isEmpty()
                && f.getServiceAccountJson() != null && !f.getServiceAccountJson().isEmpty();
    }
}
