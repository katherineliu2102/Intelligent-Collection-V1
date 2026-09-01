package com.collection.channel.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.service.CaseService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * AI_CALL 波次聚合协调器：把同一触达槽到期的多个步骤合成一个 Facade 批次。
 *
 * <p><b>为什么缓冲到起批才上传</b>：案件在 {@code start} 之前只存在于我方 Redis，还款或计划取消时直接从缓冲里剔除即可， 不需要 Facade 提供 case
 * 级撤单接口。起批之后无法撤单（与一案一批时相同）——**禁止**改用批级 {@code cancel} 代偿， 那会停掉同批其他借款人的电话。
 *
 * <p><b>起批失败与被剔除的步骤</b>：不在本类里直接改步骤状态，而是把 {@code timeout_time} 置为当前时刻，交给既有的 {@code callbackTimeout}
 * 哨兵按标准路径收口并推进计划。渠道层因此不必碰事件总线，也不会出现「步骤终态了但计划停在 STEP_EXECUTING」。
 */
@Component
public class FacadeBatchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(FacadeBatchCoordinator.class);
    private static final ZoneId PHT = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter WAVE_KEY = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private static final String PREFIX = "channel:facade:";
    private static final String OPEN_WAVES_KEY = PREFIX + "waves";
    private static final String FLUSH_LOCK_KEY = PREFIX + "flush-lock";
    private static final String META_FIRST = "firstEnrollMs";
    private static final String META_LAST = "lastEnrollMs";
    private static final Duration WAVE_TTL = Duration.ofHours(12);

    @Resource private ChannelProperties properties;
    @Resource private FacadeBatchClient batchClient;

    /** local / CI 无 Redis 时为空，此时聚合自动降级为一案一批。 */
    @Autowired(required = false)
    private StringRedisTemplate redis;

    /** 波次键取自步骤的 original_trigger_time；缺仓储时退化为按分钟聚合。 */
    @Autowired(required = false)
    private ContactPlanRepository planRepository;

    /** 起批前复检还款；缺失时不复检，仅记录。 */
    @Autowired(required = false)
    private CaseService caseService;

    public boolean isEnabled() {
        return properties.getFacade().getBatchAggregation().isEnabled()
                && redis != null
                && planRepository != null;
    }

    /**
     * 把一个案件放进当前开放波次。
     *
     * @return 波次号（写入 timeline 的 providerMsgId）；返回 null 表示入批失败，调用方应回退一案一批
     */
    public String enroll(Long planId, Long stepId, Long caseId, Map<String, Object> caseBody) {
        if (!isEnabled() || stepId == null) {
            return null;
        }
        try {
            ChannelProperties.BatchAggregation cfg = properties.getFacade().getBatchAggregation();
            String waveKey = waveKeyOf(stepId);
            long generation = currentGeneration(waveKey);
            String waveId = waveId(waveKey, generation);
            Long size = redis.opsForHash().size(caseKey(waveId));
            if (size != null && size >= cfg.getMaxCasesPerBatch()) {
                generation = redis.opsForValue().increment(generationKey(waveKey));
                waveId = waveId(waveKey, generation);
            }

            JSONObject entry = new JSONObject();
            entry.put("planId", planId);
            entry.put("stepId", stepId);
            entry.put("caseId", caseId);
            entry.put("case", caseBody);

            long now = System.currentTimeMillis();
            redis.opsForHash().put(caseKey(waveId), String.valueOf(stepId), entry.toJSONString());
            redis.opsForHash().putIfAbsent(metaKey(waveId), META_FIRST, String.valueOf(now));
            redis.opsForHash().put(metaKey(waveId), META_LAST, String.valueOf(now));
            redis.opsForSet().add(OPEN_WAVES_KEY, waveId);
            redis.expire(caseKey(waveId), WAVE_TTL);
            redis.expire(metaKey(waveId), WAVE_TTL);
            redis.expire(generationKey(waveKey), WAVE_TTL);
            return externalBatchId(waveId);
        } catch (RuntimeException e) {
            log.warn("[FacadeBatch] 入批失败，回退一案一批 step={}: {}", stepId, e.getMessage());
            return null;
        }
    }

    /** 定时调用：把到点的波次起批。单飞由 Redis 锁保证，多实例下只有一个在起批。 */
    public void flushDueWaves() {
        if (!isEnabled()) {
            return;
        }
        String token = UUID.randomUUID().toString();
        Boolean locked =
                redis.opsForValue().setIfAbsent(FLUSH_LOCK_KEY, token, Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            Set<String> open = redis.opsForSet().members(OPEN_WAVES_KEY);
            if (open == null || open.isEmpty()) {
                return;
            }
            for (String waveId : open) {
                try {
                    if (isDue(waveId)) {
                        flushWave(waveId);
                    }
                } catch (RuntimeException e) {
                    log.error("[FacadeBatch] 波次 {} 起批异常", waveId, e);
                }
            }
        } finally {
            releaseLock(token);
        }
    }

    private boolean isDue(String waveId) {
        Long size = redis.opsForHash().size(caseKey(waveId));
        if (size == null || size == 0) {
            // 空波次（已被剔空或过期）：从开放集合摘掉，避免每轮空转
            redis.opsForSet().remove(OPEN_WAVES_KEY, waveId);
            return false;
        }
        ChannelProperties.BatchAggregation cfg = properties.getFacade().getBatchAggregation();
        if (size >= cfg.getMaxCasesPerBatch()) {
            return true;
        }
        long now = System.currentTimeMillis();
        long first = readLong(metaKey(waveId), META_FIRST, now);
        long last = readLong(metaKey(waveId), META_LAST, now);
        return now - last >= cfg.getSilenceSeconds() * 1000L
                || now - first >= cfg.getMaxWaitSeconds() * 1000L;
    }

    /** 先关闭波次再读，保证起批期间新到期的步骤落到下一代波次，不会漏发也不会重复上传。 */
    private void flushWave(String waveId) {
        redis.opsForValue().increment(generationKey(waveKeyOf(waveId)));
        redis.opsForSet().remove(OPEN_WAVES_KEY, waveId);
        Map<Object, Object> raw = redis.opsForHash().entries(caseKey(waveId));
        redis.delete(caseKey(waveId));
        redis.delete(metaKey(waveId));
        if (raw == null || raw.isEmpty()) {
            return;
        }

        List<JSONObject> kept = new ArrayList<JSONObject>();
        List<Long> dropped = new ArrayList<Long>();
        for (Object value : raw.values()) {
            JSONObject entry = JSON.parseObject(String.valueOf(value));
            if (isRepaid(entry.getLong("caseId"))) {
                dropped.add(entry.getLong("stepId"));
            } else {
                kept.add(entry);
            }
        }
        for (Long stepId : dropped) {
            expireStepNow(stepId, "REPAID_BEFORE_START");
        }
        if (kept.isEmpty()) {
            log.info("[FacadeBatch] 波次 {} 全部案件在起批前被剔除，不建批", waveId);
            return;
        }
        dispatchWave(waveId, kept);
    }

    private void dispatchWave(String waveId, List<JSONObject> kept) {
        String externalBatchId = externalBatchId(waveId);
        List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
        for (JSONObject entry : kept) {
            cases.add(entry.getJSONObject("case"));
        }
        String failure;
        try {
            String batchId = batchClient.createBatch(externalBatchId);
            if (StringUtils.isBlank(batchId)) {
                failure = "FACADE_NO_BATCH_ID";
            } else {
                FacadeBatchClient.UploadOutcome upload = batchClient.uploadCases(batchId, cases);
                if (!upload.isSuccess()) {
                    failure = upload.getErrorCode();
                } else if (!batchClient.startBatch(batchId)) {
                    failure = "FACADE_START_BATCH";
                } else {
                    onWaveStarted(waveId, externalBatchId, batchId, kept);
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.error("[FacadeBatch] 波次 {} 调用 Facade 失败", waveId, e);
            failure = "FACADE_WAVE_DISPATCH_FAILED";
        }
        log.error("[FacadeBatch] 波次 {} 起批失败（{}），{} 个步骤交回超时哨兵收口", waveId, failure, kept.size());
        for (JSONObject entry : kept) {
            expireStepNow(entry.getLong("stepId"), failure);
        }
    }

    private void onWaveStarted(
            String waveId, String externalBatchId, String batchId, List<JSONObject> kept) {
        LocalDateTime deadline = callbackDeadline(kept.size());
        for (JSONObject entry : kept) {
            Long stepId = entry.getLong("stepId");
            try {
                planRepository.updateStepTimeoutTime(stepId, deadline);
            } catch (RuntimeException e) {
                log.error("[FacadeBatch] 回写步骤 {} 超时时刻失败", stepId, e);
            }
        }
        log.info(
                "[FacadeBatch] wave started wave={} externalBatchId={} batchId={} cases={} callbackDeadline={}",
                waveId,
                externalBatchId,
                batchId,
                kept.size(),
                deadline);
    }

    /**
     * 回调超时时刻：按「批内案数 ÷ 并发 × 单通时长 + 缓冲」估算，下界沿用一案一批时的默认， 上界既受 maxTimeoutMinutes
     * 约束，也不得越过当日拨打窗结束——窗外的案件今天不会再被拨，挂到明天没有意义。
     */
    LocalDateTime callbackDeadline(int caseCount) {
        ChannelProperties.Facade facade = properties.getFacade();
        ChannelProperties.BatchAggregation cfg = facade.getBatchAggregation();
        int concurrency = Math.max(1, cfg.getAssumedConcurrency());
        int rounds = (int) Math.ceil((double) caseCount / concurrency);
        long dialMinutes = (long) Math.ceil(rounds * cfg.getAssumedCallSeconds() / 60.0);
        long minutes = dialMinutes + cfg.getTimeoutBufferMinutes();
        minutes = Math.max(minutes, cfg.getMinTimeoutMinutes());
        minutes = Math.min(minutes, cfg.getMaxTimeoutMinutes());

        LocalDateTime now = LocalDateTime.now(PHT);
        LocalDateTime deadline = now.plusMinutes(minutes);
        LocalDateTime windowEnd = windowEnd(now.toLocalDate(), facade.getWindowEnd());
        if (windowEnd != null && deadline.isAfter(windowEnd)) {
            deadline = maxOf(windowEnd, now.plusMinutes(cfg.getMinTimeoutMinutes()));
        }
        return deadline;
    }

    private static LocalDateTime maxOf(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime windowEnd(LocalDate day, String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return LocalDateTime.of(day, LocalTime.parse(raw.trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 让既有 callbackTimeout 哨兵在下一轮扫描收口该步骤并推进计划。 */
    private void expireStepNow(Long stepId, String reason) {
        if (stepId == null) {
            return;
        }
        try {
            planRepository.updateStepTimeoutTime(stepId, LocalDateTime.now(PHT));
            log.warn("[FacadeBatch] 步骤 {} 未随波次发出（{}），交回超时哨兵", stepId, reason);
        } catch (RuntimeException e) {
            log.error("[FacadeBatch] 步骤 {} 交回超时哨兵失败（{}）", stepId, reason, e);
        }
    }

    private boolean isRepaid(Long caseId) {
        if (caseService == null || caseId == null) {
            return false;
        }
        try {
            return caseService.isRepaid(caseId);
        } catch (RuntimeException e) {
            // 查不到就照常拨：漏拨一次的代价低于把还款状态未知当成已还款而静默停催
            log.warn("[FacadeBatch] 案件 {} 还款状态复检失败，按未还款处理: {}", caseId, e.getMessage());
            return false;
        }
    }

    private String waveKeyOf(Long stepId) {
        LocalDateTime slot = null;
        try {
            ContactPlanStep step = planRepository.findStepById(stepId);
            if (step != null) {
                slot =
                        step.getOriginalTriggerTime() != null
                                ? step.getOriginalTriggerTime()
                                : step.getTriggerTime();
            }
        } catch (RuntimeException e) {
            log.warn("[FacadeBatch] 读取步骤 {} 触发时刻失败，按当前分钟聚合", stepId);
        }
        return (slot == null ? LocalDateTime.now(PHT) : slot).format(WAVE_KEY);
    }

    private static String waveKeyOf(String waveId) {
        int idx = waveId.indexOf('#');
        return idx < 0 ? waveId : waveId.substring(0, idx);
    }

    private long currentGeneration(String waveKey) {
        String key = generationKey(waveKey);
        Long existing = parseGeneration(redis.opsForValue().get(key));
        if (existing != null) {
            return existing;
        }
        // 键不存在时用 SETNX 写成 1。并发 INCR 会把同一槽拆成 #1 #2 #3…（14:30 首跑事故）。
        Boolean created = redis.opsForValue().setIfAbsent(key, "1", WAVE_TTL);
        if (Boolean.TRUE.equals(created)) {
            return 1L;
        }
        Long raced = parseGeneration(redis.opsForValue().get(key));
        if (raced != null) {
            return raced;
        }
        Long generation = redis.opsForValue().increment(key);
        return generation == null ? 1L : generation;
    }

    private static Long parseGeneration(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long readLong(String key, String field, long fallback) {
        Object raw = redis.opsForHash().get(key, field);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void releaseLock(String token) {
        try {
            if (token.equals(redis.opsForValue().get(FLUSH_LOCK_KEY))) {
                redis.delete(FLUSH_LOCK_KEY);
            }
        } catch (RuntimeException e) {
            log.warn("[FacadeBatch] 释放起批锁失败，等待 TTL 过期: {}", e.getMessage());
        }
    }

    private static String waveId(String waveKey, long generation) {
        return waveKey + "#" + generation;
    }

    private static String externalBatchId(String waveId) {
        return "mocasa-" + waveId.replace('#', '-');
    }

    private static String caseKey(String waveId) {
        return PREFIX + "wave:" + waveId;
    }

    private static String metaKey(String waveId) {
        return PREFIX + "wave:" + waveId + ":meta";
    }

    private static String generationKey(String waveKey) {
        return PREFIX + "gen:" + waveKey;
    }

    /** 供单测构造只读视图，避免把 Redis 细节泄漏到断言里。 */
    Map<String, Object> debugSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("enabled", isEnabled());
        snapshot.put("openWaves", redis == null ? null : redis.opsForSet().members(OPEN_WAVES_KEY));
        return snapshot;
    }
}
