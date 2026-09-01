package com.collection.admin.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 到期 / 超时扫描的配置。承载 {@code collection.scan.*}。 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.scan")
public class ScanProperties {

    /** 本地 / CI 的 {@code @Scheduled} 扫描间隔；生产走 Cloud Scheduler，不读该值。 */
    private long intervalMs = 5000;

    /**
     * 允许扫描的 case_id；空 = 扫描全库到期步骤。
     *
     * <p>扫描 SQL 没有任何租户维度，因此「连上哪个库」就等于「有权对该库全部案件发起触达」。 共享库上多个实例会互相抢步骤：2026-08-21
     * 实测本机应用停机期间，插入的到期步骤仍在 2 分钟内 被另一实例抢占并改写为 EXECUTING。故 local / test / pilot profile
     * 下本名单**必须显式配置**， 由 {@link ScanIsolationGuard} 在启动时强制。
     */
    private List<Long> caseIdWhitelist = new ArrayList<>();

    /**
     * 显式放开全库扫描，仅在 T6 全量切换时置 true。
     *
     * <p>在此之前「本轮批准处理多少案件」由 {@link #caseIdWhitelist} 表达（T4 是 50 案，T5 每批放量改名单）， 空名单一律按配置遗漏处理而非全量意图。
     */
    private boolean allowFullScan = false;

    /** 扫描时下发给仓储的案件过滤；空名单返回 null 表示不过滤。 */
    public List<Long> caseFilter() {
        return caseIdWhitelist == null || caseIdWhitelist.isEmpty() ? null : caseIdWhitelist;
    }
}
