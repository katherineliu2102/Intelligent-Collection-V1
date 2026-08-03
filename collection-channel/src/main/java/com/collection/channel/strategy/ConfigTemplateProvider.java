package com.collection.channel.strategy;

import com.collection.channel.config.ChannelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 1.5：DB 配置读取源（SMS/Push 文案 + 计划模板）。
 *
 * <p>DB 优先、YAML 兜底：命中 DB(t_script_template / t_contact_plan_template ACTIVE) 时覆盖 {@code
 * ChannelProperties}，未命中返回 {@code null}，由调用方回落到 Nacos/YAML。
 *
 * <p>热更新：管理后台写配置时 bump {@code t_config_version_seq.current_version}，本类按 TTL 轮询版本号失效缓存。
 *
 * <p><b>零 DB I/O 约定</b>：{@code getSms/getPush/getPlanSteps} 在 {@code StepResolver}/{@code PlanFactory}
 * 热路径上被调用，二者硬超时仅 50ms（核心引擎规格 §4.1），而跨区域 MySQL 单次往返即可达数百毫秒。 因此读方法只读 volatile 缓存，TTL
 * 到期时把版本号轮询与 reload 交给后台线程；缓存未就绪时返回 {@code null} 由调用方回落 YAML， 而非让引擎线程等待 JDBC。
 *
 * <p>{@link JdbcTemplate} 经 {@link ObjectProvider} 可选注入：宿主应用（collection-admin）存在 DataSource 时启用
 * DB；渠道模块独立单测无 DataSource 时自动降级为纯 YAML。
 */
@Component
public class ConfigTemplateProvider {

    private static final Logger log = LoggerFactory.getLogger(ConfigTemplateProvider.class);
    private static final String TENANT = "mocasa-ph";

    private final JdbcTemplate jdbcTemplate;
    private final boolean dbEnabled;
    private final long cacheTtlMs;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final ExecutorService refreshPool;

    private volatile long lastCheckAt = 0L;
    private volatile long loadedVersion = -1L;
    private volatile boolean cacheLoaded = false;
    private volatile Map<String, String> smsCache = Collections.emptyMap();
    private volatile Map<String, ChannelProperties.PushScript> pushCache = Collections.emptyMap();
    private volatile Map<String, List<ChannelProperties.PlanStepDef>> planCache =
            Collections.emptyMap();
    // 逐槽位 config_version：供审计标记「这条话术来自 DB 的哪一版」，区别于全局 epoch loadedVersion。
    private volatile Map<String, Long> smsVersionCache = Collections.emptyMap();
    private volatile Map<String, Long> pushVersionCache = Collections.emptyMap();

    public ConfigTemplateProvider(
            ObjectProvider<JdbcTemplate> jdbcProvider,
            @Value("${channel.config.db-source-enabled:true}") boolean dbSourceEnabled,
            @Value("${channel.config.cache-ttl-ms:10000}") long cacheTtlMs) {
        this.jdbcTemplate = jdbcProvider.getIfAvailable();
        this.dbEnabled = dbSourceEnabled && this.jdbcTemplate != null;
        this.cacheTtlMs = cacheTtlMs;
        this.refreshPool =
                this.dbEnabled
                        ? Executors.newSingleThreadExecutor(
                                r -> {
                                    Thread t = new Thread(r, "channel-config-refresh");
                                    t.setDaemon(true);
                                    return t;
                                })
                        : null;
        log.info(
                "[ConfigTemplateProvider] dbEnabled={} (configured={}, jdbcPresent={}), cacheTtlMs={}",
                dbEnabled,
                dbSourceEnabled,
                this.jdbcTemplate != null,
                cacheTtlMs);
    }

    /** 是否启用 DB 读取（供 admin 展示"当前生效来源"）。 */
    public boolean isDbActive() {
        return dbEnabled;
    }

    /** DB 中的 SMS 正文；未配置或 DB 不可用返回 {@code null}（调用方回落 YAML）。 */
    public String getSms(String scriptSlot) {
        if (scriptSlot == null || !cacheReady()) {
            return null;
        }
        return smsCache.get(scriptSlot);
    }

