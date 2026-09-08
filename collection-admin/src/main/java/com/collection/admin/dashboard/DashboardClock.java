package com.collection.admin.dashboard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 看板时间窗一律 PHT（Asia/Manila），禁止依赖 JVM 默认时区。 */
public final class DashboardClock {

    public static final ZoneId PHT = ZoneId.of("Asia/Manila");

    private DashboardClock() {}

    public static LocalDate today() {
        return LocalDate.now(PHT);
    }

    public static LocalDateTime todayStart() {
        return today().atStartOfDay();
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(PHT);
    }
}
