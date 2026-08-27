package com.collection.channel.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.collection.channel.config.ChannelProperties;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Facade 批次 HTTP 调用：create / upload / start 三段各自可独立调用。
 *
 * <p>拆开的原因是波次聚合需要「先建批、攒案、稍后一次起批」，而一案一批需要三段连着走。 两条路径共用同一套请求体构造与错误码，避免出现两套 Facade 契约。
 */
@Component
public class FacadeBatchClient {

    @Resource private ChannelProperties properties;
    @Resource private RestTemplate facadeRestTemplate;

    /**
     * @return Facade 侧 batchId；建批被业务拒绝时抛 {@link IllegalStateException}
     */
    public String createBatch(String externalBatchId) {
        ChannelProperties.Facade cfg = properties.getFacade();
        ResponseEntity<String> response =
                facadeRestTemplate.postForEntity(
                        join(cfg.getBaseUrl(), "/batches"),
                        entity(cfg, buildBatchBody(externalBatchId)),
                        String.class);
        JSONObject body = parseBody(response.getBody());
        if (!isSuccess(body)) {
            throw new IllegalStateException("FACADE_CREATE_BATCH " + errorMessage(body));
        }
        JSONObject data = body.getJSONObject("data");
        return data == null ? null : data.getString("batch_id");
    }

    /** 同一批次 start 前可多次调用，单次上限 500 条（手册 §5.1）。 */
    public UploadOutcome uploadCases(String batchId, List<Map<String, Object>> cases) {
        ChannelProperties.Facade cfg = properties.getFacade();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("cases", cases);
        ResponseEntity<String> response =
                facadeRestTemplate.postForEntity(
                        join(cfg.getBaseUrl(), "/batches/" + batchId + "/cases"),
                        entity(cfg, body),
                        String.class);
        JSONObject result = parseBody(response.getBody());
        if (!isSuccess(result)) {
            return UploadOutcome.rejected("FACADE_UPLOAD_CASE");
        }
        JSONObject data = result.getJSONObject("data");
        if (data != null && data.getIntValue("rejected") > 0) {
            JSONArray errors = data.getJSONArray("errors");
            String errorCode =
                    errors != null && !errors.isEmpty()
                            ? errors.getJSONObject(0).getString("error_code")
                            : "FACADE_CASE_REJECTED";
            return UploadOutcome.rejected(
                    StringUtils.defaultIfBlank(errorCode, "FACADE_CASE_REJECTED"));
        }
        return UploadOutcome.accepted();
    }

    public boolean startBatch(String batchId) {
        ChannelProperties.Facade cfg = properties.getFacade();
        ResponseEntity<String> response =
                facadeRestTemplate.postForEntity(
                        join(cfg.getBaseUrl(), "/batches/" + batchId + "/start"),
                        entity(cfg, new LinkedHashMap<String, Object>()),
                        String.class);
        return isSuccess(parseBody(response.getBody()));
    }

    public JSONObject getBatch(String batchId) {
        ChannelProperties.Facade cfg = properties.getFacade();
        ResponseEntity<String> response =
                facadeRestTemplate.exchange(
                        join(cfg.getBaseUrl(), "/batches/" + batchId),
                        HttpMethod.GET,
                        entity(cfg, null),
                        String.class);
        return parseBody(response.getBody());
    }

    /**
     * 建批请求体。
     *
     * <p>**不下发回调地址**：Facade 的 callback 是账户级配置，所有批次共用，由对方控制台预先登记，不随请求传（2026-08-20
     * 修订说明确认）。同一份修订说明还收紧了 {@code dial_policy}：只允许 {@code timezone} / {@code windows} / {@code
     * weekdays}，带上已取消的 {@code ring_timeout_sec}、{@code retry}、{@code predictive}、 {@code
     * terminal_sip_codes} 或顶层 {@code prepare_mode} 会直接 HTTP 422 建批失败，不再静默忽略。
     */
    public Map<String, Object> buildBatchBody(String externalBatchId) {
        ChannelProperties.Facade cfg = properties.getFacade();
        Map<String, Object> window = new LinkedHashMap<String, Object>();
        window.put("start_time", cfg.getWindowStart());
        window.put("end_time", cfg.getWindowEnd());
        Map<String, Object> dialPolicy = new LinkedHashMap<String, Object>();
        dialPolicy.put("timezone", cfg.getTimezone());
        dialPolicy.put("windows", Arrays.asList(window));
        dialPolicy.put("weekdays", Arrays.asList(1, 2, 3, 4, 5, 6, 7));
        Map<String, Object> script = new LinkedHashMap<String, Object>();
        script.put("domain", "collection");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("external_batch_id", externalBatchId);
        body.put("script", script);
        body.put("dial_policy", dialPolicy);
        return body;
    }

    private HttpEntity<String> entity(ChannelProperties.Facade cfg, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(cfg.getApiKey());
        return payload == null
                ? new HttpEntity<String>(headers)
                : new HttpEntity<String>(JSON.toJSONString(payload), headers);
    }

    private static String join(String baseUrl, String path) {
        return (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + path;
    }

    private static JSONObject parseBody(String raw) {
        return StringUtils.isBlank(raw) ? new JSONObject() : JSON.parseObject(raw);
    }

    private static boolean isSuccess(JSONObject body) {
        return body != null && Boolean.TRUE.equals(body.getBoolean("success"));
    }

    private static String errorMessage(JSONObject body) {
        JSONObject error = body == null ? null : body.getJSONObject("error");
        return error == null ? String.valueOf(body) : error.getString("message");
    }

    /** 上传结果：整体被拒或存在被拒案件时带回 Facade 的 error_code。 */
    public static final class UploadOutcome {

        private final boolean success;
        private final String errorCode;

        private UploadOutcome(boolean success, String errorCode) {
            this.success = success;
            this.errorCode = errorCode;
        }

        static UploadOutcome accepted() {
            return new UploadOutcome(true, null);
        }

        static UploadOutcome rejected(String errorCode) {
            return new UploadOutcome(false, errorCode);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
