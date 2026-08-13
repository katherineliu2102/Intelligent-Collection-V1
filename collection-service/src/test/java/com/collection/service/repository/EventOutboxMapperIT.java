package com.collection.service.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.collection.common.enums.OutboxStatus;
import com.collection.common.model.OutboxEvent;
import com.collection.service.mapper.EventOutboxMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
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
 * L3 发件箱 Mapper 集成测。
 *
 * <p>验证 {@link EventOutboxMapper} 在真实 MySQL 上的原子认领语义——这是多实例 {@code OutboxPublisher}
 * 正确性的前提，单元测试无法替代。
 *
 * <p>门控与 {@link ContactPlanMapperIT} 相同：仅当 {@code L3_IT_DB_URL} 存在时运行。
 */
@EnabledIfEnvironmentVariable(named = "L3_IT_DB_URL", matches = ".+")
@Tag("integration")
class EventOutboxMapperIT {

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
                        new Environment("l3-it-outbox", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        cfg.setDefaultEnumTypeHandler(EnumTypeHandler.class);
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

    @Test
    void claimForRepublish_onlyOneConcurrentTransactionSucceeds() throws Exception {
        String eventId = "L3-IT-claim:" + System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusSeconds(60);
        seedPendingRow(eventId, now.minusSeconds(5));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first =
                    executor.submit(() -> claimOnce(start, eventId, now, leaseUntil));
            Future<Integer> second =
                    executor.submit(() -> claimOnce(start, eventId, now, leaseUntil));
            start.countDown();

            int claimed = first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS);
            assertEquals(1, claimed, "同一 eventId 并发 claim 必须且只能有一个事务成功");

            try (SqlSession session = factory.openSession(true)) {
                EventOutboxMapper mapper = session.getMapper(EventOutboxMapper.class);
                OutboxEvent row =
                        mapper.selectClaimableForRepublish(now.plusMinutes(5), 10).stream()
                                .filter(r -> eventId.equals(r.getEventId()))
                                .findFirst()
                                .orElse(null);
                // 已认领且租约未到期，不应再出现在可认领列表
                assertTrue(
                        row == null, "PROCESSING 且 lease 未到期时不应被 selectClaimableForRepublish 返回");
            }
        } finally {
            executor.shutdownNow();
            deleteOutbox(eventId);
        }
    }

    @Test
    void claimForRepublish_reclaimsAfterLeaseExpires() {
        String eventId = "L3-IT-lease:" + System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        seedProcessingRow(eventId, now.minusSeconds(10));

        try (SqlSession session = factory.openSession(false)) {
            EventOutboxMapper mapper = session.getMapper(EventOutboxMapper.class);
            assertEquals(
                    1, mapper.claimForRepublish(eventId, now, now.plusSeconds(60)), "租约到期后应允许重新认领");
            session.commit();
        } finally {
            deleteOutbox(eventId);
        }
    }

    @Test
    void insertIgnoreDuplicate_doesNotResetExistingRow() throws Exception {
        String eventId = "L3-IT-dup:" + System.nanoTime();
        seedPendingRow(eventId, LocalDateTime.now().plusMinutes(10));

        try (SqlSession session = factory.openSession(false)) {
            EventOutboxMapper mapper = session.getMapper(EventOutboxMapper.class);
            OutboxEvent duplicate = pendingRow(eventId, LocalDateTime.now());
            duplicate.setRetryCount(99);
            duplicate.setNextRetryAt(LocalDateTime.now().plusHours(1));
            mapper.insertIgnoreDuplicate(duplicate);
            session.commit();
        }

        try (SqlSession session = factory.openSession(true)) {
            try (Connection connection =
                            DriverManager.getConnection(
                                    System.getenv("L3_IT_DB_URL"),
                                    System.getenv("L3_IT_DB_USER"),
                                    System.getenv("L3_IT_DB_PASS"));
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "SELECT retry_count, next_retry_at FROM t_event_outbox WHERE event_id=?")) {
                ps.setString(1, eventId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "seed row missing");
                    assertEquals(0, rs.getInt("retry_count"), "重复入箱不得重置 retry_count");
                    LocalDateTime stored = rs.getTimestamp("next_retry_at").toLocalDateTime();
                    assertTrue(
                            stored.isAfter(LocalDateTime.now().plusMinutes(5)),
                            "重复入箱不得重置 next_retry_at");
                }
            }
        } finally {
            deleteOutbox(eventId);
        }
    }

    private static int claimOnce(
            CountDownLatch start, String eventId, LocalDateTime now, LocalDateTime leaseUntil)
            throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try (SqlSession session = factory.openSession(false)) {
            EventOutboxMapper mapper = session.getMapper(EventOutboxMapper.class);
            int updated = mapper.claimForRepublish(eventId, now, leaseUntil);
            session.commit();
            return updated;
        }
    }

    private static void seedPendingRow(String eventId, LocalDateTime nextRetryAt) {
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(EventOutboxMapper.class)
                    .insertIgnoreDuplicate(pendingRow(eventId, nextRetryAt));
        }
    }

    private static void seedProcessingRow(String eventId, LocalDateTime expiredLease) {
        seedPendingRow(eventId, LocalDateTime.now().minusSeconds(30));
        try (Connection connection =
                DriverManager.getConnection(
                        System.getenv("L3_IT_DB_URL"),
                        System.getenv("L3_IT_DB_USER"),
                        System.getenv("L3_IT_DB_PASS"))) {
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "UPDATE t_event_outbox SET status='PROCESSING', lease_until=? "
                                    + "WHERE event_id=?")) {
                ps.setObject(1, expiredLease);
                ps.setString(2, eventId);
                assertEquals(1, ps.executeUpdate(), "seed PROCESSING row");
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to seed PROCESSING outbox row", e);
        }
    }

    private static OutboxEvent pendingRow(String eventId, LocalDateTime nextRetryAt) {
        OutboxEvent row = new OutboxEvent();
        row.setEventId(eventId);
        row.setEventType("STEP_COMPLETED");
        row.setPlanId(990_000_001L);
        row.setCaseId(990_000_001L);
        row.setPayload(
                "{\"eventId\":\""
                        + eventId
                        + "\",\"eventType\":\"STEP_COMPLETED\",\"payload\":{\"planId\":990000001}}");
        row.setStatus(OutboxStatus.PENDING);
        row.setRetryCount(0);
        row.setNextRetryAt(nextRetryAt);
        return row;
    }

    private static void deleteOutbox(String eventId) {
        try (Connection connection =
                DriverManager.getConnection(
                        System.getenv("L3_IT_DB_URL"),
                        System.getenv("L3_IT_DB_USER"),
                        System.getenv("L3_IT_DB_PASS"))) {
            try (PreparedStatement ps =
                    connection.prepareStatement("DELETE FROM t_event_outbox WHERE event_id=?")) {
                ps.setString(1, eventId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to delete outbox row " + eventId, e);
        }
    }
}
