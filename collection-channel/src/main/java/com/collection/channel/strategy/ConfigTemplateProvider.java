package com.collection.channel.strategy;

import com.collection.channel.config.ChannelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>热更新：管理后台写配置时 bump {@code t_config_version_seq.current_version}，本类按 TTL 轮询版本号失效缓存， 避免在
 * StepResolver 热路径（零 DB I/O 约定）每次查库。
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

    private volatile long lastCheckAt = 0L;
    private volatile long loadedVersion = -1L;
    private volatile Map<String, String> smsCache = Collections.emptyMap();
    private volatile Map<String, ChannelProperties.PushScript> pushCache = Collections.emptyMap();
    private volatile Map<String, List<ChannelProperties.PlanStepDef>> planCache =
            Collections.emptyMap();

    public ConfigTemplateProvider(
            ObjectProvider<JdbcTemplate> jdbcProvider,
            @Value("${channel.config.db-source-enabled:true}") boolean dbSourceEnabled,
            @Value("${channel.config.cache-ttl-ms:10000}") long cacheTtlMs) {
        this.jdbcTemplate = jdbcProvider.getIfAvailable();
        this.dbEnabled = dbSourceEnabled && this.jdbcTemplate != null;
        this.cacheTtlMs = cacheTtlMs;
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
        if (scriptSlot == null || !ensureFresh()) {
            return null;
        }
        return smsCache.get(scriptSlot);
    }

    /** DB 中的 Push title/body；未配置或 DB 不可用返回 {@code null}。 */
    public ChannelProperties.PushScript getPush(String scriptSlot) {
        if (scriptSlot == null || !ensureFresh()) {
            return null;
        }
        return pushCache.get(scriptSlot);
    }

    /** DB 中该 stage 的计划步骤；未配置或 DB 不可用返回 {@code null}。 */
    public List<ChannelProperties.PlanStepDef> getPlanSteps(String stageKey) {
        if (stageKey == null || !ensureFresh()) {
            return null;
        }
        return planCache.get(stageKey);
    }

    private boolean ensureFresh() {
        if (!dbEnabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (loadedVersion >= 0 && now - lastCheckAt < cacheTtlMs) {
            return true;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (loadedVersion >= 0 && now - lastCheckAt < cacheTtlMs) {
                return true;
            }
            lastCheckAt = now;
            try {
                long current = currentVersion();
                if (current != loadedVersion) {
                    reload();
                    loadedVersion = current;
                }
                return true;
            } catch (DataAccessException e) {
                log.warn(
                        "[ConfigTemplateProvider] DB config unavailable, fallback to YAML: {}",
                        e.getMessage());
                return loadedVersion >= 0;
            }
        }
    }

    private long currentVersion() {
        Long v =
                jdbcTemplate.queryForObject(
                        "SELECT current_version FROM t_config_version_seq WHERE id = 1",
                        Long.class);
        return v == null ? 0L : v;
    }

    private void reload() {
        Map<String, String> sms = new HashMap<>();
        jdbcTemplate.query(
                "SELECT script_slot, JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.body')) AS body "
                        + "FROM t_script_template WHERE tenant_id = ? AND channel = 'SMS' AND status = 'ACTIVE'",
                new Object[] {TENANT},
                rs -> {
                    String slot = rs.getString("script_slot");
                    String body = rs.getString("body");
                    if (slot != null && body != null && !"null".equals(body)) {
                        sms.put(slot, body);
                    }
                });

        Map<String, ChannelProperties.PushScript> push = new HashMap<>();
        jdbcTemplate.query(
                "SELECT script_slot, JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.title')) AS title, "
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
