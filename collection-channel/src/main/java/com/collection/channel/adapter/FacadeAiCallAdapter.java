package com.collection.channel.adapter;

import com.alibaba.fastjson.JSONObject;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Valubo Facade AI 外呼 Adapter。
 *
 * <p>两条出站路径共用同一套请求体与错误码：默认<b>一案一批</b>（create batch → upload case → start）；开启
 * {@code channel.facade.batch-aggregation.enabled} 后走<b>波次聚合</b>，案件先缓冲在 {@link
 * FacadeBatchCoordinator}，由它按触达槽合并成一个批次再起批。撤单、录音与日终对账仍未接入。
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
    @Resource private FacadeBatchClient batchClient;

    /** 纯逻辑单测手工构造 Adapter 时为空，此时始终走一案一批。 */
    @Autowired(required = false)
    private FacadeBatchCoordinator batchCoordinator;

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

        Map<String, Object> caseBody = buildCaseBody(command, callee, borrowerName, overdueAmount);
        String caseId = AdapterSupport.metadataString(command, StepCommand.META_CASE_ID);

        if (batchCoordinator != null && batchCoordinator.isEnabled()) {
            String waveBatchId =
                    batchCoordinator.enroll(
                            longMetadata(command, META_PLAN_ID),
                            longMetadata(command, META_STEP_ID),
                            longMetadata(command, StepCommand.META_CASE_ID),
                            caseBody);
            if (waveBatchId != null) {
                log.info(
                        "[FacadeAiCallAdapter] enrolled into wave {} caseId={}",
                        waveBatchId,
                        caseId);
                return AdapterSupport.delivered(waveBatchId);
            }
        }

        String externalBatchId = externalBatchId(command);
        try {
            String batchId = batchClient.createBatch(externalBatchId);
            if (StringUtils.isBlank(batchId)) {
                return AdapterSupport.permanentFailure("FACADE_NO_BATCH_ID");
            }
            FacadeBatchClient.UploadOutcome upload =
                    batchClient.uploadCases(batchId, Collections.singletonList(caseBody));
            if (!upload.isSuccess()) {
                return AdapterSupport.permanentFailure(upload.getErrorCode());
            }
            if (!batchClient.startBatch(batchId)) {
                return AdapterSupport.permanentFailure("FACADE_START_BATCH");
            }
            log.info(
                    "[FacadeAiCallAdapter] batch started batchId={} caseId={}", batchId, caseId);
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
        payload.put("batch", batchClient.buildBatchBody(externalBatchId(command)));
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
        return batchClient.getBatch(batchId);
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

    private static Long longMetadata(StepCommand command, String key) {
        String raw = AdapterSupport.metadataString(command, key);
        try {
            return StringUtils.isBlank(raw) ? null : Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> singletonMap(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
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
