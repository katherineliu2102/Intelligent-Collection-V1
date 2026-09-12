package com.collection.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.enums.Stage;
import com.collection.engine.lifecycle.PlanLifecycleManager;
import com.collection.engine.lifecycle.PlanLifecycleManager.StrategyRebuildResult;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PlanStrategyRebuildControllerTest {

    private PlanLifecycleManager manager;
    private PlanStrategyRebuildController controller;

    @BeforeEach
    void setUp() {
        manager = Mockito.mock(PlanLifecycleManager.class);
        controller = new PlanStrategyRebuildController(manager);
    }

    @Test
    void windowIncludesEveningAndPreRoll() {
        assertThat(
                        PlanStrategyRebuildController.inRebuildWindow(
                                LocalDateTime.of(2026, 9, 11, 19, 0)))
                .isTrue();
        assertThat(
                        PlanStrategyRebuildController.inRebuildWindow(
                                LocalDateTime.of(2026, 9, 12, 3, 34)))
                .isTrue();
        assertThat(
                        PlanStrategyRebuildController.inRebuildWindow(
                                LocalDateTime.of(2026, 9, 11, 18, 40)))
                .isFalse();
        assertThat(
                        PlanStrategyRebuildController.inRebuildWindow(
                                LocalDateTime.of(2026, 9, 12, 3, 35)))
                .isFalse();
        assertThat(
                        PlanStrategyRebuildController.inRebuildWindow(
                                LocalDateTime.of(2026, 9, 12, 9, 15)))
                .isFalse();
    }

    @Test
    void rejectsWrongConfirm() {
        ResponseEntity<Map<String, Object>> response =
                controller.rebuildStrategy("nope", true, true, 10, 0L, 0L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(manager, never()).listActiveS1ToS4PlanIds(anyLong(), anyLong(), eq(10));
    }

    @Test
    void dryRunCountsWouldRebuild() {
        when(manager.maxActiveS1ToS4PlanId()).thenReturn(88L);
        when(manager.listActiveS1ToS4PlanIds(0L, 88L, 10))
                .thenReturn(Collections.singletonList(88L));
        when(manager.rebuildStrategyPlan(88L, true))
                .thenReturn(StrategyRebuildResult.wouldRebuild(88L, 1002L, Stage.S2));

        ResponseEntity<Map<String, Object>> response =
                controller.rebuildStrategy(
                        PlanStrategyRebuildController.CONFIRM_TOKEN, true, true, 10, 0L, 0L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("wouldRebuild")).isEqualTo(1);
        assertThat(data.get("rebuilt")).isEqualTo(0);
        assertThat(data.get("scanned")).isEqualTo(1);
        assertThat(data.get("frozenMaxId")).isEqualTo(88L);
    }

    @Test
    void recordsExecutingSkips() {
        when(manager.maxActiveS1ToS4PlanId()).thenReturn(91L);
        when(manager.listActiveS1ToS4PlanIds(0L, 91L, 10))
                .thenReturn(Collections.singletonList(91L));
        when(manager.rebuildStrategyPlan(91L, false))
                .thenReturn(
                        StrategyRebuildResult.skipped("SKIPPED_EXECUTING_AI", 91L, 7L, Stage.S3));

        ResponseEntity<Map<String, Object>> response =
                controller.rebuildStrategy(
                        PlanStrategyRebuildController.CONFIRM_TOKEN, false, true, 10, 0L, 0L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("skipped")).isEqualTo(1);
        assertThat((List<?>) data.get("executingPlanIds")).containsExactly(91L);
        verify(manager).rebuildStrategyPlan(91L, false);
    }

    @Test
    void forceBypassesWindowWithoutCallingRebuildWhenEmpty() {
        when(manager.maxActiveS1ToS4PlanId()).thenReturn(null);
        ResponseEntity<Map<String, Object>> response =
                controller.rebuildStrategy(
                        PlanStrategyRebuildController.CONFIRM_TOKEN, true, true, 10, 0L, 0L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(manager, never()).listActiveS1ToS4PlanIds(anyLong(), anyLong(), eq(10));
        verify(manager, never()).rebuildStrategyPlan(anyLong(), anyBoolean());
    }

    @Test
    void snapshotsIdsBeforeMutatingAndHonorsFrozenMaxId() {
        when(manager.listActiveS1ToS4PlanIds(0L, 50L, 10))
                .thenReturn(Collections.singletonList(12L));
        when(manager.rebuildStrategyPlan(12L, false))
                .thenReturn(StrategyRebuildResult.rebuilt(12L, 9L, Stage.S1));

        ResponseEntity<Map<String, Object>> response =
                controller.rebuildStrategy(
                        PlanStrategyRebuildController.CONFIRM_TOKEN, false, true, 10, 0L, 50L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("frozenMaxId")).isEqualTo(50L);
        assertThat(data.get("rebuilt")).isEqualTo(1);
        verify(manager, never()).maxActiveS1ToS4PlanId();
    }
}