    /** DB 中的 Push title/body；未配置或 DB 不可用返回 {@code null}。 */
    public ChannelProperties.PushScript getPush(String scriptSlot) {
        if (scriptSlot == null || !cacheReady()) {
            return null;
        }
        return pushCache.get(scriptSlot);
    }

    /** DB 中该 stage 的计划步骤；未配置或 DB 不可用返回 {@code null}。 */
    public List<ChannelProperties.PlanStepDef> getPlanSteps(String stageKey) {
        if (stageKey == null || !cacheReady()) {
            return null;
        }
        return planCache.get(stageKey);
    }

    /**
     * 该 SMS 槽位在 DB 中的 {@code config_version}；{@code null} 表示本槽位不由 DB 供源（调用方应标记为 YAML/Nacos 来源）。
     *
     * <p>与 {@link #getCurrentConfigVersion()} 的区别：后者是全局 epoch（进程加载了第几代配置）， 本方法是这一条话术自身的版本，用于
     * {@code t_contact_timeline.template_version} 精确溯源。
     */
    public Long getSmsVersion(String scriptSlot) {
        if (scriptSlot == null || !cacheReady()) {
            return null;
        }
        return smsCache.containsKey(scriptSlot) ? smsVersionCache.get(scriptSlot) : null;
    }

    /** 该 PUSH 槽位在 DB 中的 {@code config_version}；{@code null} 表示不由 DB 供源。 */
    public Long getPushVersion(String scriptSlot) {
        if (scriptSlot == null || !cacheReady()) {
            return null;
        }
        return pushCache.containsKey(scriptSlot) ? pushVersionCache.get(scriptSlot) : null;
    }

