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
    private Facade facade = new Facade();
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
     * Valubo Facade AI 外呼 L1 联调配置。API Key 仅由 Nacos 或部署 Secret 注入，禁止写入仓库。
     *
     * <p>{@code insecureTls=true} 仅用于 local/test 下对方的自签名证书；Pilot / production 必须使用受信任证书或专用
     * TrustStore。
     */
    @Data
    public static class Facade {
        private String baseUrl = "";
        private String apiKey = "";
        private boolean insecureTls = false;
        private String productType = "Quick Loan";
        private String currency = "PHP";
        private String timezone = "Asia/Manila";
        private String windowStart = "08:00";
        private String windowEnd = "21:00";
        private String testCallee = "";
        /** Facade 入站回调验签用的 HMAC（手册 §11.3）。与 collection.webhook.hmac-secret 不是同一把。 */
        private String callbackSecret = "";

        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 30;

        private BatchAggregation batchAggregation = new BatchAggregation();
    }

    /**
     * AI_CALL 波次聚合：把同一触达槽的多个到期步骤合成一个 Facade 批次，使并发资源按批分配。
     *
     * <p>案件先缓冲在我方 Redis，直到起批那一刻才 upload，故起批前的取消（还款）无需 Facade 介入。
     * 起批后无法撤单，与一案一批时相同；**禁止**用批级 cancel 代偿，那会停掉同批其他借款人的电话。
     */
    @Data
    public static class BatchAggregation {
        /** 关闭时回到一案一批（create → upload 1 → start），行为与聚合上线前完全一致。 */
        private boolean enabled = false;
        /** 最后一案入批后静默这么久即起批。 */
        private int silenceSeconds = 15;
        /** 从首案入批起的最长等待，防止零星到期的步骤被无限期攒着。 */
        private int maxWaitSeconds = 120;
        /** 单批案件上限；Facade 单次上传上限为 500。满则立即另开一批。 */
        private int maxCasesPerBatch = 500;
        /** flusher 轮询间隔。 */
        private long pollIntervalMs = 5000;

        /**
         * 回调超时按「批内案数 ÷ 并发 × 单通时长 + 缓冲」估算，避免队尾还没拨就被超时哨兵判成 FAILED。
         * Facade 未给出每批并发的确切值前，这三个参数是保守估计，实测后再调。
         */
        private int assumedConcurrency = 5;

        private int assumedCallSeconds = 90;
        private int timeoutBufferMinutes = 15;
        /** 下界与一案一批时的引擎默认一致，避免小批次反而比以前更早超时。 */
        private int minTimeoutMinutes = 30;

        private int maxTimeoutMinutes = 120;
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
        /**
         * true → SMS 走免签名测试端点 /v1/sms/testSend（联调用，appKey 可空）。
         *
         * <p><b>这不是投递隔离开关。</b>2026-08-24 实测该端点返回的 {@code data.channel} 为 CreativeBlue / QHSms
         * 等真实运营商通道，通知中心也不存在 Virtual 账号（显式指定即报 {@code no valid account}）。 Adapter 不替换手机号，payload
         * 里始终是真实号码——开着它，短信照样真实送达。 要做到不触达真人，用 {@code smsTestRecipient}（那个才是真的改投目标，对照 PUSH 的 {@code
         * pushTestToken} 与 AI_CALL 的 {@code testCallee}）。
         */
        private boolean smsTestMode = false;
        /** 测试端点可选指定的通道账号名（accountName），空=默认测试路由。 */
        private String smsTestAccountName = "";
        /**
         * 自持号码隔离开关：非空时所有 SMS 强制改投该号码，不再发给借款人。
         *
         * <p>补 {@code smsTestMode} 补不了的那一半。通知中心没有 sandbox（无 Virtual 账号，测试端点仍走真实运营商）， 而 T3o
         * 的触达对象要求全部是团队自持号码；Pilot 案件来自数仓真实数据，号码是真实借款人的， 靠"上游名单里放测试号"在真实案件上做不到。故与 PUSH 的 {@code
         * pushTestToken}、AI_CALL 的 {@code testCallee} 对齐，在 Adapter 出口处改投。
         *
         * <p>生产必须留空。启动日志 {@code [PilotReadiness]} 段会把生效中的列出来。
         */
        private String smsTestRecipient = "";
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
        /** YAML/Nacos 文案发布版本；DB 模板命中时由 config_version 覆盖。 */
        private String releaseVersion = "unversioned";

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
        /**
         * @deprecated 不再使用。scriptSlot → d-xxx 见 {@code
         *     EmailMilestoneScriptSlots.PHASE1_SENDGRID_TEMPLATE_IDS}。
         */
        private Map<String, String> templates = new HashMap<>();
        /** 默认 https://api.sendgrid.com/v3/mail/send；单测可指向 WireMock。 */
        private String apiUrl = "https://api.sendgrid.com/v3/mail/send";
        /**
         * 自持邮箱隔离开关：非空时所有 Email 强制改投该地址，不再发给借款人。
         *
         * <p>理由同 {@code channel.notification.sms-test-recipient}：EMAIL 此前是四个渠道里唯一 既无 sandbox
         * 也无改投出口的， T3o 的「触达只发自持地址」在它上面无法成立。生产必须留空。
         */
        private String testRecipient = "";

        /**
         * Signed Event Webhook 的验签公钥：SendGrid 控制台给出的 base64 X.509 SubjectPublicKeyInfo（EC P-256）。
         *
         * <p>留空则 {@code /webhook/sendgrid} 在要求验签时一律拒收——端点公网可达，无公钥时无法区分 供应商事件与伪造事件，放行等于让任何人改写
         * timeline 与抑制名单。
         */
        private String eventWebhookPublicKey = "";

        /**
         * 事件时间戳容差（秒），0 表示不校验。
         *
         * <p>验签只能证明报文出自 SendGrid，不能证明它是新的：截获过的合法报文可无限重放。 默认 600s 兼顾供应商重试与两端时钟漂移。
         */
        private long eventWebhookToleranceSeconds = 600;
    }

    @Data
    public static class Compliance {
        private Map<String, Integer> dailyLimit = defaultDailyLimit();
        /** 单用户在一个 PHT 自然日内，所有自动化渠道合计最多触达次数。 */
        private int dailyTotalLimit = 3;

        private String timezone = "Asia/Manila";
        private String quietHoursStart = "21:00";
        private String quietHoursEnd = "08:00";
        private String touchWindowStart = "08:00";
        private String touchWindowEnd = "21:00";

        private static Map<String, Integer> defaultDailyLimit() {
            Map<String, Integer> limits = new HashMap<>();
            limits.put("SMS", 1);
            limits.put("PUSH", 1);
            limits.put("EMAIL", 1);
            limits.put("AI_CALL", 2);
            return limits;
        }
    }

    @Data
    public static class PlanTemplate {
        private List<PlanStepDef> steps = new ArrayList<>();
        /**
         * 生产日程模板：按 DPD 日和 PHT 固定槽位预排绝对 trigger_time。 未配置时回落到 {@link #steps} 的相对 delayMin 模式，供
         * local/L4 兼容。
         */
        private List<DayBlock> dayBlocks = new ArrayList<>();
    }

    @Data
    public static class PlanStepDef {
        private String channel;
        private int delayMin = 0;
        private int observeMin = 0;
        private long templateId = 0;
    }

    @Data
    public static class DayBlock {
        /** 相对 dueDate 的 DPD 日：D-3=-3、D0=0、D+1=1。 */
        private int dpdDay;

        private List<Slot> slots = new ArrayList<>();
    }

    @Data
    public static class Slot {
        private String channel;
        /** PHT 固定槽位，HH:mm。 */
        private String time;

        private int observeMin = 0;
        private long templateId = 0;
    }

    /**
     * 下发给异步渠道供应商的完整回调 URL：{@code baseUrl + /channel-callback}。
     *
     * <p>路径必须与应用唯一的入站端点一致。本方法此前拼 {@code /lth/voice}（LTH 供应商时代的遗留），而该路径从未有 Controller，供应商按下发地址回调只会拿到
     * 404；系统已确定只对接 Facade，故 2026-08-21 统一指向 {@code /channel-callback} 并去掉供应商专有命名。
     */
    public String callbackUrl() {
        String base = callback.getBaseUrl();
        if (base == null || base.isEmpty()) {
            return "";
        }
        return base.endsWith("/") ? base + "channel-callback" : base + "/channel-callback";
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

    public boolean isFacadeConfigured() {
        Facade f = facade;
        return f != null
                && f.getBaseUrl() != null
                && !f.getBaseUrl().trim().isEmpty()
                && f.getApiKey() != null
                && !f.getApiKey().trim().isEmpty();
    }
}
