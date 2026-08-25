package com.collection.channel.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Valubo Facade AI 外呼 Adapter。
 *
 * <p>当前只覆盖一案一批的 L1 冒烟与单 step dispatch：create batch → upload case → start。Facade callback、撤单、 Wave-2
 * 编排及批次聚合仍未接入。
 */
@Component
public class FacadeAiCallAdapter implements ChannelAdapter {

    public static final String META_BORROWER_NAME = "borrower_name";
    public static final String META_OVERDUE_AMOUNT = "overdue_amount";
    public static final String META_DPD = "dpd";
    public static final String META_DUE_DATE = "due_date";
    public static final String META_PLAN_ID = "plan_id";
    public static final String META_STEP_ID = "step_id";

    private static final Logger log = LoggerFactory.getLogger(FacadeAiCallAdapter.class);
    private static final ZoneId PHT = ZoneId.of("Asia/Manila");

    @Resource private ChannelProperties properties;
    @Resource private RestTemplate facadeRestTemplate;

    @Override
    public ChannelType channelType() {
        return ChannelType.AI_CALL;
    }

    @Override
    public StepResult send(StepCommand command) {
        if (!properties.isFacadeConfigured()) {
            log.error("[FacadeAiCallAdapter] channel.facade is not configured");
            return AdapterSupport.notConfigured("AI_CALL");
        }
        String callee = resolveCallee(command);
        if (callee == null) {
            return AdapterSupport.permanentFailure("INVALID_E164");
        }
        String borrowerName = AdapterSupport.metadataString(command, META_BORROWER_NAME);
        if (StringUtils.isBlank(borrowerName)) {
            return AdapterSupport.permanentFailure("MISSING_BORROWER_NAME");
        }
        BigDecimal overdueAmount = overdueAmount(command);
        if (overdueAmount == null || overdueAmount.signum() <= 0) {
            return AdapterSupport.permanentFailure("ZERO_OVERDUE_AMOUNT");
        }

        ChannelProperties.Facade cfg = properties.getFacade();
        String externalBatchId = externalBatchId(command);
        try {
            String batchId = createBatch(cfg, externalBatchId);
            if (StringUtils.isBlank(batchId)) {
                return AdapterSupport.permanentFailure("FACADE_NO_BATCH_ID");
            }
            StepResult upload =
                    uploadCase(cfg, batchId, command, callee, borrowerName, overdueAmount);
            if (!upload.isSuccess()) {
                return upload;
            }
            StepResult started = startBatch(cfg, batchId);
            if (!started.isSuccess()) {
                return started;
            }
            log.info(
                    "[FacadeAiCallAdapter] batch started batchId={} caseId={}",
                    batchId,
                    AdapterSupport.metadataString(command, StepCommand.META_CASE_ID));
            return AdapterSupport.delivered(batchId);
        } catch (IllegalStateException e) {
            log.warn("[FacadeAiCallAdapter] Facade business failure: {}", e.getMessage());
            return AdapterSupport.permanentFailure("FACADE_CREATE_BATCH");
        } catch (Exception e) {
            log.warn("[FacadeAiCallAdapter] Facade HTTP failure", e);
            return AdapterSupport.mapHttpException("FACADE", e);
        }
    }

    /**
     * 演练期把外呼改投测试号。
     *
     * <p>AI_CALL 没有 SMS 的 {@code smsTestMode}、PUSH 的 {@code pushTestToken} 那样的出口，而 {@link #send}
     * 是建批次 + 起呼，pilot 下调度一旦打开就是真拨给借款人。 配置 {@code channel.facade.test-callee} 后全部外呼改投该号码，可在不触达客户的前提下
     * 验证 Facade 契约与 callbackTimeout 收敛。
     *
     * <p>转真实触达前必须清空——留着会让所有外呼都打到测试号，线上表现为"催收全无效果"。 {@code PilotReadinessValidator} 在启动时会列出该开关。
     */
    private String resolveCallee(StepCommand command) {
        String testCallee = properties.getFacade().getTestCallee();
        if (StringUtils.isNotBlank(testCallee)) {
            log.warn("[FacadeAiCallAdapter] test-callee 生效，外呼改投 {}（不拨打借款人号码）", testCallee.trim());
            return normalizeE164(testCallee.trim());
        }
        return normalizeE164(command.getTargetAddress());
    }

