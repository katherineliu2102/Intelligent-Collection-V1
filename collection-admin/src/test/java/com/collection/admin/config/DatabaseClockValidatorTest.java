package com.collection.admin.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.collection.service.support.ServiceClock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 数据库时钟闸门：pilot 拒启、其余 profile 仅告警，连不上库不追加启动失败。 */
class DatabaseClockValidatorTest {

    private static final long THRESHOLD_SECONDS = 120L;

    @Test
    void passesWhenDatabaseClockMatchesPhtClock() {
        DatabaseClockValidator validator = validator("pilot", ServiceClock.now());

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    /** UTC 库对 PHT 应用即 8 小时偏差，这正是 L3-7 暴露的故障形态。 */
    @Test
    void rejectsPilotStartupWhenSessionTimeZoneIsUtc() {
        DatabaseClockValidator validator = validator("pilot", ServiceClock.now().minusHours(8));

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("偏差")
                .hasMessageContaining("connectionTimeZone");
    }

    @Test
    void onlyWarnsOutsidePilot() {
        DatabaseClockValidator validator = validator("local", ServiceClock.now().minusHours(8));

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void skipsCheckWhenDatabaseUnreachable() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        DatabaseClockValidator validator =
                new DatabaseClockValidator(dataSource, environment("pilot"), THRESHOLD_SECONDS);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    private DatabaseClockValidator validator(String profile, LocalDateTime databaseNow) {
        return new DatabaseClockValidator(
                dataSource(databaseNow), environment(profile), THRESHOLD_SECONDS);
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    private DataSource dataSource(LocalDateTime databaseNow) {
        try {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getTimestamp(1)).thenReturn(Timestamp.valueOf(databaseNow));

            PreparedStatement statement = mock(PreparedStatement.class);
            when(statement.executeQuery()).thenReturn(resultSet);

            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return dataSource;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
