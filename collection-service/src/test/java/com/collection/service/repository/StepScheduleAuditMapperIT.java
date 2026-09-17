package com.collection.service.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.OutboxStatus;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.OutboxEvent;
import com.collection.service.mapper.ContactPlanMapper;
import com.collection.service.mapper.ContactPlanStepMapper;
import com.collection.service.mapper.EventOutboxMapper;
import com.collection.service.support.ServiceClock;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.EnumTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * L3-7：排期审计列与停摆判定 SQL。
 *
 * <p>为何必须连真实库：两条被验证的性质都只存在于 SQL 里而不在 Java 里—— {@code original_trigger_time} 的「只写一次」靠 INSERT
 * 列表里不出现该列来保证， {@code dispatched_at} 的「只记首次」靠 {@code COALESCE} 保证；停摆判定则是一条带两个 {@code NOT EXISTS}
 * 的相关子查询， 它与 {@code selectDueSteps} / {@code selectTimeoutSteps} 的互补性只能靠同一批数据同时喂给三条 SQL 才能证明。
 *
 * <p>安全：全部用例在单一 SqlSession 内 insert→select→rollback，绝不 commit；哨兵 case_id 取 99_007_xxx 段。
 *
 * <p>门控与 {@link ContactPlanMapperIT} 相同：仅当 {@code L3_IT_DB_URL} 存在时运行。
 */
@EnabledIfEnvironmentVariable(named = "L3_IT_DB_URL", matches = ".+")
@Tag("integration")
class StepScheduleAuditMapperIT {

    /** 停摆查询是全表扫描，limit 必须大到能覆盖库内既有计划，否则 ORDER BY id ASC 会把新造的行挤出结果。 */
    private static final int SCAN_LIMIT = 100_000;

    private static final long AUDIT_CASE = 99_007_001L;
    private static final long STUCK_CASE = 99_007_010L;
    private static final long FUTURE_DUE_CASE = 99_007_011L;
    private static final long PENDING_TIMEOUT_CASE = 99_007_012L;
    private static final long ALL_DONE_CASE = 99_007_013L;
    private static final long OUTBOX_PENDING_CASE = 99_007_014L;
    private static final long RENEWAL_PENDING_CASE = 99_007_015L;
    private static final long RESURRECT_CASE = 99_007_020L;
    private static final long FILTER_CASE = 99_007_030L;
    private static final long TIMEZONE_CASE = 99_007_030L;
    private static final long TIMEZONE_TOLERANCE_SECONDS = 300L;

    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() {
        PooledDataSource ds =
                new PooledDataSource(
                        "com.mysql.cj.jdbc.Driver",
                        System.getenv("L3_IT_DB_URL"),
                        System.getenv("L3_IT_DB_USER"),
                        System.getenv("L3_IT_DB_PASS"));
        Configuration cfg =
                new Configuration(
                        new Environment("l3-it-schedule", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        cfg.setDefaultEnumTypeHandler(EnumTypeHandler.class);
        cfg.addMapper(ContactPlanMapper.class);
        cfg.addMapper(ContactPlanStepMapper.class);
        cfg.addMapper(EventOutboxMapper.class);
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            javax.sql.DataSource ds = factory.getConfiguration().getEnvironment().getDataSource();
            if (ds instanceof PooledDataSource) {
                ((PooledDataSource) ds).forceCloseAll();
            }
        }
    }

    /** L3-7：退避改写 / 扫描清空 / 超时改写都不得动 original_trigger_time——偏差分析的基准线。 */
    @Test
    void originalTriggerTime_survivesBackoffPickupAndTimeoutRewrites() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);

                LocalDateTime scheduled = seconds(LocalDateTime.now().plusMinutes(5));
                ContactPlan plan = insertPlan(planMapper, AUDIT_CASE);
                ContactPlanStep step = newStep(plan.getId(), 1, ChannelType.SMS, 101L);
                step.setTriggerTime(scheduled);
                stepMapper.insert(step);

                assertEquals(
                        scheduled,
                        stepMapper.selectById(step.getId()).getOriginalTriggerTime(),
                        "插入时 original_trigger_time 应等于首次排期");

