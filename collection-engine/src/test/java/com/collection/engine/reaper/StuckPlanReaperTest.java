package com.collection.engine.reaper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.repository.ContactPlanRepository;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.metrics.CollectionMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 停摆计划巡检（核心引擎规格 §7.4）：只检测告警，不代替人做不可回滚的触达动作。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StuckPlanReaperTest {

    @Mock private ContactPlanRepository planRepository;
    @Spy private EngineProperties props = new EngineProperties();
    @Spy private CollectionMetrics metrics = CollectionMetrics.local();

    @InjectMocks private StuckPlanReaper reaper;

    /** 固定时钟，避免 reaper 内部 now() 与测试快照 now() 的毫秒级竞态。 */
    private Clock fixedClock;

    @BeforeEach
    void fixClock() {
        fixedClock = Clock.fixed(Instant.parse("2026-08-24T13:50:42.228Z"), ZoneId.systemDefault());
        reaper.setClock(fixedClock);
    }

    @Test
    @DisplayName("检出停摆计划 → 计数告警，且不写库、不重发触达")
    void reportsStuckPlansWithoutRepair() {
        when(planRepository.findStuckPlanIds(any(), anyInt())).thenReturn(Arrays.asList(11L, 12L));

        reaper.detectStuckPlans();

        verify(metrics).planStuck(2);
        verify(planRepository, never()).updateStepTriggerTime(any(), any(), any());
        verify(planRepository, never()).updatePlanStatus(any(), any(), any());
    }

    @Test
    @DisplayName("只看静默超过 idleMinutes 的计划，避开正在处理中的计划")
    void excludesRecentlyTouchedPlans() {
        props.getReaper().setIdleMinutes(45);
        when(planRepository.findStuckPlanIds(any(), anyInt())).thenReturn(Collections.emptyList());

        reaper.detectStuckPlans();

        ArgumentCaptor<LocalDateTime> idleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(planRepository).findStuckPlanIds(idleBefore.capture(), anyInt());
        // reaper 与测试共用 fixedClock：idleBefore 必等于 now-45min，无时基竞态
        assertThat(idleBefore.getValue()).isEqualTo(LocalDateTime.now(fixedClock).minusMinutes(45));
    }

    @Test
    @DisplayName("关闭巡检 → 不扫描")
    void doesNothingWhenDisabled() {
        props.getReaper().setEnabled(false);

        reaper.detectStuckPlans();

        verify(planRepository, never()).findStuckPlanIds(any(), anyInt());
    }

    @Test
    @DisplayName("扫描抛错只记日志，不影响下一轮")
    void swallowsScanFailure() {
        when(planRepository.findStuckPlanIds(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        reaper.detectStuckPlans();

        verify(metrics, never()).planStuck(anyInt());
    }

    @Test
    @DisplayName("批次上限取自配置")
    void usesConfiguredBatchSize() {
        props.getReaper().setBatchSize(5);
        when(planRepository.findStuckPlanIds(any(), anyInt())).thenReturn(Collections.emptyList());

        reaper.detectStuckPlans();

        verify(planRepository).findStuckPlanIds(any(), eq(5));
    }
}
