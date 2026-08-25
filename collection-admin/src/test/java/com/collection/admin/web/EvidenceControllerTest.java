package com.collection.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T3o 观测证据面的读源、关联与 PII 边界。
 *
 * <p>本模块测试不连库，故校验的是发出的 SQL 与出参裁剪。PII 那几条尤其要锁：证据面出参一旦带上 payload 原文或姓名，泄露不会有任何报错，只会安静地发生。
 */
class EvidenceControllerTest {

    private JdbcTemplate jdbcTemplate;
    private EvidenceController controller;
    /** 按 SQL 片段路由的假结果集；默认空表。 */
    private Function<String, List<Map<String, Object>>> rows = sql -> new ArrayList<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // 用自定义默认 answer 而非 when(...) 打桩：JdbcTemplate.queryForList 同时存在 (String, Object...)
        // 与 (String, Class) 两个重载，matcher 形式会静默匹配不上，退回 Mockito 的默认空 List——
        // 表现是断言全空却不报错，排查成本远高于这几行。
        jdbcTemplate =
                mock(
                        JdbcTemplate.class,
                        invocation -> {
                            Object[] args = invocation.getArguments();
                            if ("queryForList".equals(invocation.getMethod().getName())
                                    && args.length > 0
                                    && args[0] instanceof String) {
                                return rows.apply((String) args[0]);
                            }
                            return Answers.RETURNS_DEFAULTS.answer(invocation);
                        });
        ObjectProvider<com.collection.engine.bus.RedisStreamEventBus> bus =
                mock(ObjectProvider.class);
        ObjectProvider<com.collection.ingestion.job.RedisDailyRollDeduplicator> roll =
                mock(ObjectProvider.class);
        when(bus.getIfAvailable()).thenReturn(null);
        when(roll.getIfAvailable()).thenReturn(null);
        controller = new EvidenceController(jdbcTemplate, bus, roll);
    }

    /** 直接读调用记录，避开 Mockito 对 varargs 的匹配歧义。 */
    private String executedSql() {
        StringBuilder sb = new StringBuilder();
        for (Invocation invocation : mockingDetails(jdbcTemplate).getInvocations()) {
            Object[] args = invocation.getArguments();
            if (args.length > 0 && args[0] instanceof String) {
                sb.append((String) args[0]).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void eventLookupJoinsInboxProjectionAndDlq() {
        controller.byEvent("evt-1");

        String sql = executedSql();
        assertThat(sql).contains("FROM t_ai_collection_inbox").contains("WHERE event_id = ?");
        assertThat(sql).contains("FROM t_event_dlq");
        // inbox 的这两列是「投影更没更新 / 内部事件发没发出」的判据，缺任一条都会把 PENDING 误判成没入案。
        assertThat(sql).contains("projection_applied").contains("publish_status");
    }

    @Test
    void caseLookupReadsProjectionNotLegacyCatalog() {
        controller.byCase(92002L, 20);

        String sql = executedSql();
        assertThat(sql).contains("FROM t_ai_collection ");
        assertThat(sql).doesNotContain("FROM t_collection");
    }

    @Test
    void planLookupCoversStepsTimelineAndCallbackAudit() {
        controller.byPlan(771L);

        String sql = executedSql();
        assertThat(sql).contains("FROM t_contact_plan_step");
        assertThat(sql).contains("FROM t_contact_timeline");
        assertThat(sql).contains("FROM t_channel_callback_audit");
    }

    /** payload / 快照 / 渲染结果 / 回调原文都含债务人信息，任何一条进出参都是静默泄露。 */
    @Test
    void neverSelectsRawPayloadOrRenderedContent() {
        controller.byEvent("evt-1");
        controller.byCase(92002L, 20);
        controller.byPlan(771L);

        String sql = executedSql();
        assertThat(sql)
                .doesNotContain("payload")
                .doesNotContain("context_snapshot")
                .doesNotContain("resolved_params")
                .doesNotContain("content_summary")
                .doesNotContain("canonical_payload")
                .doesNotContain("borrower_name")
                .doesNotContain("push_token");
    }

    @Test
    void masksPhoneAndEmailInCaseSummary() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", 92002L);
        row.put("borrowerPhone", "+639171234567");
        row.put("borrowerEmail", "worker@126.com");
        rows =
                sql -> {
                    List<Map<String, Object>> result = new ArrayList<>();
                    if (sql.contains("FROM t_ai_collection ")) {
                        result.add(new LinkedHashMap<>(row));
                    }
                    return result;
                };

        Map<String, Object> response = controller.byCase(92002L, 20);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("case");
        assertThat(summary.get("borrowerPhone")).isEqualTo("+63****567");
        assertThat(summary.get("borrowerEmail")).isEqualTo("w***@126.com");
    }

    /** 一案多计划时，每个计划要挂上自己的步骤——只给计划状态判不出卡在哪一步。 */
    @Test
    void caseLookupAttachesStepsToEachPlan() {
        rows =
                sql -> {
                    List<Map<String, Object>> result = new ArrayList<>();
                    if (sql.contains("FROM t_contact_plan ")) {
                        Map<String, Object> plan = new LinkedHashMap<>();
                        plan.put("id", 771L);
                        result.add(plan);
                    } else if (sql.contains("FROM t_contact_plan_step")) {
                        Map<String, Object> step = new LinkedHashMap<>();
                        step.put("id", 9001L);
                        step.put("status", "SKIPPED");
                        step.put("result", "COMPLIANCE_BLOCKED");
                        result.add(step);
                    }
                    return result;
                };

        Map<String, Object> response = controller.byCase(92002L, 20);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) data.get("plans");
        assertThat(plans).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) plans.get(0).get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).get("result")).isEqualTo("COMPLIANCE_BLOCKED");
    }

    /** 内存总线（local/test）下没有 Redis 证据，应给出说明而不是 500。 */
    @Test
    void redisViewDegradesWhenEventBusIsInMemory() {
        Map<String, Object> response = controller.redis(10, 50);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data).containsKeys("eventBus", "dailyRoll", "dlqByStatus");
        assertThat(data.get("eventBus").toString()).contains("内存总线");
    }
}
