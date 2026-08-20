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
 * Valubo Facade AI 外呼 Adapter。渠道层只负责一案一批提交；业务重拨与 Wave-2 由引擎编排。
 *
 * <p>不传 {@code script.language}（用租户默认 Taglish）；不传 brand / line / caller。
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
            log.error("[FacadeAiCallAdapter] channel.facade not configured");
            return AdapterSupport.notConfigured("AI_CALL");
        }
        String callee = normalizeE164(command.getTargetAddress());
        if (callee == null) {
            return AdapterSupport.permanentFailure("INVALID_E164");
        }
        BigDecimal overdue = overdueAmount(command);
        if (overdue == null || overdue.signum() <= 0) {
            return AdapterSupport.permanentFailure("ZERO_OVERDUE_AMOUNT");
        }

        ChannelProperties.Facade cfg = properties.getFacade();
        String externalId = externalId(command, callee);
        try {
            String batchId = createBatch(cfg, externalId);
            if (batchId == null) {
                return AdapterSupport.permanentFailure("FACADE_NO_BATCH_ID");
            }
            StepResult casesResult = uploadCase(cfg, batchId, command, callee, overdue, externalId);
            if (!casesResult.isSuccess()) {
                return casesResult;
            }
            StepResult startResult = startBatch(cfg, batchId);
            if (!startResult.isSuccess()) {
                return startResult;
            }
            log.info(
                    "[FacadeAiCallAdapter] started batchId={} callee={} caseId={}",
                    batchId,
                    maskPhone(callee),
                    command.getMetadata() == null
                            ? null
                            : command.getMetadata().get(StepCommand.META_CASE_ID));
            return AdapterSupport.delivered(batchId);
        } catch (IllegalStateException e) {
            log.warn("[FacadeAiCallAdapter] business failure callee={}", maskPhone(callee), e);
            return AdapterSupport.permanentFailure("FACADE_CREATE_BATCH");
        } catch (Exception e) {
            log.warn("[FacadeAiCallAdapter] HTTP failure callee={}", maskPhone(callee), e);
            return AdapterSupport.mapHttpException("FACADE", e);
        }
    }

    public JSONObject getBatch(String batchId) {
        ChannelProperties.Facade cfg = properties.getFacade();
        String url = join(cfg.getBaseUrl(), "/batches/" + batchId);
        ResponseEntity<String> response =
                facadeRestTemplate.exchange(url, HttpMethod.GET, entity(cfg, null), String.class);
        return JSON.parseObject(response.getBody());
    }

    public Map<String, Object> previewPayload(StepCommand command) {
        String callee = normalizeE164(command.getTargetAddress());
        BigDecimal overdue = overdueAmount(command);
        String externalId = externalId(command, callee == null ? "unknown" : callee);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("batch", buildBatchBody(externalId));
        out.put(
                "case",
                buildCaseBody(
                        command, callee, overdue == null ? BigDecimal.ZERO : overdue, externalId));
        return out;
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
        String url = join(cfg.getBaseUrl(), "/batches");
        ResponseEntity<String> response =
                facadeRestTemplate.postForEntity(
                        url, entity(cfg, buildBatchBody(externalBatchId)), String.class);
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
            BigDecimal overdue,
            String externalId) {
        String url = join(cfg.getBaseUrl(), "/batches/" + batchId + "/cases");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
        cases.add(buildCaseBody(command, callee, overdue, externalId));
        payload.put("cases", cases);
        ResponseEntity<String> response =
                facadeRestTemplate.postForEntity(url, entity(cfg, payload), String.class);
        JSONObject body = parseBody(response.getBody());
        if (!isSuccess(body)) {
            return AdapterSupport.permanentFailure("FACADE_UPLOAD_CASE");
        }
        JSONObject data = body.getJSONObject("data");
        if (data != null && data.getIntValue("rejected") > 0) {
            JSONArray errors = data.getJSONArray("errors");
            String code =
                    errors != null && !errors.isEmpty()
                            ? errors.getJSONObject(0).getString("error_code")
                            : "FACADE_CASE_REJECTED";
            return AdapterSupport.permanentFailure(
                    StringUtils.defaultIfBlank(code, "FACADE_CASE_REJECTED"));
        }
        return AdapterSupport.delivered(batchId);
    }

    private StepResult startBatch(ChannelProperties.Facade cfg, String batchId) {
        String url = join(cfg.getBaseUrl(), "/batches/" + batchId + "/start");
        ResponseEntity<String> response =
                facadeRestTemplate.postForEntity(
                        url, entity(cfg, new LinkedHashMap<String, Object>()), String.class);
        JSONObject body = parseBody(response.getBody());
        if (!isSuccess(body)) {
            return AdapterSupport.permanentFailure("FACADE_START_BATCH");
        }
        return AdapterSupport.delivered(batchId);
    }

    private Map<String, Object> buildBatchBody(String externalBatchId) {
        ChannelProperties.Facade cfg = properties.getFacade();
        Map<String, Object> script = new LinkedHashMap<String, Object>();
        script.put("domain", "collection");
        Map<String, Object> window = new LinkedHashMap<String, Object>();
        window.put("start_time", cfg.getWindowStart());
        window.put("end_time", cfg.getWindowEnd());
        Map<String, Object> dial = new LinkedHashMap<String, Object>();
        dial.put("timezone", cfg.getTimezone());
        dial.put("windows", Arrays.asList(window));
        dial.put("weekdays", Arrays.asList(1, 2, 3, 4, 5, 6, 7));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("external_batch_id", externalBatchId);
        body.put("script", script);
        body.put("dial_policy", dial);
        return body;
    }

    private Map<String, Object> buildCaseBody(
            StepCommand command, String callee, BigDecimal overdue, String externalId) {
        ChannelProperties.Facade cfg = properties.getFacade();
        String name = AdapterSupport.metadataString(command, META_BORROWER_NAME);
        if (StringUtils.isBlank(name)) {
            name = "Test Borrower";
        }
        Map<String, Object> borrower = new LinkedHashMap<String, Object>();
        borrower.put("name", name);
        Map<String, Object> debt = new LinkedHashMap<String, Object>();
        debt.put("product_type", cfg.getProductType());
        debt.put("currency", cfg.getCurrency());
        debt.put("overdue_amount", overdue);
        debt.put("days_past_due", dpd(command));
        debt.put("due_date", dueDate(command));
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        Object caseId =
                command.getMetadata() == null
                        ? null
                        : command.getMetadata().get(StepCommand.META_CASE_ID);
        if (caseId != null) {
            metadata.put("case_id", caseId);
        }
        String planId = AdapterSupport.metadataString(command, META_PLAN_ID);
        if (planId != null) {
            metadata.put("plan_id", planId);
        }
        String stepId = AdapterSupport.metadataString(command, META_STEP_ID);
        if (stepId != null) {
            metadata.put("step_id", stepId);
        }
        metadata.put("smoke", Boolean.TRUE);
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("borrower", borrower);
        context.put("debt", debt);
        context.put("prior_contacts", new ArrayList<Object>());
        context.put("prior_promises", new ArrayList<Object>());
        context.put("client_metadata", metadata);
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("external_case_id", externalId);
        item.put("callee_e164", callee);
        item.put("business_context", context);
        return item;
    }

    private HttpEntity<String> entity(ChannelProperties.Facade cfg, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(cfg.getApiKey());
        if (payload == null) {
            return new HttpEntity<String>(headers);
        }
        return new HttpEntity<String>(JSON.toJSONString(payload), headers);
    }

    private static String join(String base, String path) {
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return root + path;
    }

    private static JSONObject parseBody(String raw) {
        if (StringUtils.isBlank(raw)) {
            return new JSONObject();
        }
        return JSON.parseObject(raw);
    }

    private static boolean isSuccess(JSONObject body) {
        return body != null && Boolean.TRUE.equals(body.getBoolean("success"));
    }

    private static String errorMessage(JSONObject body) {
        if (body == null) {
            return "empty";
        }
        JSONObject error = body.getJSONObject("error");
        if (error == null) {
            return body.toJSONString();
        }
        return error.getString("code") + " " + error.getString("message");
    }

    private static String externalId(StepCommand command, String callee) {
        Object caseId =
                command.getMetadata() == null
                        ? null
                        : command.getMetadata().get(StepCommand.META_CASE_ID);
        String suffix = caseId != null ? String.valueOf(caseId) : callee.replace("+", "");
        return "mocasa-smoke-" + suffix + "-" + System.currentTimeMillis();
    }

    private static BigDecimal overdueAmount(StepCommand command) {
        String raw = AdapterSupport.metadataString(command, META_OVERDUE_AMOUNT);
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int dpd(StepCommand command) {
        String raw = AdapterSupport.metadataString(command, META_DPD);
        if (StringUtils.isBlank(raw)) {
            return 1;
        }
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String dueDate(StepCommand command) {
        String raw = AdapterSupport.metadataString(command, META_DUE_DATE);
        if (StringUtils.isNotBlank(raw)) {
            return raw;
        }
        return LocalDate.now(PHT).minusDays(dpd(command)).toString();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 2);
    }
}
