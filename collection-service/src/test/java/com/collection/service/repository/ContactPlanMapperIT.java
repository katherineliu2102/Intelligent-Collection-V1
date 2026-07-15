package com.collection.service.repository;

import com.collection.common.enums.CancelReason;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.service.mapper.ContactPlanMapper;
import com.collection.service.mapper.ContactPlanStepMapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 落库集成测（草稿，供 collection-service 服务同事并入）。
 *
 * <p>定位：验证 {@link ContactPlanMapper}/{@link ContactPlanStepMapper} 注解 SQL 与真实
 * {@code ai_collection_db} schema 的列/枚举/类型匹配（对应测试文档 D10/D11/D14/D19/D27）。
 *
 * <p>为何连真实库：本模块无 H2、CI 无 Docker（Testcontainers 不可用），MySQL 特有语法
 * （{@code FOR UPDATE}、{@code JSON}、{@code ON UPDATE CURRENT_TIMESTAMP}）在 H2 上不可靠。
 *
 * <p>安全：每个用例在**单一 SqlSession 内 insert→select→rollback**，绝不 commit，不污染库。
 *
 * <p>门控：仅当环境变量 {@code L3_IT_DB_URL} 存在时运行；CI/本地无库时自动跳过。
 * <pre>
 *   export L3_IT_DB_URL="jdbc:mysql://HOST:3306/ai_collection_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Manila"
 *   export L3_IT_DB_USER=ai_collection
 *   export L3_IT_DB_PASS=******
 *   mvn -pl collection-service -am -Dtest=ContactPlanMapperIT test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "L3_IT_DB_URL", matches = ".+")
class ContactPlanMapperIT {

    /** 哨兵 case/user id，避免与真实催收数据冲突（且全程 rollback，实际不落库）。 */
    private static final long SENTINEL_CASE = 99_009_999L;
    private static final long SENTINEL_USER = 99_009_999L;

    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() {
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                System.getenv("L3_IT_DB_URL"),
                System.getenv("L3_IT_DB_USER"),
                System.getenv("L3_IT_DB_PASS"));
        Configuration cfg = new Configuration(new Environment("l3-it", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        cfg.setDefaultEnumTypeHandler(EnumTypeHandler.class);
        cfg.addMapper(ContactPlanMapper.class);
        cfg.addMapper(ContactPlanStepMapper.class);
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
        ContactPlan plan = new ContactPlan();
        plan.setCaseId(SENTINEL_CASE);
        plan.setUserId(SENTINEL_USER);
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
                assertTrue(loaded.getContextSnapshot().replaceAll("\\s", "").contains("\"dpd\":2"),
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

                assertEquals(1, planMapper.updateStatus(plan.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.REPAID));
                ContactPlan loaded = planMapper.selectById(plan.getId());
                assertEquals(PlanStatus.PLAN_CANCELLED, loaded.getStatus());
                assertEquals(CancelReason.REPAID, loaded.getCancelReason());
                assertTrue(loaded.getStatus().isTerminal());
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
                ContactPlan active = newPlan();
                planMapper.insert(active);
                ContactPlan cancelled = newPlan();
                planMapper.insert(cancelled);
                planMapper.updateStatus(cancelled.getId(), PlanStatus.PLAN_CANCELLED, CancelReason.REPAID);

                List<ContactPlan> activePlans = planMapper.selectActiveByCase(SENTINEL_CASE);
                assertTrue(activePlans.stream().anyMatch(p -> p.getId().equals(active.getId())),
                        "非终态计划应出现在 active 列表");
                assertTrue(activePlans.stream().noneMatch(p -> p.getId().equals(cancelled.getId())),
                        "已取消计划不应出现在 active 列表");
            } finally {
                session.rollback();
            }
        }
    }
}
