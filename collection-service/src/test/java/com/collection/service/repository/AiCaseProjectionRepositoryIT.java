package com.collection.service.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.collection.common.model.CaseProjection;
import com.collection.common.model.CaseProjectionCommand;
import com.collection.common.repository.CaseProjectionRepository;
import com.collection.common.repository.MissingCaseBaselineException;
import com.collection.service.mapper.AiCollectionInboxMapper;
import com.collection.service.mapper.AiCollectionProjectionMapper;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * L3-8：案件投影的事务语义。
 *
 * <p>被验证的四条性质都在 SQL 与事务边界上，单元测试的内存实现无法替代： 收件箱与投影同事务提交/回滚、{@code FOR UPDATE} 行锁使同案并发串行、相同内容指纹或更旧
 * {@code occurredAt} 被判陈旧、同 {@code eventId} 重入不重写投影。
 *
 * <p>事务边界说明：{@link AiCaseProjectionRepository#apply} 的 {@code @Transactional} 由 Spring 代理提供，本测试不起
 * Spring 容器，改由 {@link SqlSession}（单连接、autoCommit=false）充当同一个事务——这正是 {@code @Transactional} 在运行时委托给
 * JDBC 的那层语义，因此结论可迁移。
 *
 * <p>安全：哨兵 case_id 取 99_008_xxx 段，{@code @AfterEach} 按 case_id / event_id 前缀精确删除，不做 TRUNCATE。
 *
 * <p>门控与 {@link ContactPlanMapperIT} 相同：仅当 {@code L3_IT_DB_URL} 存在时运行。
 */
@EnabledIfEnvironmentVariable(named = "L3_IT_DB_URL", matches = ".+")
@Tag("integration")
class AiCaseProjectionRepositoryIT {

    private static final long CASE = 99_008_001L;
    private static final long LOCK_CASE = 99_008_002L;
    private static final String EVENT_PREFIX = "l3-8-it:";
    private static final String FINGERPRINT_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1";
    private static final String FINGERPRINT_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2";

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
                        new Environment("l3-it-projection", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        cfg.setDefaultEnumTypeHandler(EnumTypeHandler.class);
        cfg.addMapper(AiCollectionProjectionMapper.class);
        cfg.addMapper(AiCollectionInboxMapper.class);
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

    @AfterEach
    void cleanUp() throws Exception {
        execute("DELETE FROM t_ai_collection_inbox WHERE event_id LIKE ?", EVENT_PREFIX + "%");
        execute("DELETE FROM t_ai_collection WHERE case_id IN (?, ?)", CASE, LOCK_CASE);
    }

    /** L3-8：投影与收件箱在同一事务内提交，提交后两行都可见。 */
    @Test
    void projectionAndInbox_commitTogether() throws Exception {
        String eventId = EVENT_PREFIX + "commit:" + System.nanoTime();
        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionRepository repository = repository(session);
            assertEquals(
                    CaseProjectionRepository.Outcome.APPLIED,
                    repository.apply(command(eventId, FINGERPRINT_A, LocalDateTime.now())));
            session.commit();
        }

        assertEquals(FINGERPRINT_A, readProjectionVersion(CASE), "提交后投影行必须可见");
        assertEquals("PENDING", readInboxStatus(eventId), "提交后收件箱行必须可见且待发布");
    }

    /**
     * L3-8：收件箱写入失败时，投影写入不会被语句级错误撤销 —— 必须靠事务回滚兜住。
     *
     * <p>这条用例证明 {@code @Transactional} 是<b>承重</b>的而非装饰性的：MySQL 的语句失败只回滚该语句，
     * 若少了事务回滚，投影会在没有收件箱记录的情况下留存， 之后重投会被版本判定为陈旧，领域事件永久丢失。
     */
    @Test
    void inboxInsertFailure_leavesProjectionOnlyUntilTransactionRollsBack() throws Exception {
        String eventId = EVENT_PREFIX + "atomic:" + System.nanoTime();
        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionRepository repository = repository(session);
            CaseProjectionCommand command = command(eventId, FINGERPRINT_A, LocalDateTime.now());
            command.setPayload("not-json");

            assertThrows(
                    RuntimeException.class,
                    () -> repository.apply(command),
                    "payload 非法 JSON 时收件箱插入必须失败");
            assertEquals(
                    FINGERPRINT_A,
                    session.getMapper(AiCollectionProjectionMapper.class)
                            .selectVersionForUpdate(CASE),
                    "语句级失败不会撤销先前的投影写入，事务回滚才是唯一保障");

            session.rollback();
        }

        assertNull(readProjectionVersion(CASE), "回滚后投影行不得残留");
        assertNull(readInboxStatus(eventId), "回滚后收件箱行不得残留");
    }

    /** L3-8：同 eventId 重入直接返回，既不重写投影也不改收件箱状态。 */
    @Test
    void sameEventId_reentry_doesNotRewriteProjection() throws Exception {
        String eventId = EVENT_PREFIX + "reentry:" + System.nanoTime();
        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionRepository repository = repository(session);
            repository.apply(command(eventId, FINGERPRINT_A, LocalDateTime.now()));
            session.commit();
        }
        LocalDateTime firstSyncedAt = readSyncedAt(CASE);

        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionRepository repository = repository(session);
            CaseProjectionCommand replay = command(eventId, FINGERPRINT_B, LocalDateTime.now());
            assertEquals(
                    CaseProjectionRepository.Outcome.PENDING_PUBLISH,
                    repository.apply(replay),
                    "收件箱仍为 PENDING 时重投应返回补发信号，而不是再写一遍投影");
            session.commit();
        }

        assertEquals(FINGERPRINT_A, readProjectionVersion(CASE), "重投不得改写投影内容指纹");
        assertEquals(firstSyncedAt, readSyncedAt(CASE), "重投不得刷新 synced_at");
    }

    /** L3-8：不同 eventId 但内容指纹相同 → 陈旧，收件箱落 SKIPPED 供审计。 */
    @Test
    void sameFingerprint_fromDifferentEvent_isStaleAndRecordsSkippedInbox() throws Exception {
        String first = EVENT_PREFIX + "fp1:" + System.nanoTime();
        String second = EVENT_PREFIX + "fp2:" + System.nanoTime();
        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionRepository repository = repository(session);
            repository.apply(command(first, FINGERPRINT_A, LocalDateTime.now()));
            session.commit();
        }
        LocalDateTime firstSyncedAt = readSyncedAt(CASE);

        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionRepository repository = repository(session);
            assertEquals(
                    CaseProjectionRepository.Outcome.STALE_VERSION,
                    repository.apply(command(second, FINGERPRINT_A, LocalDateTime.now())),
                    "内容指纹未变的重复快照应判陈旧");
            session.commit();
        }

        assertEquals("SKIPPED", readInboxStatus(second), "陈旧事件应留 SKIPPED 收件箱记录，不触发领域事件");
        assertEquals(firstSyncedAt, readSyncedAt(CASE), "陈旧事件不得刷新投影");
    }

    /** L3-8：新指纹覆盖投影；publishRequired=false（每日校准）只刷投影不排队事件。 */
    @Test
    void newFingerprint_refreshesProjection_andDailyCalibrationSkipsPublish() throws Exception {
        String snapshot = EVENT_PREFIX + "snap:" + System.nanoTime();
        String calibration = EVENT_PREFIX + "cal:" + System.nanoTime();
        try (SqlSession session = factory.openSession(false)) {
            repository(session).apply(command(snapshot, FINGERPRINT_A, LocalDateTime.now()));
            session.commit();
        }

        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionCommand command =
                    command(calibration, FINGERPRINT_B, LocalDateTime.now());
            command.setPublishRequired(false);
            command.getProjection().setDpd(42);
            assertEquals(
                    CaseProjectionRepository.Outcome.APPLIED_WITHOUT_EVENT,
                    repository(session).apply(command),
                    "每日全量校准应刷新投影但不排队领域事件");
            session.commit();
        }

        assertEquals(FINGERPRINT_B, readProjectionVersion(CASE), "新指纹必须覆盖投影");
        assertEquals(42, readProjectionDpd(CASE), "覆盖必须落到业务字段");
        assertEquals("SKIPPED", readInboxStatus(calibration), "不需发布的事件收件箱状态应为 SKIPPED");
    }

    /** L3-8：还款增量的 occurredAt 早于已入库值时判陈旧，不得回退余额。 */
    @Test
    void repaymentDelta_withOlderOccurredAt_isSkipped() throws Exception {
        String baseline = EVENT_PREFIX + "base:" + System.nanoTime();
        String stale = EVENT_PREFIX + "stale:" + System.nanoTime();
        LocalDateTime baselineTime = LocalDateTime.now().withNano(0);
        try (SqlSession session = factory.openSession(false)) {
            repository(session).apply(command(baseline, FINGERPRINT_A, baselineTime));
            session.commit();
        }

        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionCommand delta =
                    command(stale, FINGERPRINT_B, baselineTime.minusMinutes(5));
            delta.setMessageType("repaymentEvent");
            delta.getProjection().setTotalOutstanding(new BigDecimal("1.00"));
            assertEquals(
                    CaseProjectionRepository.Outcome.STALE_VERSION,
                    repository(session).applyRepaymentDelta(delta),
                    "occurredAt 更旧的还款增量必须判陈旧");
            session.commit();
        }

        assertEquals(
                0, new BigDecimal("5000.00").compareTo(readTotalOutstanding(CASE)), "陈旧还款增量不得回退余额");
        assertEquals("SKIPPED", readInboxStatus(stale));
    }

    /**
     * L3-8：还款增量落库后 stage 随之更新；增量未携带 stage 时保持基线；显式 null 时清空。
     *
     * <p>必须用真库验证：显式 {@code null} 能否真正写进 {@code stage} 列，只有真实 MySQL 能证明—— 之前的 {@code COALESCE} 写法在
     * Java 侧看不出问题，却会把 null 当缺省值吞掉。
     */
    @Test
    void repaymentDelta_syncsStageAndDistinguishesAbsentFromExplicitNull() throws Exception {
        String baseline = EVENT_PREFIX + "stagebase:" + System.nanoTime();
        String repayment = EVENT_PREFIX + "stagepay:" + System.nanoTime();
        LocalDateTime baselineTime = LocalDateTime.now().withNano(0);
        try (SqlSession session = factory.openSession(false)) {
            repository(session).apply(command(baseline, FINGERPRINT_A, baselineTime));
            session.commit();
        }
        assertEquals("S2", readProjectionStage(CASE), "前置：基线 stage 应为 S2");

        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionCommand delta =
                    command(repayment, FINGERPRINT_B, baselineTime.plusMinutes(5));
            delta.setMessageType("repaymentEvent");
            delta.getProjection().setStage("S4");
            delta.getProjection().setStagePresent(true);
            delta.getProjection().setTotalOutstanding(new BigDecimal("1200.00"));
            assertEquals(
                    CaseProjectionRepository.Outcome.APPLIED,
                    repository(session).applyRepaymentDelta(delta),
                    "新鲜还款增量应正常合并");
            session.commit();
        }

        assertEquals("S4", readProjectionStage(CASE), "还款增量携带 stage 时应同步");
        assertEquals(
                0,
                new BigDecimal("1200.00").compareTo(readTotalOutstanding(CASE)),
                "余额仍须按增量更新，证明该行确实被这条增量写过");

        // 增量未携带 stage：合并侧不覆盖，基线原样写回，不得把列刷成 NULL
        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionCommand noStage =
                    command(
                            EVENT_PREFIX + "stagenull:" + System.nanoTime(),
                            FINGERPRINT_A,
                            baselineTime.plusMinutes(10));
            noStage.setMessageType("repaymentEvent");
            noStage.getProjection().setStage(null);
            noStage.getProjection().setStagePresent(false);
            noStage.getProjection().setTotalOutstanding(new BigDecimal("900.00"));
            assertEquals(
                    CaseProjectionRepository.Outcome.APPLIED,
                    repository(session).applyRepaymentDelta(noStage));
            session.commit();
        }

        assertEquals("S4", readProjectionStage(CASE), "增量缺 stage 时保持基线，不得写成 NULL");
        assertEquals(
                0,
                new BigDecimal("900.00").compareTo(readTotalOutstanding(CASE)),
                "余额仍按增量更新，证明该行确实被第二条增量写过");

        // 增量显式携带 stage=null（提前还清、下一期在 3 天以上）：必须真的清空列
        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionCommand cleared =
                    command(
                            EVENT_PREFIX + "stageclr:" + System.nanoTime(),
                            FINGERPRINT_B,
                            baselineTime.plusMinutes(15));
            cleared.setMessageType("repaymentEvent");
            cleared.getProjection().setStage(null);
            cleared.getProjection().setStagePresent(true);
            cleared.getProjection().setDpd(-5);
            cleared.getProjection().setOverdueAmount(BigDecimal.ZERO);
            cleared.getProjection().setTotalOutstanding(new BigDecimal("700.00"));
            assertEquals(
                    CaseProjectionRepository.Outcome.APPLIED,
                    repository(session).applyRepaymentDelta(cleared));
            session.commit();
        }

        assertNull(readProjectionStage(CASE), "显式 null 必须写进列，否则提前还清的客户会被 S0 兜底建出计划");
    }

    /** L3-8：无基线时还款增量必须抛可识别异常（由上层判定 poison 并 ACK 告警），不得静默建行。 */
    @Test
    void repaymentDelta_withoutBaseline_throwsMissingCaseBaseline() {
        String eventId = EVENT_PREFIX + "nobase:" + System.nanoTime();
        try (SqlSession session = factory.openSession(false)) {
            CaseProjectionCommand delta = command(eventId, null, LocalDateTime.now());
            delta.setMessageType("repaymentEvent");
            assertThrows(
                    MissingCaseBaselineException.class,
                    () -> repository(session).applyRepaymentDelta(delta));
            session.rollback();
        }
    }

    /** L3-8：markEventPublished 只对 PENDING 生效一次，重复确认不得改写 published_at。 */
    @Test
    void markEventPublished_flipsPendingExactlyOnce() throws Exception {
        String eventId = EVENT_PREFIX + "publish:" + System.nanoTime();
        try (SqlSession session = factory.openSession(true)) {
            CaseProjectionRepository repository = repository(session);
            repository.apply(command(eventId, FINGERPRINT_A, LocalDateTime.now()));
            repository.markEventPublished(eventId);
            assertEquals("PUBLISHED", readInboxStatus(eventId));
            LocalDateTime publishedAt = readPublishedAt(eventId);
            assertNotNull(publishedAt);

            repository.markEventPublished(eventId);
            assertEquals(publishedAt, readPublishedAt(eventId), "重复确认不得改写 published_at");
        }
    }

    /** L3-8：同案件并发写入被 FOR UPDATE 行锁串行化——否则两条事实事件会互相覆盖。 */
    @Test
    void concurrentWritesOnSameCase_areSerializedByRowLock() throws Exception {
        try (SqlSession seed = factory.openSession(true)) {
            CaseProjection projection = projection(LOCK_CASE, FINGERPRINT_A, LocalDateTime.now());
            assertEquals(1, seed.getMapper(AiCollectionProjectionMapper.class).insert(projection));
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = newConnection();
                Connection contender = newConnection();
                PreparedStatement holderLock =
                        holder.prepareStatement(
                                "SELECT case_version FROM t_ai_collection WHERE case_id = ? FOR UPDATE");
                PreparedStatement contenderLock =
                        contender.prepareStatement(
                                "SELECT case_version FROM t_ai_collection WHERE case_id = ? FOR UPDATE")) {
            holder.setAutoCommit(false);
            contender.setAutoCommit(false);
            holderLock.setLong(1, LOCK_CASE);
            contenderLock.setLong(1, LOCK_CASE);
            try (ResultSet held = holderLock.executeQuery()) {
                assertTrue(held.next());
            }

            CountDownLatch attempted = new CountDownLatch(1);
            Future<String> competing =
                    executor.submit(
                            () -> {
                                attempted.countDown();
                                try (ResultSet rs = contenderLock.executeQuery()) {
                                    assertTrue(rs.next());
                                    return rs.getString("case_version");
                                } finally {
                                    contender.rollback();
                                }
                            });

            assertTrue(attempted.await(2, TimeUnit.SECONDS));
            assertThrows(
                    TimeoutException.class,
                    () -> competing.get(300, TimeUnit.MILLISECONDS),
                    "首事务持锁期间，第二个写入者不得进入临界区");
            holder.rollback();
            assertEquals(FINGERPRINT_A, competing.get(5, TimeUnit.SECONDS), "锁释放后第二个写入者应读到同一行");
        } finally {
            executor.shutdownNow();
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 用当前 SqlSession 的 mapper 装配真实 Repository：事务边界即该 session。 */
    private static CaseProjectionRepository repository(SqlSession session) {
        try {
            AiCaseProjectionRepository repository = new AiCaseProjectionRepository();
            inject(
                    repository,
                    "projectionMapper",
                    session.getMapper(AiCollectionProjectionMapper.class));
            inject(repository, "inboxMapper", session.getMapper(AiCollectionInboxMapper.class));
            return repository;
        } catch (Exception e) {
            throw new IllegalStateException("failed to wire AiCaseProjectionRepository", e);
        }
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static CaseProjectionCommand command(
            String eventId, String fingerprint, LocalDateTime occurredAt) {
        CaseProjectionCommand command = new CaseProjectionCommand();
        command.setEventId(eventId);
        command.setMessageType("caseEvent");
        command.setEventType("CASE_SNAPSHOT");
        command.setPayload("{\"eventId\":\"" + eventId + "\"}");
        command.setProjection(projection(CASE, fingerprint, occurredAt));
        return command;
    }

    private static CaseProjection projection(
            Long caseId, String fingerprint, LocalDateTime occurredAt) {
        CaseProjection projection = new CaseProjection();
        projection.setCaseId(caseId);
        projection.setUserId(caseId);
        projection.setCaseVersion(fingerprint);
        projection.setDpd(7);
        projection.setStage("S2");
        projection.setCollectionStatus("IN_COLLECTION");
        projection.setProduct("MOCASA-IT");
        projection.setOverdueAmount(new BigDecimal("5000.00"));
        projection.setTotalOutstanding(new BigDecimal("5000.00"));
        projection.setPenaltyAmount(new BigDecimal("100.00"));
        projection.setRemainingAmount(BigDecimal.ZERO);
        projection.setUpcomingAmount(null);
        projection.setDueDate(LocalDate.now().minusDays(7));
        projection.setBorrowerName("L3-8 IT");
        projection.setBorrowerPhone("+639000000000");
        projection.setBorrowerEmail("l3-8-it@mocasa.test");
        projection.setBorrowerLanguage("en");
        projection.setUpdatedAt(occurredAt == null ? null : occurredAt.withNano(0));
        return projection;
    }

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(
                System.getenv("L3_IT_DB_URL"),
                System.getenv("L3_IT_DB_USER"),
                System.getenv("L3_IT_DB_PASS"));
    }

    private static String readProjectionVersion(long caseId) throws Exception {
        return (String)
                readOne("SELECT case_version FROM t_ai_collection WHERE case_id = ?", caseId);
    }

    private static String readProjectionStage(long caseId) throws Exception {
        return (String) readOne("SELECT stage FROM t_ai_collection WHERE case_id = ?", caseId);
    }

    private static int readProjectionDpd(long caseId) throws Exception {
        return ((Number) readOne("SELECT dpd FROM t_ai_collection WHERE case_id = ?", caseId))
                .intValue();
    }

    private static BigDecimal readTotalOutstanding(long caseId) throws Exception {
        return (BigDecimal)
                readOne("SELECT total_outstanding FROM t_ai_collection WHERE case_id = ?", caseId);
    }

    private static LocalDateTime readSyncedAt(long caseId) throws Exception {
        return toLocalDateTime(
                readOne("SELECT synced_at FROM t_ai_collection WHERE case_id = ?", caseId));
    }

    private static String readInboxStatus(String eventId) throws Exception {
        return (String)
                readOne(
                        "SELECT publish_status FROM t_ai_collection_inbox WHERE event_id = ?",
                        eventId);
    }

    private static LocalDateTime readPublishedAt(String eventId) throws Exception {
        return toLocalDateTime(
                readOne(
                        "SELECT published_at FROM t_ai_collection_inbox WHERE event_id = ?",
                        eventId));
    }

    /** 驱动按配置可能把 DATETIME 映射为 Timestamp 或 LocalDateTime，两种都要吃下。 */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        return ((java.sql.Timestamp) value).toLocalDateTime();
    }

    /** 独立连接读已提交数据；不存在返回 null。 */
    private static Object readOne(String sql, Object param) throws Exception {
        try (Connection connection = newConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    private static void execute(String sql, Object... params) throws Exception {
        try (Connection connection = newConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
