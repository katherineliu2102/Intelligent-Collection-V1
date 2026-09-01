package com.collection.service.support;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 持久化时间基准。
 *
 * <p>凡参与「应用侧比较」的时间列（{@code t_contact_timeline.created_at} 用于当日频控、{@code t_contact_plan.updated_at}
 * 用于停摆宽限）一律由本类产出并作为参数下发，不再用 SQL 的 {@code NOW()}。 原因：MySQL 实例 {@code system_time_zone=UTC}，{@code
 * NOW()} 的取值随连接会话时区漂移，而引擎侧一律按 {@code Asia/Manila} 比较；实测同一行内 {@code executed_at} 与 {@code
 * updated_at} 会落在不同时区， 导致频控漏计与停摆宽限失效。会话时区仅作为其余审计列的兜底，不作为语义依据。
 */
public final class ServiceClock {

    /** 菲律宾市场唯一业务时区。 */
    public static final ZoneId PHT = ZoneId.of("Asia/Manila");

    private ServiceClock() {}

    /**
     * 当前 PHT 时间，截断到秒。
     *
     * <p>截断而非四舍五入：无精度声明的 {@code DATETIME} 列写入带小数秒的值时 MySQL 会进位，可能让 {@code created_at} 落在下一秒，破坏「写入值
     * == 落库值」这一取证前提。
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(PHT).truncatedTo(ChronoUnit.SECONDS);
    }
}
