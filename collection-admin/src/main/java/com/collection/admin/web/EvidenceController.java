package com.collection.admin.web;

import com.collection.engine.bus.RedisStreamEventBus;
import com.collection.ingestion.job.RedisDailyRollDeduplicator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T3o 简版观测 MVP 的可查询证据面（测试 SSOT T3o-O1…O4）。
 *
 * <p>指标回答的是「整体有没有异常」，回答不了「这一条为什么没触达」。T3o-O 每条都要求证据能关联到 event/case 或
 * plan/step，且明确不接受事后人工推断——本控制器把那几条关联查询固化，替代逐次手写 SQL。
 *
 * <p>只读：不写库、不发事件、不改状态。挂在 {@code /ops/**} 下即由 {@code AdminAuthInterceptor} 强制登录态。
 *
 * <p>PII 边界：外部 payload 原文（{@code t_ai_collection_inbox.payload}）、计划快照（{@code context_snapshot}）、
 * 话术渲染结果（{@code resolved_params} / {@code content_summary}）与回调原文（{@code canonical_payload}）
 * 一律不出参——它们含债务人姓名、金额与联系方式，且没有「脱敏后仍可用于排障」的形态。 需要原文时到库里按本接口给出的主键单独取，留在审计日志里。
 */
@RestController
@RequestMapping("/ops/evidence")
public class EvidenceController {

    private static final int DEFAULT_LIMIT = 20;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<RedisStreamEventBus> eventBus;
    private final ObjectProvider<RedisDailyRollDeduplicator> dailyRoll;

    public EvidenceController(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<RedisStreamEventBus> eventBus,
            ObjectProvider<RedisDailyRollDeduplicator> dailyRoll) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventBus = eventBus;
        this.dailyRoll = dailyRoll;
    }

    /**
     * T3o-O1 / O2：一条入站消息的完整去向。
     *
     * <p>回答「数仓说发了，到底进没进来、投影更没更新、内部事件发没发出、后续建了什么计划」。 {@code projection_applied=0} 是陈旧版本被跳过，{@code
     * publish_status=PENDING} 是投影已落库但 内部事件还没发出——后者正是发件箱要补发的那一类，不看这两列会误判成「没入案」。
     */
    @GetMapping("/event/{eventId}")
    public Map<String, Object> byEvent(@PathVariable String eventId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("eventId", eventId);
        Map<String, Object> inbox = queryOne(INBOX_SQL + " WHERE event_id = ?", eventId);
        evidence.put("inbox", inbox);
        Object caseId = inbox == null ? null : inbox.get("caseId");
        evidence.put("case", caseId == null ? null : caseSummary(((Number) caseId).longValue()));
        evidence.put(
                "plans", caseId == null ? emptyList() : plansByCase(((Number) caseId).longValue()));
        evidence.put("dlq", queryList(DLQ_SQL + " WHERE event_id = ?", eventId));
        return ApiResponse.success(evidence);
    }

    /** T3o-O1：按案件反查。投影、该案全部计划与最近触达记录一次取齐。 */
    @GetMapping("/case/{caseId}")
    public Map<String, Object> byCase(
            @PathVariable long caseId, @RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("caseId", caseId);
        evidence.put("case", caseSummary(caseId));
        evidence.put("inbox", queryList(INBOX_SQL + " WHERE case_id = ? ORDER BY id DESC", caseId));
        evidence.put("plans", plansByCase(caseId));
        evidence.put(
                "timeline",
                queryList(
                        TIMELINE_SQL + " WHERE case_id = ? ORDER BY id DESC LIMIT " + cap(limit),
                        caseId));
        return ApiResponse.success(evidence);
    }

    /**
     * T3o-O3：一个计划里每一步的去向。
     *
     * <p>步骤 {@code status=SKIPPED} 时 {@code result} 就是跳过原因（{@code COMPLIANCE_BLOCKED} / {@code
     * GUARD_ERROR} 等），这是「Guard 拦截」与「渠道真失败」的分界；AI_CALL 的接通细节只在回调审计里。
     */
    @GetMapping("/plan/{planId}")
    public Map<String, Object> byPlan(@PathVariable long planId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("planId", planId);
        evidence.put("plan", queryOne(PLAN_SQL + " WHERE id = ?", planId));
        evidence.put(
                "steps", queryList(STEP_SQL + " WHERE plan_id = ? ORDER BY step_order", planId));
        evidence.put(
                "timeline", queryList(TIMELINE_SQL + " WHERE plan_id = ? ORDER BY id", planId));
        evidence.put(
                "callbackAudit",
                queryList(CALLBACK_SQL + " WHERE plan_id = ? ORDER BY id", planId));
        return ApiResponse.success(evidence);
    }

    /**
     * T3o-O3 / O4：Redis 侧运行态与日切进度。
     *
     * <p>读数实时打 Redis，不用 gauge 的采样值——故障注入后要看当下，不是 30 秒前。 内存总线（local/test）下 {@code eventbus} 为 {@code
     * null}，属预期而非故障。
     */
    @GetMapping("/redis")
    public Map<String, Object> redis(
            @RequestParam(defaultValue = "10") int pelSample,
            @RequestParam(defaultValue = "50") int dedupSample) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        RedisStreamEventBus bus = eventBus.getIfAvailable();
        evidence.put(
                "eventBus",
                bus == null
                        ? Collections.singletonMap(
                                "note", "collection.eventbus != redis，内存总线无 Redis 证据")
                        : bus.evidenceSnapshot(pelSample, dedupSample));
        RedisDailyRollDeduplicator roll = dailyRoll.getIfAvailable();
        evidence.put(
                "dailyRoll",
                roll == null
                        ? Collections.singletonMap(
                                "note", "collection.ingestion.redis-dedup-enabled != true")
                        : roll.evidenceSnapshot());
        evidence.put("dlqByStatus", dlqByStatus());
        return ApiResponse.success(evidence);
    }

    private Map<String, Object> caseSummary(long caseId) {
        Map<String, Object> row = queryOne(CASE_SQL + " WHERE case_id = ?", caseId);
        if (row != null) {
            row.put("borrowerPhone", PiiMask.phone((String) row.get("borrowerPhone")));
            row.put("borrowerEmail", PiiMask.email((String) row.get("borrowerEmail")));
        }
        return row;
    }

    /** 计划连同其步骤一起返回：只给计划状态判不出「卡在哪一步」，而那才是排障要问的。 */
    private List<Map<String, Object>> plansByCase(long caseId) {
        List<Map<String, Object>> plans =
                queryList(PLAN_SQL + " WHERE case_id = ? ORDER BY id DESC", caseId);
        for (Map<String, Object> plan : plans) {
            Object id = plan.get("id");
            plan.put(
                    "steps",
                    id == null
                            ? emptyList()
                            : queryList(STEP_SQL + " WHERE plan_id = ? ORDER BY step_order", id));
        }
        return plans;
    }

    private List<Map<String, Object>> dlqByStatus() {
        return queryList(
                "SELECT status AS status, COUNT(*) AS count, MAX(last_failed_at) AS lastFailedAt "
                        + "FROM t_event_dlq GROUP BY status");
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = queryList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> queryList(String sql, Object... args) {
        return new ArrayList<>(jdbcTemplate.queryForList(sql, args));
    }

    private static List<Map<String, Object>> emptyList() {
        return Collections.emptyList();
    }

    private static int cap(int limit) {
        return Math.max(1, Math.min(200, limit <= 0 ? DEFAULT_LIMIT : limit));
    }

    private static final String INBOX_SQL =
            "SELECT id AS id, event_id AS eventId, case_id AS caseId, case_version AS caseVersion, "
                    + "message_type AS messageType, event_type AS eventType, "
                    + "projection_applied AS projectionApplied, publish_status AS publishStatus, "
                    + "published_at AS publishedAt, created_at AS createdAt, updated_at AS updatedAt "
                    + "FROM t_ai_collection_inbox";

    private static final String CASE_SQL =
            "SELECT case_id AS caseId, user_id AS userId, case_version AS caseVersion, dpd AS dpd, "
                    + "stage AS stage, collection_status AS collectionStatus, product AS product, "
                    + "overdue_amount AS overdueAmount, total_outstanding AS totalOutstanding, "
                    + "upcoming_amount AS upcomingAmount, due_date AS dueDate, "
                    + "next_due_date AS nextDueDate, borrower_phone AS borrowerPhone, "
                    + "borrower_email AS borrowerEmail, borrower_language AS borrowerLanguage, "
                    + "updated_at AS updatedAt, synced_at AS syncedAt "
                    + "FROM t_ai_collection";

    private static final String PLAN_SQL =
            "SELECT id AS id, case_id AS caseId, user_id AS userId, stage AS stage, "
                    + "status AS status, current_step AS currentStep, total_steps AS totalSteps, "
                    + "cancel_reason AS cancelReason, renewal_pending AS renewalPending, "
                    + "started_at AS startedAt, completed_at AS completedAt, created_at AS createdAt "
                    + "FROM t_contact_plan";

    private static final String STEP_SQL =
            "SELECT id AS id, plan_id AS planId, step_order AS stepOrder, "
                    + "channel_type AS channelType, template_id AS templateId, status AS status, "
                    + "result AS result, retry_count AS retryCount, trigger_time AS triggerTime, "
                    + "timeout_time AS timeoutTime, executed_at AS executedAt, "
                    + "dispatched_at AS dispatchedAt, completed_at AS completedAt "
                    + "FROM t_contact_plan_step";

    private static final String TIMELINE_SQL =
            "SELECT id AS id, case_id AS caseId, plan_id AS planId, step_id AS stepId, "
                    + "channel AS channel, direction AS direction, template_id AS templateId, "
                    + "script_slot AS scriptSlot, result AS result, "
                    + "provider_msg_id AS providerMsgId, source AS source, created_at AS createdAt "
                    + "FROM t_contact_timeline";

    private static final String CALLBACK_SQL =
            "SELECT id AS id, plan_id AS planId, step_id AS stepId, case_id AS caseId, "
                    + "provider_msg_id AS providerMsgId, result AS result, "
                    + "disposition AS disposition, signature_valid AS signatureValid, "
                    + "received_at AS receivedAt "
                    + "FROM t_channel_callback_audit";

    private static final String DLQ_SQL =
            "SELECT id AS id, event_id AS eventId, event_type AS eventType, "
                    + "failure_reason AS failureReason, delivery_count AS deliveryCount, "
                    + "redrive_count AS redriveCount, status AS status, "
                    + "first_failed_at AS firstFailedAt, last_failed_at AS lastFailedAt, "
                    + "redriven_at AS redrivenAt, terminated_at AS terminatedAt "
                    + "FROM t_event_dlq";
}
