package com.collection.service.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.collection.common.enums.CancelReason;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.DataSource;
import com.collection.common.enums.Direction;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContactRecord;
import com.collection.service.mapper.ContactPlanMapper;
import com.collection.service.mapper.ContactPlanStepMapper;
import com.collection.service.mapper.ContactTimelineMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * L3 落库集成测（草稿，供 collection-service 服务同事并入）。
 *
 * <p>定位：验证 {@link ContactPlanMapper}/{@link ContactPlanStepMapper} 注解 SQL 与真实 {@code
 * ai_collection_db} schema 的列/枚举/类型匹配（对应测试文档 D10/D11/D14/D19/D27）。
 *
 * <p>为何连真实库：本模块无 H2、CI 无 Docker（Testcontainers 不可用），MySQL 特有语法 （{@code FOR UPDATE}、{@code
 * JSON}、{@code ON UPDATE CURRENT_TIMESTAMP}）在 H2 上不可靠。
 *
 * <p>安全：每个用例在**单一 SqlSession 内 insert→select→rollback**，绝不 commit，不污染库。
 *
 * <p>门控：仅当环境变量 {@code L3_IT_DB_URL} 存在时运行；CI/本地无库时自动跳过。
 *
 * <pre>
 *   export L3_IT_DB_URL="jdbc:mysql://HOST:3306/ai_collection_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Manila"
 *   export L3_IT_DB_USER=ai_collection
 *   export L3_IT_DB_PASS=******
 *   mvn -pl collection-service -am -Dtest=ContactPlanMapperIT test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "L3_IT_DB_URL", matches = ".+")
@Tag("integration")
class ContactPlanMapperIT {

    /** 哨兵 case/user id，避免与真实催收数据冲突（且全程 rollback，实际不落库）。 */
    private static final long SENTINEL_CASE = 99_009_999L;

