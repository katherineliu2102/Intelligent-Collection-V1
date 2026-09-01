package com.collection.admin.web.sendgrid;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 用本地生成的 P-256 密钥对模拟 SendGrid 签名：私钥签 {@code timestamp || body}，只把公钥交给验签器。 SendGrid 的 Signed Event
 * Webhook 用的正是这套算法与被签字节顺序。
 */
class SendGridEventVerifierTest {

    private static final String BODY = "[{\"event\":\"open\",\"idempotency_key\":\"7:1:0\"}]";
    private static final String TIMESTAMP = "1780000000";
    private static final long NOW = 1780000000L;

    private static KeyPair keyPair;
    private static String publicKeyBase64;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static String sign(String timestamp, String body) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(timestamp.getBytes(StandardCharsets.UTF_8));
        signer.update(body.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static SendGridEventVerifier.Outcome verify(String body, String signature) {
        return SendGridEventVerifier.verify(
                body.getBytes(StandardCharsets.UTF_8),
                signature,
                TIMESTAMP,
                publicKeyBase64,
                600,
                NOW);
    }

    @Test
    void acceptsGenuineSignature() throws Exception {
        assertThat(verify(BODY, sign(TIMESTAMP, BODY)).isValid()).isTrue();
    }

    @Test
    void rejectsBodyTamperedAfterSigning() throws Exception {
        String signature = sign(TIMESTAMP, BODY);

        SendGridEventVerifier.Outcome outcome = verify(BODY.replace("open", "click"), signature);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.getReason()).isEqualTo("SIGNATURE_MISMATCH");
    }

    @Test
    void rejectsSignatureBoundToAnotherTimestamp() throws Exception {
        // 时间戳参与签名，重放者换 timestamp 骗过新鲜度校验就会破坏签名。
        SendGridEventVerifier.Outcome outcome = verify(BODY, sign("1779999000", BODY));

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.getReason()).isEqualTo("SIGNATURE_MISMATCH");
    }

    @Test
    void rejectsStaleTimestampBeyondTolerance() throws Exception {
        SendGridEventVerifier.Outcome outcome =
                SendGridEventVerifier.verify(
                        BODY.getBytes(StandardCharsets.UTF_8),
                        sign(TIMESTAMP, BODY),
                        TIMESTAMP,
                        publicKeyBase64,
                        600,
                        NOW + 601);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.getReason()).isEqualTo("TIMESTAMP_OUT_OF_TOLERANCE");
    }

    @Test
    void skipsFreshnessCheckWhenToleranceDisabled() throws Exception {
        SendGridEventVerifier.Outcome outcome =
                SendGridEventVerifier.verify(
                        BODY.getBytes(StandardCharsets.UTF_8),
                        sign(TIMESTAMP, BODY),
                        TIMESTAMP,
                        publicKeyBase64,
                        0,
                        NOW + 999999);

        assertThat(outcome.isValid()).isTrue();
    }

    @Test
    void rejectsWhenPublicKeyMissing() throws Exception {
        SendGridEventVerifier.Outcome outcome =
                SendGridEventVerifier.verify(
                        BODY.getBytes(StandardCharsets.UTF_8),
                        sign(TIMESTAMP, BODY),
                        TIMESTAMP,
                        "",
                        600,
                        NOW);

        assertThat(outcome.isValid()).isFalse();
        assertThat(outcome.getReason()).isEqualTo("PUBLIC_KEY_NOT_CONFIGURED");
    }

    @Test
    void rejectsWhenHeadersAbsent() {
        assertThat(verify(BODY, null).getReason()).isEqualTo("MISSING_HEADER");
    }

    @Test
    void rejectsMalformedBase64Signature() {
        assertThat(verify(BODY, "not-base64!!").getReason())
                .isEqualTo("MALFORMED_SIGNATURE_OR_KEY");
    }

    @Test
    void rejectsEmptyBody() {
        SendGridEventVerifier.Outcome outcome =
                SendGridEventVerifier.verify(
                        new byte[0], "sig", TIMESTAMP, publicKeyBase64, 600, NOW);

        assertThat(outcome.getReason()).isEqualTo("EMPTY_BODY");
    }
}
