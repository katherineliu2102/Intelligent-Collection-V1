package com.collection.channel.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.fastjson.JSONObject;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.service.CaseService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 波次聚合的关键不变量：起批前能剔除、起批失败不静默丢步骤、超时随批次规模伸缩。
 *
 * <p>这三条直接决定聚合上线后会不会出现「客户没被拨到、步骤却挂着等 30 分钟」或「已还款仍被拨打」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FacadeBatchCoordinatorTest {

    private static final ZoneId PHT = ZoneId.of("Asia/Manila");
    private static final String WAVE_KEY = "20260827-0915";
    private static final String WAVE_ID = WAVE_KEY + "#1";
    private static final String CASE_KEY = "channel:facade:wave:" + WAVE_ID;
    private static final String META_KEY = CASE_KEY + ":meta";
    private static final String GEN_KEY = "channel:facade:gen:" + WAVE_KEY;

    @Mock private StringRedisTemplate redis;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ContactPlanRepository planRepository;
    @Mock private CaseService caseService;
    @Mock private FacadeBatchClient batchClient;

    private ChannelProperties properties;
    private FacadeBatchCoordinator coordinator;

    @BeforeEach
    void setUp() {
        properties = new ChannelProperties();
        properties.getFacade().getBatchAggregation().setEnabled(true);

        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(valueOps.increment(GEN_KEY)).thenReturn(2L);

        coordinator = new FacadeBatchCoordinator();
        ReflectionTestUtils.setField(coordinator, "properties", properties);
        ReflectionTestUtils.setField(coordinator, "batchClient", batchClient);
        ReflectionTestUtils.setField(coordinator, "redis", redis);
        ReflectionTestUtils.setField(coordinator, "planRepository", planRepository);
        ReflectionTestUtils.setField(coordinator, "caseService", caseService);
    }

    @Test
    @DisplayName("开关关闭时不聚合，Adapter 会回退一案一批")
    void disabledByDefault() {
        properties.getFacade().getBatchAggregation().setEnabled(false);
        assertFalse(coordinator.isEnabled());
        assertEquals(null, coordinator.enroll(1L, 2L, 3L, new LinkedHashMap<String, Object>()));
    }

    @Test
    @DisplayName("入批按步骤的 original_trigger_time 归到同一触达槽")
    void enrollUsesOriginalTriggerSlot() {
        ContactPlanStep step = new ContactPlanStep();
        step.setId(101L);
        step.setOriginalTriggerTime(LocalDateTime.of(2026, 8, 27, 9, 15));
        when(planRepository.findStepById(101L)).thenReturn(step);
        when(valueOps.get(GEN_KEY)).thenReturn("1");
        when(hashOps.size(CASE_KEY)).thenReturn(3L);

        String externalBatchId = coordinator.enroll(11L, 101L, 701L, caseBody("+639171234567"));

        assertEquals("mocasa-20260827-0915-1", externalBatchId);
        verify(hashOps).put(eq(CASE_KEY), eq("101"), anyString());
        verify(setOps).add("channel:facade:waves", WAVE_ID);
        verify(valueOps, never()).increment(GEN_KEY);
    }

    @Test
    @DisplayName("代次键不存在时 SETNX 写成 1，并发 enroll 不会 INCR 出 #2")
    void enrollInitializesGenerationWithSetNx() {
        ContactPlanStep step = new ContactPlanStep();
        step.setId(101L);
        step.setOriginalTriggerTime(LocalDateTime.of(2026, 8, 27, 9, 15));
        when(planRepository.findStepById(101L)).thenReturn(step);
        when(valueOps.get(GEN_KEY)).thenReturn(null);
        when(valueOps.setIfAbsent(eq(GEN_KEY), eq("1"), any(Duration.class))).thenReturn(true);
        when(hashOps.size(CASE_KEY)).thenReturn(0L);

        String externalBatchId = coordinator.enroll(11L, 101L, 701L, caseBody("+639171234567"));

        assertEquals("mocasa-20260827-0915-1", externalBatchId);
        verify(valueOps).setIfAbsent(eq(GEN_KEY), eq("1"), any(Duration.class));
        verify(valueOps, never()).increment(GEN_KEY);
    }

    @Test
    @DisplayName("SETNX 输给并发赢家后读回对方的 1，不另起代次")
    void enrollReadsWinnerGenerationWhenSetNxLoses() {
        ContactPlanStep step = new ContactPlanStep();
        step.setId(101L);
        step.setOriginalTriggerTime(LocalDateTime.of(2026, 8, 27, 9, 15));
        when(planRepository.findStepById(101L)).thenReturn(step);
        when(valueOps.get(GEN_KEY)).thenReturn(null, "1");
        when(valueOps.setIfAbsent(eq(GEN_KEY), eq("1"), any(Duration.class))).thenReturn(false);
        when(hashOps.size(CASE_KEY)).thenReturn(1L);

        String externalBatchId = coordinator.enroll(11L, 101L, 701L, caseBody("+639171234567"));

        assertEquals("mocasa-20260827-0915-1", externalBatchId);
        verify(valueOps, never()).increment(GEN_KEY);
    }

    @Test
    @DisplayName("起批成功：一次建批 + 一次上传 + 一次起批，超时按批内案数回写")
    void flushStartsSingleBatchAndWritesTimeout() {
        givenDueWaveWith(entry(101L, 701L), entry(102L, 702L));
        when(batchClient.createBatch("mocasa-20260827-0915-1")).thenReturn("batch-9");
        when(batchClient.uploadCases(eq("batch-9"), anyList()))
                .thenReturn(FacadeBatchClient.UploadOutcome.accepted());
        when(batchClient.startBatch("batch-9")).thenReturn(true);

        coordinator.flushDueWaves();

        ArgumentCaptor<List<Map<String, Object>>> cases = captureCases();
        verify(batchClient).uploadCases(eq("batch-9"), cases.capture());
        assertEquals(2, cases.getValue().size());

        LocalDateTime now = LocalDateTime.now(PHT);
        verify(planRepository).updateStepTimeoutTime(eq(101L), deadlineAfter(now.plusMinutes(25)));
        verify(planRepository).updateStepTimeoutTime(eq(102L), deadlineAfter(now.plusMinutes(25)));
    }

    @Test
    @DisplayName("起批前已还款的案件被剔除，且不留在 EXECUTING 等超时")
    void repaidCaseDroppedBeforeUpload() {
        givenDueWaveWith(entry(101L, 701L), entry(102L, 702L));
        when(caseService.isRepaid(701L)).thenReturn(true);
        when(batchClient.createBatch(anyString())).thenReturn("batch-9");
        when(batchClient.uploadCases(anyString(), anyList()))
                .thenReturn(FacadeBatchClient.UploadOutcome.accepted());
        when(batchClient.startBatch(anyString())).thenReturn(true);

        coordinator.flushDueWaves();

        ArgumentCaptor<List<Map<String, Object>>> cases = captureCases();
        verify(batchClient).uploadCases(anyString(), cases.capture());
        assertEquals(1, cases.getValue().size());
        verify(planRepository).updateStepTimeoutTime(eq(101L), deadlineBefore(LocalDateTime.now(PHT).plusMinutes(2)));
    }

    @Test
    @DisplayName("起批失败：所有步骤立刻交回超时哨兵，不等 30 分钟")
    void startFailureHandsStepsBackToTimeoutSentinel() {
        givenDueWaveWith(entry(101L, 701L), entry(102L, 702L));
        when(batchClient.createBatch(anyString())).thenReturn("batch-9");
        when(batchClient.uploadCases(anyString(), anyList()))
                .thenReturn(FacadeBatchClient.UploadOutcome.accepted());
        when(batchClient.startBatch(anyString())).thenReturn(false);

        coordinator.flushDueWaves();

        LocalDateTime soon = LocalDateTime.now(PHT).plusMinutes(2);
        verify(planRepository).updateStepTimeoutTime(eq(101L), deadlineBefore(soon));
        verify(planRepository).updateStepTimeoutTime(eq(102L), deadlineBefore(soon));
    }

    @Test
    @DisplayName("全部案件被剔除时不建批，避免向 Facade 发空批次")
    void noFacadeCallWhenEveryCaseDropped() {
        givenDueWaveWith(entry(101L, 701L));
        when(caseService.isRepaid(701L)).thenReturn(true);

        coordinator.flushDueWaves();

        verify(batchClient, never()).createBatch(anyString());
    }

    @Test
    @DisplayName("超时随批次规模伸缩，但不低于一案一批时的 30 分钟、不越过当日拨打窗")
    void callbackDeadlineScalesWithBatchSizeAndRespectsBounds() {
        LocalDateTime now = LocalDateTime.now(PHT);
        assertTrue(coordinator.callbackDeadline(1).isAfter(now.plusMinutes(29)));

        LocalDateTime large = coordinator.callbackDeadline(400);
        assertFalse(large.isAfter(now.plusMinutes(121)));

        properties.getFacade().setWindowEnd("00:01");
        assertFalse(coordinator.callbackDeadline(400).isAfter(now.plusMinutes(31)));
    }

    private void givenDueWaveWith(JSONObject... entries) {
        when(setOps.members("channel:facade:waves"))
                .thenReturn(new LinkedHashSet<String>(Collections.singletonList(WAVE_ID)));
        when(hashOps.size(CASE_KEY)).thenReturn((long) entries.length);
        long stale = System.currentTimeMillis() - 600_000L;
        when(hashOps.get(META_KEY, "firstEnrollMs")).thenReturn(String.valueOf(stale));
        when(hashOps.get(META_KEY, "lastEnrollMs")).thenReturn(String.valueOf(stale));
        Map<Object, Object> stored = new LinkedHashMap<Object, Object>();
        for (JSONObject entry : entries) {
            stored.put(entry.getString("stepId"), entry.toJSONString());
        }
        when(hashOps.entries(CASE_KEY)).thenReturn(stored);
    }

    private static JSONObject entry(long stepId, long caseId) {
        JSONObject entry = new JSONObject();
        entry.put("planId", 11L);
        entry.put("stepId", stepId);
        entry.put("caseId", caseId);
        entry.put("case", caseBody("+63917000" + stepId));
        return entry;
    }

    private static Map<String, Object> caseBody(String callee) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("external_case_id", "701");
        body.put("callee_e164", callee);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Map<String, Object>>> captureCases() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static LocalDateTime deadlineAfter(LocalDateTime floor) {
        return org.mockito.ArgumentMatchers.argThat(actual -> actual != null && actual.isAfter(floor));
    }

    private static LocalDateTime deadlineBefore(LocalDateTime ceiling) {
        return org.mockito.ArgumentMatchers.argThat(
                actual -> actual != null && actual.isBefore(ceiling));
    }
}
