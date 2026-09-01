package com.collection.admin.config;

import java.util.Arrays;
import java.util.List;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 扫描隔离闸门：{@code local} / {@code test} 禁止空案件名单启动；Pilot 空名单告警放行。
 *
 * <p>与 {@code IngestionIsolationGuard} 同源的理由，但堵的是另一个入口：接入闸门只管「从 Pub/Sub 收哪些案件」，
 * 而到期扫描直接读库，不经过接入。共享库上这意味着任何实例都会捞走全库到期步骤并对真实案件发起触达—— 2026-08-21 已实测：本机应用停机期间插入的到期步骤，2
 * 分钟内被另一实例抢占并改写为 EXECUTING。
 *
 * <p>Pilot 是单实例，范围跟订阅进件与库内活跃计划；加量/换名单走数仓 Publisher。local / test 仍强制非空。
 */
@Component
public class ScanIsolationGuard {

    private static final Logger log = LoggerFactory.getLogger(ScanIsolationGuard.class);
    private static final List<String> GUARDED_PROFILES = Arrays.asList("local", "test", "pilot");

    private final ScanProperties props;
    private final Environment environment;

    public ScanIsolationGuard(ScanProperties props, Environment environment) {
        this.props = props;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (!isGuardedProfile()) {
            return;
        }
        if (props.getCaseIdWhitelist() == null || props.getCaseIdWhitelist().isEmpty()) {
            if (props.isAllowFullScan() || isPilotProfile()) {
                log.warn(
                        "[Scan] 扫描名单为空（allow-full-scan={} profile={}）——"
                                + "本实例将对该库全部到期步骤发起触达；范围跟订阅进件与活跃计划",
                        props.isAllowFullScan(),
                        String.join(",", environment.getActiveProfiles()));
                return;
            }
            throw new IllegalStateException(
                    "拒绝启动：profile="
                            + String.join(",", environment.getActiveProfiles())
                            + " 未配置 collection.scan.case-id-whitelist。"
                            + "到期扫描不经过接入白名单、也没有租户维度，空名单等于对该库全部案件发起触达；"
                            + "共享库上还会与其他实例互抢步骤。请显式列出本轮批准的 case_id"
                            + "（环境变量 COLLECTION_SCAN_CASE_IDS，逗号分隔）；"
                            + "确需全量时置 collection.scan.allow-full-scan=true。");
        }
        log.info(
                "[Scan] 隔离闸门通过 — 只扫描 {} 个案件: {}",
                props.getCaseIdWhitelist().size(),
                props.getCaseIdWhitelist());
    }

    private boolean isGuardedProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (GUARDED_PROFILES.contains(profile)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPilotProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("pilot".equals(profile)) {
                return true;
            }
        }
        return false;
    }
}