    /** L1 endpoint uses this to inspect the outbound JSON without dialing. */
    public Map<String, Object> previewPayload(StepCommand command) {
        String callee = normalizeE164(command.getTargetAddress());
        BigDecimal overdue = overdueAmount(command);
        String name = AdapterSupport.metadataString(command, META_BORROWER_NAME);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("batch", buildBatchBody(externalBatchId(command)));
        payload.put(
                "case",
                buildCaseBody(
                        command,
                        callee,
                        StringUtils.defaultIfBlank(name, "Test Borrower"),
                        overdue == null ? BigDecimal.ZERO : overdue));
        return payload;
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

    public static String normalizeE164(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String digits = raw.trim().replaceAll("[\\s-]", "");
        if (digits.startsWith("+")) {
            return digits.matches("\\+63\\d{10}") ? digits : null;
        }
        if (digits.startsWith("63") && digits.length() == 12) {
            return "+" + digits;
        }
        if (digits.startsWith("0") && digits.length() == 11) {
            return "+63" + digits.substring(1);
        }
        if (digits.startsWith("9") && digits.length() == 10) {
            return "+63" + digits;
        }
        return null;
    }

    private String createBatch(ChannelProperties.Facade cfg, String externalBatchId) {
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

    private StepResult uploadCase(
            ChannelProperties.Facade cfg,
            String batchId,
            StepCommand command,
            String callee,
            String borrowerName,
            BigDecimal overdueAmount) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
        cases.add(buildCaseBody(command, callee, borrowerName, overdueAmount));
        body.put("cases", cases);
        ResponseEntity<String> response =
                facadeRestTemplate.postForEntity(
                        join(cfg.getBaseUrl(), "/batches/" + batchId + "/cases"),
                        entity(cfg, body),
                        String.class);
        JSONObject result = parseBody(response.getBody());
        if (!isSuccess(result)) {
            return AdapterSupport.permanentFailure("FACADE_UPLOAD_CASE");
        }
        JSONObject data = result.getJSONObject("data");
        if (data != null && data.getIntValue("rejected") > 0) {
            JSONArray errors = data.getJSONArray("errors");
            String errorCode =
                    errors != null && !errors.isEmpty()
                            ? errors.getJSONObject(0).getString("error_code")
                            : "FACADE_CASE_REJECTED";
            return AdapterSupport.permanentFailure(
                    StringUtils.defaultIfBlank(errorCode, "FACADE_CASE_REJECTED"));
        }
        return AdapterSupport.delivered(batchId);
    }

    private StepResult startBatch(ChannelProperties.Facade cfg, String batchId) {
        ResponseEntity<String> response =
                facadeRestTemplate.postForEntity(
                        join(cfg.getBaseUrl(), "/batches/" + batchId + "/start"),
                        entity(cfg, new LinkedHashMap<String, Object>()),
                        String.class);
        return isSuccess(parseBody(response.getBody()))
                ? AdapterSupport.delivered(batchId)
                : AdapterSupport.permanentFailure("FACADE_START_BATCH");
    }

    /**
     * 建批请求体。
     *
     * <p>**不下发回调地址**：Facade 的 callback 是账户级配置，所有批次共用，由对方控制台预先登记，不随请求传（2026-08-20
     * 修订说明确认）。同一份修订说明还收紧了 {@code dial_policy}：只允许 {@code timezone} / {@code windows} / {@code
     * weekdays}，带上已取消的 {@code ring_timeout_sec}、{@code retry}、{@code predictive}、 {@code
     * terminal_sip_codes} 或顶层 {@code prepare_mode} 会直接 HTTP 422 建批失败，不再静默忽略。
     */
    private Map<String, Object> buildBatchBody(String externalBatchId) {
        ChannelProperties.Facade cfg = properties.getFacade();
        Map<String, Object> window = new LinkedHashMap<String, Object>();
        window.put("start_time", cfg.getWindowStart());
        window.put("end_time", cfg.getWindowEnd());
        Map<String, Object> dialPolicy = new LinkedHashMap<String, Object>();
        dialPolicy.put("timezone", cfg.getTimezone());
        dialPolicy.put("windows", Arrays.asList(window));
        dialPolicy.put("weekdays", Arrays.asList(1, 2, 3, 4, 5, 6, 7));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("external_batch_id", externalBatchId);
        body.put("script", singletonMap("domain", "collection"));
        body.put("dial_policy", dialPolicy);
        return body;
    }

    private Map<String, Object> buildCaseBody(
            StepCommand command, String callee, String borrowerName, BigDecimal overdueAmount) {
        ChannelProperties.Facade cfg = properties.getFacade();
        Map<String, Object> debt = new LinkedHashMap<String, Object>();
        debt.put("product_type", cfg.getProductType());
        debt.put("currency", cfg.getCurrency());
        debt.put("overdue_amount", overdueAmount);
        debt.put("days_past_due", dpd(command));
        debt.put("due_date", dueDate(command));

        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("borrower", singletonMap("name", borrowerName));
        context.put("debt", debt);
        context.put("prior_contacts", new ArrayList<Object>());
        context.put("prior_promises", new ArrayList<Object>());
        context.put("client_metadata", clientMetadata(command));

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("external_case_id", externalCaseId(command, callee));
        item.put("callee_e164", callee);
        item.put("business_context", context);
        return item;
    }

    private static Map<String, Object> clientMetadata(StepCommand command) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        copyMetadata(command, metadata, StepCommand.META_CASE_ID, "case_id");
        copyMetadata(command, metadata, META_PLAN_ID, "plan_id");
        copyMetadata(command, metadata, META_STEP_ID, "step_id");
        return metadata;
    }

    private static void copyMetadata(
            StepCommand command, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = command.getMetadata() == null ? null : command.getMetadata().get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    private HttpEntity<String> entity(ChannelProperties.Facade cfg, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(cfg.getApiKey());
        return payload == null
                ? new HttpEntity<String>(headers)
                : new HttpEntity<String>(JSON.toJSONString(payload), headers);
    }

    private static Map<String, Object> singletonMap(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
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

    private static String externalBatchId(StepCommand command) {
        return "mocasa-"
                + StringUtils.defaultIfBlank(
                        command.getProviderIdempotencyKey(), command.getIdempotencyKey());
    }

    private static String externalCaseId(StepCommand command, String callee) {
        String caseId = AdapterSupport.metadataString(command, StepCommand.META_CASE_ID);
        return StringUtils.defaultIfBlank(caseId, callee.replace("+", ""));
    }

    private static BigDecimal overdueAmount(StepCommand command) {
        String raw = AdapterSupport.metadataString(command, META_OVERDUE_AMOUNT);
        try {
            return StringUtils.isBlank(raw) ? null : new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int dpd(StepCommand command) {
        String raw = AdapterSupport.metadataString(command, META_DPD);
        try {
            return StringUtils.isBlank(raw) ? 1 : Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String dueDate(StepCommand command) {
        String configured = AdapterSupport.metadataString(command, META_DUE_DATE);
        return StringUtils.isNotBlank(configured)
                ? configured
                : LocalDate.now(PHT).minusDays(dpd(command)).toString();
    }
}
