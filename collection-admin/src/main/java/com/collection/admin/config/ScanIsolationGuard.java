package com.collection.admin.config;

import java.util.Arrays;
import java.util.List;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 扫描隔离闸门：{@code local} / {@code test} / {@code pilot} profile 下禁止空案件名单启动。
 *
 * <p>与 {@code IngestionIsolationGuard} 同源的理由，但堵的是另一个入口：接入闸门只管「从 Pub/Sub 收哪些案件」，
 * 而到期扫描直接读库，不经过接入。共享库上这意味着任何实例都会捞走全库到期步骤并对真实案件发起触达—— 2026-08-21 已实测：本机应用停机期间插入的到期步骤，2
 * 分钟内被另一实例抢占并改写为 EXECUTING。
 *
 * <p>Pilot 同样受约束：{@code PilotReadinessValidator} 只校验接入白名单，若扫描名单留空，「固定 50 案」 在扫描这一侧并不成立。T6 全量切换时用
 * {@code collection.scan.allow-full-scan=true} 显式放开。
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
            if (props.isAllowFullScan()) {
                log.warn(
                        "[Scan] 全库扫描已显式放开（collection.scan.allow-full-scan=true）—— "
                                + "本实例将对 profile={} 所连库的全部到期步骤发起触达",
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
}