                LocalDateTime backoff = seconds(LocalDateTime.now().plusMinutes(35));
                assertEquals(
                        1,
                        stepMapper.updateTriggerTime(
                                step.getId(), backoff, StepStatus.PENDING, ServiceClock.now()),
                        "退避应改写 trigger_time");
                ContactPlanStep afterBackoff = stepMapper.selectById(step.getId());
                assertEquals(backoff, afterBackoff.getTriggerTime());
                assertEquals(
                        scheduled,
                        afterBackoff.getOriginalTriggerTime(),
                        "退避改写 trigger_time 后 original_trigger_time 必须保持首次排期");

                assertEquals(1, stepMapper.markExecuting(step.getId(), ServiceClock.now()));
                ContactPlanStep afterPickup = stepMapper.selectById(step.getId());
                assertNull(afterPickup.getTriggerTime(), "被扫描拾取后 trigger_time 应清空");
                assertEquals(
                        scheduled,
                        afterPickup.getOriginalTriggerTime(),
                        "清空 trigger_time 不得连带清空 original_trigger_time");

                assertEquals(
                        1,
                        stepMapper.updateTimeoutTime(
                                step.getId(),
                                seconds(LocalDateTime.now().plusMinutes(10)),
                                ServiceClock.now()));
                assertEquals(
                        scheduled,
                        stepMapper.selectById(step.getId()).getOriginalTriggerTime(),
                        "改写 timeout_time 不得触碰 original_trigger_time");
            } finally {
                session.rollback();
            }
        }
    }

    /** L3-7：dispatched_at 只记首次受理，重试复用同一行时不得被后来的写入时间覆盖。 */
    @Test
    void dispatchedAt_recordsFirstAcceptanceOnly() throws Exception {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);
                ContactPlan plan = insertPlan(planMapper, AUDIT_CASE + 1);
                ContactPlanStep step = newStep(plan.getId(), 1, ChannelType.SMS, 101L);
                stepMapper.insert(step);

                assertNull(stepMapper.selectById(step.getId()).getDispatchedAt());
                assertEquals(1, stepMapper.markDispatched(step.getId(), ServiceClock.now()));
                LocalDateTime first = stepMapper.selectById(step.getId()).getDispatchedAt();
                assertNotNull(first, "首次受理应写入 dispatched_at");

                // 写入时间只到秒，同一秒内的二次调用无法区分「保留」与「覆盖」；
                // 先把首次受理时间推到过去，再重试，才能真正证伪覆盖行为。
                LocalDateTime backdated = seconds(LocalDateTime.now().minusMinutes(7));
                backdateDispatchedAt(session, step.getId(), backdated);
                assertEquals(1, stepMapper.markDispatched(step.getId(), ServiceClock.now()));
                assertEquals(
                        backdated,
                        stepMapper.selectById(step.getId()).getDispatchedAt(),
                        "重试不得把 dispatched_at 刷成本次时间，否则真实发出时间丢失");
            } finally {
                session.rollback();
            }
        }
    }

    /**
     * L3-7c：时间列必须落 PHT，且同一行内不得出现两套时区。
     *
     * <p>这是「时区口径」缺口的回归守卫（SSOT 附录 C）：故障形态是 {@code created_at}/{@code updated_at} 落 Manila 而 {@code
     * executed_at} 落 UTC，恒差 8 小时，使当日频控漏计、停摆宽限恒被满足。改为应用侧传参后， 三列都应贴近应用 PHT 时钟；容差取 5 分钟，只为吸收执行耗时，8
     * 小时的时区错位必然越界。
     */
    @Test
    void timeColumns_landOnPhtNotDatabaseSessionTimeZone() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);

                ContactPlan plan = insertPlan(planMapper, TIMEZONE_CASE);
                ContactPlanStep step = newStep(plan.getId(), 1, ChannelType.SMS, 101L);
                stepMapper.insert(step);
                assertEquals(1, stepMapper.markExecuting(step.getId(), ServiceClock.now()));

                ContactPlanStep loaded = stepMapper.selectById(step.getId());
                assertClose("created_at", loaded.getCreatedAt());
                assertClose("updated_at", loaded.getUpdatedAt());
                assertClose("executed_at", loaded.getExecutedAt());
                assertClose("plan.updated_at", planMapper.selectById(plan.getId()).getUpdatedAt());
            } finally {
                session.rollback();
            }
        }
    }

    private static void assertClose(String column, LocalDateTime actual) {
        assertNotNull(actual, column + " 不应为空");
        long driftSeconds =
                Math.abs(java.time.Duration.between(actual, ServiceClock.now()).getSeconds());
        assertTrue(
                driftSeconds <= TIMEZONE_TOLERANCE_SECONDS,
                column
                        + " 偏离应用 PHT 时钟 "
                        + driftSeconds
                        + "s（容差 "
                        + TIMEZONE_TOLERANCE_SECONDS
                        + "s）：该列疑似仍由库端会话时区写入");
    }

    /**
     * L3-7b：{@code markExecuting} 的终态谓词是承重的，必须在真库上验证。
     *
     * <p>该谓词是引擎防「重复投递复活已终结步骤」的唯一原子屏障：调用方的先读后写不具原子性（终态写入发生在
     * 计划行锁之外）。谓词一旦在旧库映射改写中丢失，仓储会无条件返回「抢到了」，引擎的保护静默消失， 且不会有任何编译错误或单测失败——所以这条断言只能落在真 SQL 上。
     */
    @Test
    void markExecuting_refusesToResurrectTerminalStep() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);

                StepStatus[] terminals = {
                    StepStatus.COMPLETED, StepStatus.SKIPPED, StepStatus.FAILED
                };
                for (int i = 0; i < terminals.length; i++) {
                    StepStatus terminal = terminals[i];
                    // uk_active_stage_key(case_id, stage) 限制同案件同阶段只有一个活跃计划，故逐个换案件号
                    ContactPlan plan = insertPlan(planMapper, RESURRECT_CASE + i);
                    ContactPlanStep step = newStep(plan.getId(), 1, ChannelType.SMS, 101L);
                    LocalDateTime scheduled = seconds(LocalDateTime.now().plusMinutes(5));
                    step.setTriggerTime(scheduled);
                    stepMapper.insert(step);
                    assertEquals(
                            1,
                            stepMapper.updateStatus(
                                    step.getId(),
                                    terminal,
                                    ContactResult.DELIVERED,
                                    ServiceClock.now()),
                            "前置：把步骤置为 " + terminal);

                    assertEquals(
                            0,
                            stepMapper.markExecuting(step.getId(), ServiceClock.now()),
                            terminal + " 的步骤不得被重新抢占为 EXECUTING");
                    ContactPlanStep after = stepMapper.selectById(step.getId());
                    assertEquals(terminal, after.getStatus(), terminal + " 状态必须保持");
                    assertEquals(
                            scheduled,
                            after.getTriggerTime(),
                            "抢占失败不得清空 trigger_time——清空即造成两个扫描都摸不到的悬挂");
                }

                // 非终态仍必须抢得到：退避重试会复用同一行，谓词不能收得过紧。
                ContactPlan livePlan = insertPlan(planMapper, RESURRECT_CASE + terminals.length);
                StepStatus[] lives = {StepStatus.PENDING, StepStatus.EXECUTING};
                for (int i = 0; i < lives.length; i++) {
                    StepStatus live = lives[i];
                    ContactPlanStep step = newStep(livePlan.getId(), i + 1, ChannelType.SMS, 101L);
                    stepMapper.insert(step);
                    assertEquals(
                            1,
                            stepMapper.updateStatus(step.getId(), live, null, ServiceClock.now()),
                            "前置：把步骤置为 " + live);
                    assertEquals(
                            1,
                            stepMapper.markExecuting(step.getId(), ServiceClock.now()),
                            live + " 的步骤必须能被抢占");
                }
            } finally {
                session.rollback();
            }
        }
    }

    /**
     * L3-7c：扫描的案件过滤是承重的，必须在真库上验证。
     *
     * <p>扫描 SQL 没有任何租户维度，「连上哪个库」等于「有权对该库全部案件发起触达」。2026-08-21 实测共享库上
     * 存在外来实例抢走本机测试步骤（本机应用停机期间插入的到期步骤，2 分钟内被改写为 EXECUTING）。
     * 过滤一旦丢失不会有任何编译错误或单测失败，只会静默恢复全库扫描，故守护断言只能落在真 SQL 上。
     */
    @Test
    void dueAndTimeoutScans_honourCaseIdFilter() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);

                ContactPlan mine = insertPlan(planMapper, FILTER_CASE);
                ContactPlanStep myStep = newStep(mine.getId(), 1, ChannelType.SMS, 101L);
                myStep.setTriggerTime(seconds(LocalDateTime.now().minusMinutes(5)));
                stepMapper.insert(myStep);

                ContactPlan foreign = insertPlan(planMapper, FILTER_CASE + 1);
                ContactPlanStep foreignStep = newStep(foreign.getId(), 1, ChannelType.SMS, 101L);
                foreignStep.setTriggerTime(seconds(LocalDateTime.now().minusMinutes(5)));
                stepMapper.insert(foreignStep);
                assertEquals(1, stepMapper.markExecuting(foreignStep.getId(), ServiceClock.now()));
                assertEquals(
                        1,
                        stepMapper.updateTimeoutTime(
                                foreignStep.getId(),
                                seconds(LocalDateTime.now().minusMinutes(1)),
                                ServiceClock.now()));

                LocalDateTime now = seconds(LocalDateTime.now().plusSeconds(1));
                List<Long> onlyMine = Collections.singletonList(FILTER_CASE);

                List<ContactPlanStep> due =
                        stepMapper.selectDueSteps(now, SCAN_LIMIT, onlyMine, null);
                assertTrue(containsStep(due, myStep.getId()), "名单内案件的到期步骤必须被扫到");
                assertFalse(containsStep(due, foreignStep.getId()), "名单外案件的步骤绝不能出现在扫描结果里");

                // 超时扫描同样受约束：名单外案件已到超时，仍不得被本实例拾取。
                // 复用同一计划的第二步——uk_active_stage_key 不允许同案件同阶段再建活跃计划。
                ContactPlanStep myTimeoutStep = newStep(mine.getId(), 2, ChannelType.SMS, 101L);
                stepMapper.insert(myTimeoutStep);
                assertEquals(
                        1, stepMapper.markExecuting(myTimeoutStep.getId(), ServiceClock.now()));
                assertEquals(
                        1,
                        stepMapper.updateTimeoutTime(
                                myTimeoutStep.getId(),
                                seconds(LocalDateTime.now().minusMinutes(1)),
                                ServiceClock.now()));

                List<ContactPlanStep> timeout =
                        stepMapper.selectTimeoutSteps(now, SCAN_LIMIT, onlyMine, null);
                assertTrue(containsStep(timeout, myTimeoutStep.getId()), "名单内案件的超时步骤必须被扫到");
                assertFalse(containsStep(timeout, foreignStep.getId()), "名单外案件的超时步骤绝不能出现在扫描结果里");

                // null 名单 = 不过滤，两者都应出现（生产单实例语义，不可因加了过滤而丢步骤）
                assertTrue(
                        containsStep(
                                stepMapper.selectDueSteps(now, SCAN_LIMIT, null, null),
                                myStep.getId()),
                        "null 名单必须退化为不过滤");
                assertTrue(
                        containsStep(
                                stepMapper.selectTimeoutSteps(now, SCAN_LIMIT, null, null),
                                foreignStep.getId()),
                        "null 名单下名单外案件也应被扫到");
            } finally {
                session.rollback();
            }
        }
    }

    /**
     * L3-7：停摆查询与 due/timeout 扫描严格互补。
     *
     * <p>互补的判据不是「此刻捞不到」，而是「今后再也捞不到」：未来到期与未到超时的计划都还有驱动源， 因此不算停摆；只有既无 trigger_time 也无 timeout_time
     * 可被拾取、且发件箱无在途事件的非终态计划才算。
     */
    @Test
    void stuckPlanQuery_isStrictComplementOfDueAndTimeoutScans() throws Exception {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);
                EventOutboxMapper outboxMapper = session.getMapper(EventOutboxMapper.class);
                // 参照系取应用 PHT 时钟：updated_at 已改由应用侧按 Asia/Manila 传入（ServiceClock），
                // 不再随库端会话时区漂移（历史故障见 SSOT 附录 C 的时区口径条目）。
                LocalDateTime now = ServiceClock.now();

                // 停摆：EXECUTING 但 trigger_time 与 timeout_time 都为空 —— 两条扫描都拾不到
                ContactPlan orphaned = insertPlan(planMapper, STUCK_CASE);
                ContactPlanStep orphanedStep = newStep(orphaned.getId(), 1, ChannelType.SMS, 101L);
                orphanedStep.setStatus(StepStatus.EXECUTING);
                stepMapper.insert(orphanedStep);

                // 未来到期：此刻 due 捞不到，但将来会捞到 → 不算停摆
                ContactPlan futureDue = insertPlan(planMapper, FUTURE_DUE_CASE);
                ContactPlanStep futureStep = newStep(futureDue.getId(), 1, ChannelType.SMS, 101L);
                futureStep.setTriggerTime(now.plusMinutes(30));
                stepMapper.insert(futureStep);

                // 未到超时：timeout 扫描将来会捞到 → 不算停摆
                ContactPlan awaitingCallback = insertPlan(planMapper, PENDING_TIMEOUT_CASE);
                ContactPlanStep awaitingStep =
                        newStep(awaitingCallback.getId(), 1, ChannelType.SMS, 101L);
                awaitingStep.setStatus(StepStatus.EXECUTING);
                awaitingStep.setTimeoutTime(now.plusMinutes(30));
                stepMapper.insert(awaitingStep);

                // 步骤全部终态但计划仍非终态 → 停摆
                ContactPlan allDone = insertPlan(planMapper, ALL_DONE_CASE);
                ContactPlanStep doneStep = newStep(allDone.getId(), 1, ChannelType.SMS, 101L);
                stepMapper.insert(doneStep);
                stepMapper.updateStatus(
                        doneStep.getId(), StepStatus.COMPLETED, null, ServiceClock.now());

                // 无可拾取步骤，但发件箱还有在途事件 → 事件补发仍会推进，不算停摆
                ContactPlan outboxPending = insertPlan(planMapper, OUTBOX_PENDING_CASE);
                ContactPlanStep outboxStep =
                        newStep(outboxPending.getId(), 1, ChannelType.SMS, 101L);
                outboxStep.setStatus(StepStatus.EXECUTING);
                stepMapper.insert(outboxStep);
                outboxMapper.insertIgnoreDuplicate(
                        pendingOutbox(outboxPending.getId(), OUTBOX_PENDING_CASE));

                // 正在重建 → 由重建流程负责，不算停摆
                ContactPlan rebuilding = insertPlan(planMapper, RENEWAL_PENDING_CASE);
                ContactPlanStep rebuildingStep =
                        newStep(rebuilding.getId(), 1, ChannelType.SMS, 101L);
                rebuildingStep.setStatus(StepStatus.EXECUTING);
                stepMapper.insert(rebuildingStep);
                planMapper.markRenewalPending(rebuilding.getId(), ServiceClock.now());

                List<Long> stuck = planMapper.selectStuckPlanIds(now.plusMinutes(1), SCAN_LIMIT);
                assertTrue(
                        stuck.contains(orphaned.getId()),
                        "无 trigger_time 无 timeout_time 的非终态计划应判停摆");
                assertTrue(stuck.contains(allDone.getId()), "步骤已全部终态但计划未收尾应判停摆");
                assertFalse(stuck.contains(futureDue.getId()), "未来到期的计划仍有驱动源，不得判停摆");
                assertFalse(stuck.contains(awaitingCallback.getId()), "等待回调超时的计划仍有驱动源，不得判停摆");
                assertFalse(stuck.contains(outboxPending.getId()), "发件箱有在途事件的计划仍会被补发推进，不得判停摆");
                assertFalse(stuck.contains(rebuilding.getId()), "renewal_pending 计划由重建流程负责，不得判停摆");

                // 反向确认互补：被判停摆的计划，其步骤在两条扫描里都取不到
                List<ContactPlanStep> due =
                        stepMapper.selectDueSteps(now.plusYears(1), SCAN_LIMIT, null, null);
                List<ContactPlanStep> timeout =
                        stepMapper.selectTimeoutSteps(now.plusYears(1), SCAN_LIMIT, null, null);
                assertFalse(containsStep(due, orphanedStep.getId()), "停摆计划的步骤不应出现在 due 扫描");
                assertFalse(containsStep(timeout, orphanedStep.getId()), "停摆计划的步骤不应出现在 timeout 扫描");
                assertFalse(containsStep(due, doneStep.getId()));
                assertFalse(containsStep(timeout, doneStep.getId()));
                assertTrue(
                        containsStep(due, futureStep.getId()), "非停摆计划的步骤必须能被将来的 due 扫描拾取，否则互补性不成立");
                assertTrue(
                        containsStep(timeout, awaitingStep.getId()),
                        "非停摆计划的步骤必须能被将来的 timeout 扫描拾取");

                // 空闲宽限：刚更新过的计划不得被判停摆，否则会与正在执行的流程赛跑
                assertFalse(
                        planMapper
                                .selectStuckPlanIds(now.minusMinutes(30), SCAN_LIMIT)
                                .contains(orphaned.getId()),
                        "updated_at 晚于 idleBefore 的计划应被宽限窗口排除");
            } finally {
                session.rollback();
            }
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private static boolean containsStep(List<ContactPlanStep> steps, Long stepId) {
        return steps.stream().anyMatch(step -> stepId.equals(step.getId()));
    }

    /** DATETIME 列无小数秒，输入先截到秒，避免回读比较抖动。 */
    private static LocalDateTime seconds(LocalDateTime time) {
        return time.withNano(0);
    }

    private static void backdateDispatchedAt(SqlSession session, Long stepId, LocalDateTime value)
            throws Exception {
        try (PreparedStatement ps =
                session.getConnection()
                        .prepareStatement(
                                "UPDATE t_contact_plan_step SET dispatched_at = ? WHERE id = ?")) {
            ps.setObject(1, value);
            ps.setLong(2, stepId);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static ContactPlan insertPlan(ContactPlanMapper mapper, long caseId) {
        ContactPlan plan = new ContactPlan();
        plan.setCaseId(caseId);
        plan.setUserId(caseId);
        plan.setStage(Stage.S1);
        plan.setStatus(PlanStatus.PENDING);
        plan.setCurrentStep(0);
        plan.setTotalSteps(1);
        plan.setContextSnapshot("{\"it\":\"l3-7\"}");
        plan.setIdempotencyKey("l3-7-it:" + caseId + ":" + System.nanoTime());
        plan.setRenewalPending(false);
        plan.setVersion(0);
        plan.setCreatedAt(ServiceClock.now());
        plan.setUpdatedAt(ServiceClock.now());
        assertEquals(1, mapper.insert(plan));
        assertNotNull(plan.getId());
        return plan;
    }

    private static ContactPlanStep newStep(
            long planId, int order, ChannelType channel, long templateId) {
        ContactPlanStep step = new ContactPlanStep();
        step.setPlanId(planId);
        step.setStepOrder(order);
        step.setChannelType(channel);
        step.setTemplateId(templateId);
        step.setDelayMinutes(0);
        step.setStatus(StepStatus.PENDING);
        step.setObservationMinutes(0);
        step.setRetryCount(0);
        step.setIdempotencyKey(planId + ":" + order + ":0");
        step.setCreatedAt(ServiceClock.now());
        step.setUpdatedAt(ServiceClock.now());
        return step;
    }

    private static OutboxEvent pendingOutbox(Long planId, Long caseId) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId("l3-7-it:" + System.nanoTime());
        event.setEventType("STEP_COMPLETED");
        event.setPlanId(planId);
        event.setCaseId(caseId);
        event.setPayload(
                "{\"eventType\":\"STEP_COMPLETED\",\"payload\":{\"planId\":" + planId + "}}");
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        return event;
    }
}
