package com.collection.admin.config;

import com.collection.service.support.ServiceClock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 数据库时钟一致性闸门。
 *
 * <p>参与比较的时间列已改为应用侧按 {@code Asia/Manila} 传参（{@link ServiceClock}），但 DLQ、收件箱、发件箱与回调审计 仍由库端 {@code
 * NOW()} 写入。若连接会话时区不是 PHT，这些列会与应用侧时间相差固定小时数，跨表取证与对账将失真 （历史故障：MySQL {@code system_time_zone=UTC}
 * 导致当日频控漏计 PHT 00:00–08:00、停摆宽限期恒被满足）。
 *
 * <p>因此启动时比对应用时钟与 {@code SELECT NOW()}：偏差超阈值时，{@code pilot} 拒绝启动，其余 profile 仅告警—— 本地与 CI
 * 允许连任意时区的库做语法验证，不应因此起不来。
 */
@Component
public class DatabaseClockValidator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseClockValidator.class);

    private final DataSource dataSource;
    private final Environment environment;
    private final long thresholdSeconds;

    public DatabaseClockValidator(
            DataSource dataSource,
            Environment environment,
            @Value("${collection.db.clock-drift-threshold-seconds:120}") long thresholdSeconds) {
        this.dataSource = dataSource;
        this.environment = environment;
        this.thresholdSeconds = thresholdSeconds;
    }

    @PostConstruct
    public void validate() {
        LocalDateTime dbNow;
        try {
            dbNow = readDatabaseNow();
        } catch (Exception e) {
            // 连不上库时交由数据源自身的健康检查处置，这里不追加一次启动失败。
            log.warn("[DbClock] 无法读取数据库时钟，跳过时区一致性校验：{}", e.getMessage());
            return;
        }
        LocalDateTime appNow = ServiceClock.now();
        long driftSeconds = Math.abs(Duration.between(dbNow, appNow).getSeconds());
        if (driftSeconds <= thresholdSeconds) {
            log.info("[DbClock] 数据库时钟与应用 PHT 时钟一致，偏差 {}s", driftSeconds);
            return;
        }
        String message =
                String.format(
                        "数据库时钟与应用 PHT 时钟偏差 %ds（阈值 %ds）：SELECT NOW()=%s，应用=%s。"
                                + "通常是连接会话时区未设为 +08:00，会让 NOW() 写入的审计列与应用侧时间错位；"
                                + "请检查 JDBC URL 的 connectionTimeZone/forceConnectionTimeZoneToSession 与 JVM 时区",
                        driftSeconds, thresholdSeconds, dbNow, appNow);
        if (environment.acceptsProfiles(Profiles.of("pilot"))) {
            throw new IllegalStateException(message);
        }
        log.warn("[DbClock] {}", message);
    }

    private LocalDateTime readDatabaseNow() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT NOW()");
                ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("SELECT NOW() 未返回结果");
            }
            Timestamp timestamp = rs.getTimestamp(1);
            if (timestamp == null) {
                throw new IllegalStateException("SELECT NOW() 返回 null");
            }
            return timestamp.toLocalDateTime();
        }
    }
}
