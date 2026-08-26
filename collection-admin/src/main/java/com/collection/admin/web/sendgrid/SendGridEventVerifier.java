package com.collection.admin.web.sendgrid;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SendGrid Signed Event Webhook 验签（ECDSA P-256 / SHA-256）。
 *
 * <p>与另两个入站端点的 HMAC 口径不同，且不可统一：签名由 SendGrid 用私钥生成，我们只有公钥， 被签名的字节是 {@code timestamp ||
 * rawBody}——{@code rawBody} 必须是收到的原始字节，不能 反序列化再重新序列化。Jackson 会规范化空白与键序，重排一个字节验签即失败，因此本类只接受 {@code
 * byte[]}，调用方也必须以 {@code @RequestBody byte[]} 接收。
 */
public final class SendGridEventVerifier {

    private static final Logger log = LoggerFactory.getLogger(SendGridEventVerifier.class);

    public static final String SIGNATURE_HEADER = "X-Twilio-Email-Event-Webhook-Signature";
    public static final String TIMESTAMP_HEADER = "X-Twilio-Email-Event-Webhook-Timestamp";

    private SendGridEventVerifier() {}

    /** 验签结果与拒收理由；理由只进日志与审计，不回给调用方，避免成为伪造者的探测口。 */
    public static final class Outcome {
        private final boolean valid;
        private final String reason;

        private Outcome(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public boolean isValid() {
            return valid;
        }

        public String getReason() {
            return reason;
        }
    }

    private static final Outcome OK = new Outcome(true, "OK");

    /**
     * @param publicKeyBase64 SendGrid 控制台给出的 base64 X.509 SubjectPublicKeyInfo
     * @param toleranceSeconds 时间戳容差，{@code <= 0} 表示不校验新鲜度
     * @param nowEpochSeconds 当前时间，由调用方注入以便单测固定时钟
     */
    public static Outcome verify(
            byte[] rawBody,
            String signatureBase64,
            String timestamp,
            String publicKeyBase64,
            long toleranceSeconds,
            long nowEpochSeconds) {
        if (rawBody == null || rawBody.length == 0) {
            return new Outcome(false, "EMPTY_BODY");
        }
        if (StringUtils.isBlank(signatureBase64) || StringUtils.isBlank(timestamp)) {
            return new Outcome(false, "MISSING_HEADER");
        }
        if (StringUtils.isBlank(publicKeyBase64)) {
            return new Outcome(false, "PUBLIC_KEY_NOT_CONFIGURED");
        }

        Outcome freshness = checkFreshness(timestamp, toleranceSeconds, nowEpochSeconds);
        if (freshness != null) {
            return freshness;
        }

        try {
            PublicKey publicKey =
                    KeyFactory.getInstance("EC")
                            .generatePublic(
                                    new X509EncodedKeySpec(
                                            Base64.getDecoder().decode(publicKeyBase64.trim())));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(timestamp.getBytes(StandardCharsets.UTF_8));
            verifier.update(rawBody);
            boolean valid = verifier.verify(Base64.getDecoder().decode(signatureBase64.trim()));
            return valid ? OK : new Outcome(false, "SIGNATURE_MISMATCH");
        } catch (IllegalArgumentException e) {
            // base64 解码失败：签名头或公钥被截断/换行污染，属配置或伪造，不是运行故障。
            return new Outcome(false, "MALFORMED_SIGNATURE_OR_KEY");
        } catch (Exception e) {
            log.warn("[sendgrid-webhook] verify failed unexpectedly", e);
            return new Outcome(false, "VERIFY_ERROR");
        }
    }

    private static Outcome checkFreshness(
            String timestamp, long toleranceSeconds, long nowEpochSeconds) {
        if (toleranceSeconds <= 0) {
            return null;
        }
        long sent;
        try {
            sent = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return new Outcome(false, "MALFORMED_TIMESTAMP");
        }
        if (Math.abs(nowEpochSeconds - sent) > toleranceSeconds) {
            return new Outcome(false, "TIMESTAMP_OUT_OF_TOLERANCE");
        }
        return null;
    }
}
