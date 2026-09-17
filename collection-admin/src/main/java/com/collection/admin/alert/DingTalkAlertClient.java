package com.collection.admin.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 钉钉机器人。失败只打日志，不阻断扫描。 */
@Component
public class DingTalkAlertClient {

    private static final Logger log = LoggerFactory.getLogger(DingTalkAlertClient.class);

    private final AlertProperties properties;
    private final ObjectMapper objectMapper;

    public DingTalkAlertClient(AlertProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void sendText(String content) {
        if (StringUtils.isNotBlank(content) && !content.startsWith("【催收告警】")) {
            content = "【催收告警】 " + content;
        }
        String webhook = properties.getDingtalk().getWebhook();
        if (StringUtils.isBlank(webhook)) {
            log.warn("[alert] dingtalk webhook not configured, log only: {}", content);
            return;
        }
        HttpURLConnection conn = null;
        try {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("content", content);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "text");
            body.put("text", text);
            byte[] payload = objectMapper.writeValueAsBytes(body);
            conn = (HttpURLConnection) new URL(webhook).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                log.error("[alert] dingtalk http {}", code);
            }
        } catch (Exception e) {
            log.error("[alert] dingtalk send failed", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
