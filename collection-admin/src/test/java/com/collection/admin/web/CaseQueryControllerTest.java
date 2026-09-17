package com.collection.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

/**
 * 案件检索的数据源与过滤条件绑定。
 *
 * <p>本模块测试不连库，故这里校验的是生成的 SQL 与参数绑定，而非查询结果。 重点是把「案件目录 SSOT = {@code t_ai_collection}」钉死：读回旧库 {@code
 * t_collection} 不会让任何现有用例失败，但线上表现是「引擎在催、后台搜不到」（T0-6）。
 */
class CaseQueryControllerTest {

    private JdbcTemplate jdbcTemplate;
    private CaseQueryController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(Object[].class), eq(Long.class)))
                .thenReturn(0L);
        when(jdbcTemplate.query(any(String.class), any(Object[].class), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());
        controller = new CaseQueryController(jdbcTemplate);
    }

    private String captureDataSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(Object[].class), any(RowMapper.class));
        return sql.getValue();
    }

    private Object[] captureDataArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(any(String.class), args.capture(), any(RowMapper.class));
        return args.getValue();
    }

    @Test
    void readsProjectionTableNotLegacyCatalog() {
        controller.search(null, null, null, null, null, null, 1, 20);

        String sql = captureDataSql();
        assertThat(sql).contains("FROM t_ai_collection c");
        // t_ai_collection 也出现在 "t_collection" 的子串里，故按词边界断言。
        assertThat(sql).doesNotContain(" t_collection ").doesNotContain("FROM t_collection");
    }

    @Test
    void doesNotExposeBorrowerName() {
        controller.search(null, null, null, null, null, null, 1, 20);

        assertThat(captureDataSql()).doesNotContain("borrower_name");
    }

    @Test
    void bindsCaseAndUserFiltersToProjectionColumns() {
        controller.search(92002L, 771L, null, null, null, null, 1, 20);

        String sql = captureDataSql();
        assertThat(sql).contains("c.case_id = ?").contains("c.user_id = ?");
        assertThat(captureDataArgs()).startsWith(92002L, 771L);
    }

    @Test
    void stageAndCollectionStatusComeFromProjectionWhilePlanStatusComesFromPlan() {
        controller.search(null, null, "S2", "IN_COLLECTION", "PLAN_COMPLETED", null, 1, 20);

        String sql = captureDataSql();
        assertThat(sql).contains("c.stage = ?").contains("c.collection_status = ?");
        assertThat(sql).contains("p.status = ?");
        assertThat(captureDataArgs()).startsWith("S2", "IN_COLLECTION", "PLAN_COMPLETED");
    }

    /** 一案多条历史计划时列表必须稳定展示最新一条，而不是任取其一。 */
    @Test
    void joinsOnlyTheLatestPlanPerCase() {
        controller.search(null, null, null, null, null, null, 1, 20);

        String sql = captureDataSql();
        assertThat(sql)
                .contains("SELECT MAX(p2.id) FROM t_contact_plan p2 WHERE p2.case_id = c.case_id");
        assertThat(sql).doesNotContain("ANY_VALUE").doesNotContain("GROUP BY");
    }

    @Test
    void frozenFilterNegatesWithoutDroppingUnfrozenCases() {
        controller.search(null, null, null, null, null, Boolean.FALSE, 1, 20);

        assertThat(captureDataSql())
                .contains("AND NOT EXISTS (SELECT 1 FROM t_admin_case_freeze f");
    }

    @Test
    void clampsPageSizeAndComputesOffset() {
        controller.search(null, null, null, null, null, null, 3, 500);

        // pageSize 上限 100，offset = (3-1) * 100
        assertThat(captureDataArgs()).containsExactly(100, 200);
    }

    @Test
    void includesOwnerDateAndLastCancelReason() {
        controller.search(null, null, null, null, null, null, 1, 20);

        String sql = captureDataSql();
        assertThat(sql).contains("c.owner_date AS ownerDate");
        assertThat(sql).contains("p.cancel_reason AS lastCancelReason");
    }

    @Test
    void get_missingCase_notFound() {
        assertThatThrownBy(() -> controller.get(92002L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_readsProjectionSummaryWithoutBorrowerName() {
        when(jdbcTemplate.query(any(String.class), any(Object[].class), any(RowMapper.class)))
                .thenReturn(Collections.singletonList(new LinkedHashMap<>()));

        controller.get(92002L);

        String sql = captureDataSql();
        assertThat(sql).contains("FROM t_ai_collection c");
        assertThat(sql).contains("WHERE c.case_id = ?");
        assertThat(sql).contains("c.owner_date AS ownerDate");
        assertThat(sql).contains("c.due_date AS dueDate");
        assertThat(sql).contains("p.cancel_reason AS lastCancelReason");
        assertThat(sql).doesNotContain("borrower_name");
        assertThat(captureDataArgs()).containsExactly(92002L);
    }
}