    /**
     * 启动预热：在 bean 初始化阶段<b>阻塞</b>加载一次。
     *
     * <p>刻意不用 {@code ApplicationReadyEvent}——那样 PubSub consumer 可能先于预热完成而开始消费， 首批案件会静默回落 YAML
     * 计划模板，建出结构不符预期的计划。放在 {@code @PostConstruct} 可保证 任何依赖方拿到本 bean 时缓存已就绪；DB 不可用时
     * {@link #refreshBlocking()} 内部吞异常，退化为 YAML 供源而不阻断启动。
     */
    @PostConstruct
    public void warmUp() {
        if (dbEnabled) {
            refreshBlocking();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (refreshPool != null) {
            refreshPool.shutdownNow();
        }
    }

    /** 只读缓存 + 到期时异步刷新，保证调用方（SPI 热路径）零阻塞。 */
    private boolean cacheReady() {
        if (!dbEnabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (!cacheLoaded || now - lastCheckAt >= cacheTtlMs) {
            triggerAsyncRefresh(now);
        }
        return cacheLoaded;
    }

    private void triggerAsyncRefresh(long now) {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        lastCheckAt = now;
        try {
            refreshPool.execute(
                    () -> {
                        try {
                            refreshBlocking();
                        } finally {
                            refreshing.set(false);
                        }
                    });
        } catch (RejectedExecutionException e) {
            refreshing.set(false);
        }
    }

    private void refreshBlocking() {
        lastCheckAt = System.currentTimeMillis();
        try {
            long current = currentVersion();
            if (!cacheLoaded || current != loadedVersion) {
                reload();
                loadedVersion = current;
            }
            cacheLoaded = true;
        } catch (DataAccessException e) {
            log.warn(
                    "[ConfigTemplateProvider] DB config unavailable, fallback to YAML: {}",
                    e.getMessage());
        }
    }

    private long currentVersion() {
        Long v =
                jdbcTemplate.queryForObject(
                        "SELECT current_version FROM t_config_version_seq WHERE id = 1",
                        Long.class);
        return v == null ? 0L : v;
    }

    /**
     * 当前<b>已生效</b>的 DB 配置版本；0 表示未启用或缓存尚未就绪。
     *
     * <p>返回缓存版本而非现查 DB：一是本方法在 {@code StepResolver} 热路径上（50ms 硬超时，见类注释的零 DB I/O 约定）；
     * 二是审计字段 {@code template_version} 要记录「本次渲染实际用的配置版本」，现查到的最新版本可能还没被本进程加载。
     */
    public long getCurrentConfigVersion() {
        if (!dbEnabled) {
            return 0L;
        }
        cacheReady();
        return loadedVersion < 0 ? 0L : loadedVersion;
    }

    private void reload() {
        Map<String, String> sms = new HashMap<>();
        Map<String, Long> smsVersions = new HashMap<>();
        jdbcTemplate.query(
                "SELECT script_slot, config_version, JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.body')) AS body "
                        + "FROM t_script_template WHERE tenant_id = ? AND channel = 'SMS' AND status = 'ACTIVE'",
                new Object[] {TENANT},
                rs -> {
                    String slot = rs.getString("script_slot");
                    String body = rs.getString("body");
                    if (slot != null && body != null && !"null".equals(body)) {
                        sms.put(slot, body);
                        smsVersions.put(slot, rs.getLong("config_version"));
                    }
                });

        Map<String, ChannelProperties.PushScript> push = new HashMap<>();
        Map<String, Long> pushVersions = new HashMap<>();
        jdbcTemplate.query(
                "SELECT script_slot, config_version, "
                        + "JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.title')) AS title, "
                        + "JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.body')) AS body "
                        + "FROM t_script_template WHERE tenant_id = ? AND channel = 'PUSH' AND status = 'ACTIVE'",
                new Object[] {TENANT},
                rs -> {
                    String slot = rs.getString("script_slot");
                    if (slot == null) {
                        return;
                    }
                    ChannelProperties.PushScript ps = new ChannelProperties.PushScript();
                    String title = rs.getString("title");
                    String body = rs.getString("body");
                    ps.setTitle(title == null || "null".equals(title) ? "" : title);
                    ps.setBody(body == null || "null".equals(body) ? "" : body);
                    push.put(slot, ps);
                    pushVersions.put(slot, rs.getLong("config_version"));
                });

        Map<String, List<ChannelProperties.PlanStepDef>> plans = new HashMap<>();
        jdbcTemplate.query(
                "SELECT stage, plan_json FROM t_contact_plan_template "
                        + "WHERE tenant_id = ? AND status = 'ACTIVE' "
                        + "ORDER BY stage, config_version, updated_at",
                new Object[] {TENANT},
                rs -> {
                    String stage = rs.getString("stage");
                    List<ChannelProperties.PlanStepDef> steps =
                            parsePlanSteps(rs.getString("plan_json"));
                    if (stage != null && steps != null) {
                        // 同 stage 多模板时，按 config_version/updated_at 升序遍历，后者覆盖 → 取最新 ACTIVE
                        plans.put(stage, steps);
                    }
                });

        smsCache = sms;
        pushCache = push;
        planCache = plans;
        smsVersionCache = smsVersions;
        pushVersionCache = pushVersions;
        log.info(
                "[ConfigTemplateProvider] reloaded DB config: sms={}, push={}, planStages={}",
                sms.size(),
                push.size(),
                plans.keySet());
    }

    private List<ChannelProperties.PlanStepDef> parsePlanSteps(String planJson) {
        if (planJson == null || planJson.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(planJson);
            JsonNode stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray()) {
                return null;
            }
            List<ChannelProperties.PlanStepDef> steps = new ArrayList<>();
            for (JsonNode node : stepsNode) {
                ChannelProperties.PlanStepDef def = new ChannelProperties.PlanStepDef();
                def.setChannel(node.path("channel").asText(null));
                def.setDelayMin(node.path("delayMin").asInt(0));
                def.setObserveMin(node.path("observeMin").asInt(0));
                def.setTemplateId(node.path("templateId").asLong(0L));
                steps.add(def);
            }
            return steps;
        } catch (Exception e) {
            log.warn("[ConfigTemplateProvider] bad plan_json skipped: {}", e.getMessage());
            return null;
        }
    }
}