    private static final long SENTINEL_USER = 99_009_999L;
    private static final long LOCK_SENTINEL_CASE = 99_009_990L;

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
                new Configuration(new Environment("l3-it", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        cfg.setDefaultEnumTypeHandler(EnumTypeHandler.class);
        cfg.addMapper(ContactPlanMapper.class);
        cfg.addMapper(ContactPlanStepMapper.class);
        cfg.addMapper(ContactTimelineMapper.class);
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

    private ContactPlan newPlan() {
        return newPlan(SENTINEL_CASE, SENTINEL_USER);
    }

    private ContactPlan newPlan(long caseId, long userId) {
        ContactPlan plan = new ContactPlan();
        plan.setCaseId(caseId);
        plan.setUserId(userId);
        plan.setStage(Stage.S1);
        plan.setStatus(PlanStatus.PENDING);
        plan.setCurrentStep(0);
        plan.setTotalSteps(2);
        plan.setContextSnapshot("{\"it\":true,\"dpd\":2}");
        plan.setIdempotencyKey("l3-it:" + System.nanoTime());
        plan.setRenewalPending(false);
        plan.setVersion(0);
        return plan;
    }

    private ContactPlanStep newStep(long planId, int order, ChannelType channel, long templateId) {
        ContactPlanStep step = new ContactPlanStep();
        step.setPlanId(planId);
        step.setStepOrder(order);
        step.setChannelType(channel);
        step.setTemplateId(templateId);
        step.setDelayMinutes(order == 1 ? 0 : 1);
        step.setStatus(StepStatus.PENDING);
        step.setObservationMinutes(0);
        step.setRetryCount(0);
        step.setIdempotencyKey(planId + ":" + order + ":0");
        return step;
    }

    /** D10/D11：plan + step 插入并按列/枚举正确读回。 */
    @Test
    void planAndSteps_insertThenReadBack_columnsAndEnumsMatch() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);

                ContactPlan plan = newPlan();
                assertEquals(1, planMapper.insert(plan));
                assertNotNull(plan.getId(), "useGeneratedKeys 应回填自增主键");

                stepMapper.insert(newStep(plan.getId(), 1, ChannelType.SMS, 101L));
                stepMapper.insert(newStep(plan.getId(), 2, ChannelType.PUSH, 102L));

                ContactPlan loaded = planMapper.selectById(plan.getId());
                assertNotNull(loaded);
                assertEquals(SENTINEL_CASE, loaded.getCaseId());
                assertEquals(Stage.S1, loaded.getStage());
                assertEquals(PlanStatus.PENDING, loaded.getStatus());
                assertEquals(2, loaded.getTotalSteps());
                // context_snapshot 为 MySQL JSON 列：读回会被规范化（空格/键序可能变化），
                // 故按语义断言而非字节相等（引擎侧按 JSON 解析，不依赖原始格式）。
                assertNotNull(loaded.getContextSnapshot());
                assertTrue(
                        loaded.getContextSnapshot().replaceAll("\\s", "").contains("\"dpd\":2"),
                        "context_snapshot 应保留 dpd=2");

                List<ContactPlanStep> steps = stepMapper.selectByPlan(plan.getId());
                assertEquals(2, steps.size(), "应按 step_order 升序读回两步");
                assertEquals(1, steps.get(0).getStepOrder());
                assertEquals(ChannelType.SMS, steps.get(0).getChannelType());
                assertEquals(ChannelType.PUSH, steps.get(1).getChannelType());
            } finally {
                session.rollback();
            }
        }
    }

    /** D14：计划状态更新到终态 + cancelReason 落列并回读。 */
    @Test
    void updatePlanStatus_toTerminal_persistsCancelReason() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlan plan = newPlan();
                planMapper.insert(plan);

                assertEquals(
                        1,
                        planMapper.updateStatus(
                                plan.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.REPAID));
                assertEquals(1, planMapper.markCompleted(plan.getId()));
                ContactPlan loaded = planMapper.selectById(plan.getId());
                assertEquals(PlanStatus.PLAN_CANCELLED, loaded.getStatus());
                assertEquals(CancelReason.REPAID, loaded.getCancelReason());
                assertTrue(loaded.getStatus().isTerminal());
                assertNotNull(loaded.getCompletedAt(), "终态计划应写 completed_at=NOW()");
            } finally {
                session.rollback();
            }
        }
    }

    /** D19：步骤状态更新（EXECUTING→COMPLETED）落列并回读。 */
    @Test
    void updateStepStatus_persistsStatusAndCompletedAt() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);
                ContactPlan plan = newPlan();
                planMapper.insert(plan);
                ContactPlanStep step = newStep(plan.getId(), 1, ChannelType.SMS, 101L);
                stepMapper.insert(step);

                assertEquals(1, stepMapper.updateStatus(step.getId(), StepStatus.COMPLETED, null));
                ContactPlanStep loaded = stepMapper.selectById(step.getId());
                assertEquals(StepStatus.COMPLETED, loaded.getStatus());
                assertNotNull(loaded.getCompletedAt(), "updateStatus 应写 completed_at=NOW()");
            } finally {
                session.rollback();
            }
        }
    }

    /** D27：selectActiveByCase 只返回非终态计划（同 session 内可见未提交行）。 */
    @Test
    void selectActiveByCase_returnsOnlyNonTerminal() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlan cancelled = newPlan();
                planMapper.insert(cancelled);
                planMapper.updateStatus(
                        cancelled.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
                ContactPlan active = newPlan();
                active.setIdempotencyKey("l3-it-active:" + System.nanoTime());
                planMapper.insert(active);

                List<ContactPlan> activePlans = planMapper.selectActiveByCase(SENTINEL_CASE);
                assertTrue(
                        activePlans.stream().anyMatch(p -> p.getId().equals(active.getId())),
                        "非终态计划应出现在 active 列表");
                assertTrue(
                        activePlans.stream().noneMatch(p -> p.getId().equals(cancelled.getId())),
                        "已取消计划不应出现在 active 列表");
            } finally {
                session.rollback();
            }
        }
    }

    /** L3-1：timeline 的 attempt_key 应在真实 MySQL 中幂等 upsert。 */
    @Test
    void timeline_sameAttemptKey_upsertsSingleRecord() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);
                ContactTimelineMapper timelineMapper =
                        session.getMapper(ContactTimelineMapper.class);
                ContactPlan plan = newPlan();
                planMapper.insert(plan);
                ContactPlanStep step = newStep(plan.getId(), 1, ChannelType.SMS, 101L);
                stepMapper.insert(step);

                String attemptKey = plan.getId() + ":" + step.getId() + ":0";
                ContactRecord initial = newTimelineRecord(plan, step, attemptKey);
                initial.setResult(ContactResult.SENT_NO_RESPONSE);
                assertEquals(1, timelineMapper.insert(initial));

                ContactRecord callback = newTimelineRecord(plan, step, attemptKey);
                callback.setResult(ContactResult.DELIVERED);
                callback.setProviderMsgId("provider-l3-it");
                callback.setProviderCallback("{\"status\":\"delivered\"}");
                assertEquals(
                        2,
                        timelineMapper.insert(callback),
                        "MySQL 的 ON DUPLICATE KEY UPDATE 受影响行数应为 2");

                List<ContactRecord> records = timelineMapper.selectRecentByUser(SENTINEL_USER, 10);
                List<ContactRecord> sameAttempt =
                        records.stream()
                                .filter(record -> attemptKey.equals(record.getAttemptKey()))
                                .collect(java.util.stream.Collectors.toList());
                assertEquals(1, sameAttempt.size(), "uk_attempt_key 应防止重复 timeline 行");
                assertEquals(ContactResult.DELIVERED, sameAttempt.get(0).getResult());
                assertEquals("provider-l3-it", sameAttempt.get(0).getProviderMsgId());
            } finally {
                session.rollback();
            }
        }
    }

    /** L3-4：同一 case + stage 同时只能保留一个活跃计划。 */
    @Test
    void insertActivePlan_sameCaseAndStage_isRejectedByUniqueConstraint() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlan first = newPlan();
                planMapper.insert(first);

                ContactPlan duplicate = newPlan();
                duplicate.setIdempotencyKey("l3-it-duplicate:" + System.nanoTime());
                assertThrows(
                        RuntimeException.class,
                        () -> planMapper.insert(duplicate),
                        "uk_active_stage_key 应拒绝同 case + stage 的第二个活跃计划");
            } finally {
                session.rollback();
            }
        }
    }

    /** L3-5：due/timeout 查询按时间排序、限制数量，并排除终态或重建中的计划。 */
    @Test
    void dueAndTimeoutQueries_filterInactivePlansAndRespectOrderingAndLimit() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlanStepMapper stepMapper = session.getMapper(ContactPlanStepMapper.class);
                LocalDateTime now = LocalDateTime.now();
                ContactPlan active = newPlan();
                planMapper.insert(active);

                ContactPlanStep earlierDue = newStep(active.getId(), 1, ChannelType.SMS, 101L);
                earlierDue.setTriggerTime(now.minusMinutes(2));
                stepMapper.insert(earlierDue);
                ContactPlanStep laterDue = newStep(active.getId(), 2, ChannelType.PUSH, 102L);
                laterDue.setTriggerTime(now.minusMinutes(1));
                stepMapper.insert(laterDue);
                ContactPlanStep timeout = newStep(active.getId(), 3, ChannelType.EMAIL, 103L);
                timeout.setStatus(StepStatus.EXECUTING);
                timeout.setTimeoutTime(now.minusMinutes(1));
                stepMapper.insert(timeout);

                ContactPlan terminal = newPlan();
                terminal.setCaseId(SENTINEL_CASE + 1);
                terminal.setIdempotencyKey("l3-it-terminal:" + System.nanoTime());
                planMapper.insert(terminal);
                planMapper.updateStatus(
                        terminal.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);
                ContactPlanStep terminalDue = newStep(terminal.getId(), 1, ChannelType.SMS, 104L);
                terminalDue.setTriggerTime(now.minusMinutes(3));
                terminalDue.setTimeoutTime(now.minusMinutes(3));
                terminalDue.setStatus(StepStatus.EXECUTING);
                stepMapper.insert(terminalDue);

                ContactPlan renewalPending = newPlan();
                renewalPending.setCaseId(SENTINEL_CASE + 2);
                renewalPending.setIdempotencyKey("l3-it-renewal:" + System.nanoTime());
                planMapper.insert(renewalPending);
                planMapper.markRenewalPending(renewalPending.getId());
                ContactPlanStep pendingDue =
                        newStep(renewalPending.getId(), 1, ChannelType.SMS, 105L);
                pendingDue.setTriggerTime(now.minusMinutes(4));
                pendingDue.setTimeoutTime(now.minusMinutes(4));
                pendingDue.setStatus(StepStatus.EXECUTING);
                stepMapper.insert(pendingDue);

                List<ContactPlanStep> dueSteps = stepMapper.selectDueSteps(now, 1);
                assertEquals(1, dueSteps.size(), "limit 应限制 due 查询结果数");
                assertEquals(
                        earlierDue.getId(), dueSteps.get(0).getId(), "due 步骤应按 trigger_time 升序");
                List<ContactPlanStep> allDueSteps = stepMapper.selectDueSteps(now, 10);
                assertFalse(
                        allDueSteps.stream()
                                .anyMatch(step -> terminalDue.getId().equals(step.getId())),
                        "终态计划的步骤不得被 due 查询返回");
                assertFalse(
                        allDueSteps.stream()
                                .anyMatch(step -> pendingDue.getId().equals(step.getId())),
                        "renewal_pending 计划的步骤不得被 due 查询返回");

                List<ContactPlanStep> timeoutSteps = stepMapper.selectTimeoutSteps(now, 10);
                assertTrue(
                        timeoutSteps.stream()
                                .anyMatch(step -> timeout.getId().equals(step.getId())),
                        "到期 EXECUTING 步骤应被 timeout 查询返回");
                assertFalse(
                        timeoutSteps.stream()
                                .anyMatch(step -> terminalDue.getId().equals(step.getId())),
                        "终态计划的步骤不得被 timeout 查询返回");
                assertFalse(
                        timeoutSteps.stream()
                                .anyMatch(step -> pendingDue.getId().equals(step.getId())),
                        "renewal_pending 计划的步骤不得被 timeout 查询返回");
            } finally {
                session.rollback();
            }
        }
    }

    /** L3-4：REBUILD 先撤销旧活跃键，再创建同 case/stage 的新活跃计划。 */
    @Test
    void rebuildSequence_releasesActiveKeyBeforeCreatingReplacementPlan() {
        try (SqlSession session = factory.openSession(false)) {
            try {
                ContactPlanMapper planMapper = session.getMapper(ContactPlanMapper.class);
                ContactPlan oldPlan = newPlan(SENTINEL_CASE + 10, SENTINEL_USER + 10);
                planMapper.insert(oldPlan);

                assertEquals(1, planMapper.markRenewalPending(oldPlan.getId()));
                assertEquals(
                        null,
                        planMapper.selectActiveByCaseAndStage(oldPlan.getCaseId(), Stage.S1),
                        "renewal_pending 旧计划不得继续占用 active_stage_key");

                ContactPlan replacement = newPlan(oldPlan.getCaseId(), oldPlan.getUserId());
                replacement.setIdempotencyKey("l3-it-rebuild:" + System.nanoTime());
                planMapper.insert(replacement);
                assertEquals(
                        1,
                        planMapper.updateStatus(oldPlan.getId(), PlanStatus.PLAN_COMPLETED, null));

                ContactPlan active =
                        planMapper.selectActiveByCaseAndStage(oldPlan.getCaseId(), Stage.S1);
                assertNotNull(active);
                assertEquals(replacement.getId(), active.getId());
            } finally {
                session.rollback();
            }
        }
    }

    /**
     * L3-4：已提交的行被一个事务 FOR UPDATE 锁定时，第二连接必须等到锁释放后才可读取。
     *
     * <p>跨连接可见性要求 seed 提交；finally 按专用 caseId 删除 seed，避免共享测试库残留。
     */
    @Test
    void selectForUpdate_secondConnectionWaitsUntilFirstTransactionReleasesLock() throws Exception {
        deletePlansForCase(LOCK_SENTINEL_CASE);
        Long planId = null;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (SqlSession seedSession = factory.openSession(true)) {
                ContactPlanMapper mapper = seedSession.getMapper(ContactPlanMapper.class);
                ContactPlan seed = newPlan(LOCK_SENTINEL_CASE, LOCK_SENTINEL_CASE);
                mapper.insert(seed);
                planId = seed.getId();
            }

            try (Connection firstConnection =
                            DriverManager.getConnection(
                                    System.getenv("L3_IT_DB_URL"),
                                    System.getenv("L3_IT_DB_USER"),
                                    System.getenv("L3_IT_DB_PASS"));
                    Connection secondConnection =
                            DriverManager.getConnection(
                                    System.getenv("L3_IT_DB_URL"),
                                    System.getenv("L3_IT_DB_USER"),
                                    System.getenv("L3_IT_DB_PASS"));
                    PreparedStatement firstLockQuery =
                            firstConnection.prepareStatement(
                                    "SELECT id FROM t_contact_plan WHERE id = ? FOR UPDATE");
                    PreparedStatement lockQuery =
                            secondConnection.prepareStatement(
                                    "SELECT id FROM t_contact_plan WHERE id = ? FOR UPDATE")) {
                firstConnection.setAutoCommit(false);
                secondConnection.setAutoCommit(false);
                firstLockQuery.setLong(1, planId);
                lockQuery.setLong(1, planId);
                try (ResultSet firstResult = firstLockQuery.executeQuery()) {
                    assertTrue(firstResult.next());
                }
                CountDownLatch secondLockAttempted = new CountDownLatch(1);
                Future<Long> competingLock =
                        executor.submit(
                                () -> {
                                    secondLockAttempted.countDown();
                                    try (ResultSet result = lockQuery.executeQuery()) {
                                        assertTrue(result.next());
                                        return result.getLong("id");
                                    } finally {
                                        secondConnection.rollback();
                                    }
                                });

                assertTrue(secondLockAttempted.await(2, TimeUnit.SECONDS));
                assertThrows(
                        java.util.concurrent.TimeoutException.class,
                        () -> competingLock.get(300, TimeUnit.MILLISECONDS),
                        "第二连接在首事务持锁时不得提前返回");
                firstConnection.rollback();

                Long acquiredPlanId = competingLock.get(5, TimeUnit.SECONDS);
                assertEquals(planId, acquiredPlanId, "首事务释放后第二连接应取得同一计划行锁");
            }
        } finally {
            executor.shutdownNow();
            deletePlansForCase(LOCK_SENTINEL_CASE);
        }
    }

    private void deletePlansForCase(long caseId) throws Exception {
        try (SqlSession cleanup = factory.openSession(true)) {
            try (PreparedStatement deleteSteps =
                            cleanup.getConnection()
                                    .prepareStatement(
                                            "DELETE s FROM t_contact_plan_step s "
                                                    + "JOIN t_contact_plan p ON p.id = s.plan_id "
                                                    + "WHERE p.case_id = ?");
                    PreparedStatement deletePlans =
                            cleanup.getConnection()
                                    .prepareStatement(
                                            "DELETE FROM t_contact_plan WHERE case_id = ?")) {
                deleteSteps.setLong(1, caseId);
                deleteSteps.executeUpdate();
                deletePlans.setLong(1, caseId);
                deletePlans.executeUpdate();
            }
        }
    }

    private ContactRecord newTimelineRecord(
            ContactPlan plan, ContactPlanStep step, String attemptKey) {
        ContactRecord record = new ContactRecord();
        record.setCaseId(plan.getCaseId());
        record.setUserId(plan.getUserId());
        record.setPlanId(plan.getId());
        record.setStepId(step.getId());
        record.setAttemptKey(attemptKey);
        record.setChannel(step.getChannelType());
        record.setDirection(Direction.OUT);
        record.setTemplateId(step.getTemplateId());
        record.setContentSummary("L3 mapper integration test");
        record.setCost(BigDecimal.ZERO);
        record.setSource(DataSource.SYSTEM);
        return record;
    }
}
