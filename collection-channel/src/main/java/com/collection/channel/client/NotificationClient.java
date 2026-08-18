package com.collection.channel.client;

import com.alibaba.fastjson.JSON;
import com.collection.channel.config.ChannelProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 通知中心（common-notification）HTTP 客户端。
 *
 * <p>统一注入鉴权字段 {@code appCode / dateTime / sign}（{@code sign = MD5(appCode+appKey+dateTime)} 小写
 * hex）， POST JSON 并解析 {@link NotificationResponse}。
 *
 * <p>仅对 {@link HttpFailureClassifier#provablyNotSent 可证明未写给供应商}的故障做 <b>渠道侧短重试</b>（默认重试 1
 * 次）——通知中心没有幂等字段，读超时与 5xx 下请求可能已被受理，重试就是重复发送。这两类一律立即上抛， 由 Adapter 映射为 {@code retryable=false}
 * 的结果未知。业务码（HTTP 200 + body code）不在此重试，交由 Adapter 按 §9 映射。
 */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    /** 总尝试次数（1 次正常 + 1 次重试）。 */
    private static final int MAX_ATTEMPTS = 2;

    private static final long RETRY_BACKOFF_MS = 500L;

    @Resource private ChannelProperties properties;

    @Resource private RestTemplate channelRestTemplate;

    public boolean isConfigured() {
        return properties.isNotificationConfigured();
    }

    /**
     * 向通知中心 POST 一个请求体（自动补充鉴权字段 {@code appCode/dateTime/sign}）。
     *
     * @param path 接口路径，如 {@code /v1/sms/send}
     * @param body 业务字段（不含鉴权）
     * @return 解析后的响应
     * @throws RestClientException 结果未知（读超时 / 5xx）立即抛出；可证明未发出的故障重试耗尽后抛出
     */
    public NotificationResponse post(String path, Map<String, Object> body) {
        return execute(path, body, true);
    }

    /** 免签名 POST（仅补 {@code appCode}），用于 {@code @NotAuth} 测试端点 {@code /v1/sms/testSend}。 */
    public NotificationResponse postNoAuth(String path, Map<String, Object> body) {
        return execute(path, body, false);
    }

    private NotificationResponse execute(String path, Map<String, Object> body, boolean withAuth) {
        String url = baseUrl() + path;
        for (int attempt = 1; ; attempt++) {
            try {
                ResponseEntity<String> response =
                        channelRestTemplate.postForEntity(
                                url, buildEntity(body, withAuth), String.class);
                return NotificationResponse.parse(response.getBody());
            } catch (RestClientException e) {
                if (attempt >= MAX_ATTEMPTS || !HttpFailureClassifier.provablyNotSent(e)) {
                    throw e;
                }
                log.warn(
                        "[NotificationClient] not-sent failure, retrying path={} attempt={}/{}: {}",
                        path,
                        attempt,
                        MAX_ATTEMPTS,
                        e.getMessage());
            }
            sleepBeforeRetry(attempt);
        }
    }

    private HttpEntity<String> buildEntity(Map<String, Object> body, boolean withAuth) {
        Map<String, Object> payload = new HashMap<>(body);
        String appCode = properties.getNotification().getAppCode();
        payload.put("appCode", appCode);
        if (withAuth) {
            String dateTime = String.valueOf(System.currentTimeMillis());
            payload.put("dateTime", dateTime);
            payload.put("sign", sign(appCode, properties.getNotification().getAppKey(), dateTime));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(JSON.toJSONString(payload), headers);
    }

    /** {@code sign = MD5(appCode + appKey + dateTime)}，三字段直接拼接、无分隔符，小写 hex。 */
    static String sign(String appCode, String appKey, String dateTime) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest =
                    md.digest((appCode + appKey + dateTime).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private String baseUrl() {
        String base = properties.getNotification().getBaseUrl();
        if (base != null && base.endsWith("/")) {
            return base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static void sleepBeforeRetry(int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return;
        }
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
