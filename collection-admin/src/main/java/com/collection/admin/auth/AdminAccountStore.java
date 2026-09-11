package com.collection.admin.auth;

import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 管理账号落库。表空时从 env 种子一次，之后以本表为准。 */
@Component
public class AdminAccountStore implements ApplicationRunner {

    static final String CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS t_admin_account ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "username VARCHAR(64) NOT NULL, "
                    + "password_hash VARCHAR(100) NOT NULL, "
                    + "role VARCHAR(32) NOT NULL, "
                    + "enabled TINYINT(1) NOT NULL DEFAULT 1, "
                    + "created_by VARCHAR(64) NULL, "
                    + "updated_by VARCHAR(64) NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "UNIQUE KEY uk_username (username)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private final JdbcTemplate jdbcTemplate;
    private final AdminAuthProperties properties;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private volatile boolean schemaReady;

    public AdminAccountStore(JdbcTemplate jdbcTemplate, AdminAuthProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureSchema();
        seedFromEnvIfEmpty();
    }

    public synchronized void ensureSchema() {
        if (schemaReady) {
            return;
        }
        jdbcTemplate.execute(CREATE_SQL);
        schemaReady = true;
    }

    public boolean hasAnyRow() {
        ensureSchema();
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM t_admin_account", Long.class);
        return n != null && n > 0;
    }

    public boolean hasEnabled() {
        ensureSchema();
        Long n =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM t_admin_account WHERE enabled = 1", Long.class);
        return n != null && n > 0;
    }

    public AccountRow findByUsername(String username) {
        ensureSchema();
        List<AccountRow> rows =
                jdbcTemplate.query(
                        "SELECT id, username, password_hash, role, enabled FROM t_admin_account WHERE username = ?",
                        (rs, i) -> {
                            AccountRow row = new AccountRow();
                            row.setId(rs.getLong("id"));
                            row.setUsername(rs.getString("username"));
                            row.setPasswordHash(rs.getString("password_hash"));
                            row.setRole(rs.getString("role"));
                            row.setEnabled(rs.getInt("enabled") == 1);
                            return row;
                        },
                        username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listPublic() {
        ensureSchema();
        return jdbcTemplate.query(
                "SELECT id, username, role, enabled, created_at, updated_at, updated_by "
                        + "FROM t_admin_account ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("username", rs.getString("username"));
                    row.put("role", rs.getString("role"));
                    row.put("enabled", rs.getInt("enabled") == 1);
                    row.put("createdAt", rs.getTimestamp("created_at"));
                    row.put("updatedAt", rs.getTimestamp("updated_at"));
                    row.put("updatedBy", rs.getString("updated_by"));
                    return row;
                });
    }

    public void create(String username, String rawPassword, String role, String operator) {
        ensureSchema();
        if (StringUtils.isBlank(username) || username.trim().length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username too short");
        }
        if (StringUtils.isBlank(rawPassword) || rawPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at least 8 characters");
        }
        String normalized = AdminRole.parse(role).name();
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_admin_account(username, password_hash, role, enabled, created_by, updated_by) "
                            + "VALUES (?,?,?,1,?,?)",
                    username.trim(),
                    encoder.encode(rawPassword),
                    normalized,
                    operator,
                    operator);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists");
        }
    }

    public void patch(long id, Boolean enabled, String role, String rawPassword, String operator) {
        ensureSchema();
        AccountRow current = findById(id);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found");
        }
        boolean nextEnabled = enabled == null ? current.isEnabled() : enabled;
        String nextRole = role == null ? current.getRole() : AdminRole.parse(role).name();
        assertNotLastAdminLockout(current, nextEnabled, nextRole);

        if (rawPassword != null) {
            if (rawPassword.length() < 8) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "password must be at least 8 characters");
            }
            jdbcTemplate.update(
                    "UPDATE t_admin_account SET password_hash = ?, updated_by = ? WHERE id = ?",
                    encoder.encode(rawPassword),
                    operator,
                    id);
        }
        jdbcTemplate.update(
                "UPDATE t_admin_account SET enabled = ?, role = ?, updated_by = ? WHERE id = ?",
                nextEnabled ? 1 : 0,
                nextRole,
                operator,
                id);
    }

    private void assertNotLastAdminLockout(AccountRow current, boolean nextEnabled, String nextRole) {
        boolean wasAdmin =
                current.isEnabled() && AdminRole.SYSTEM_ADMIN == AdminRole.parse(current.getRole());
        boolean stayAdmin = nextEnabled && AdminRole.SYSTEM_ADMIN == AdminRole.parse(nextRole);
        if (wasAdmin && !stayAdmin && countEnabledAdmins() <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "cannot disable or demote the last SYSTEM_ADMIN");
        }
    }

    private long countEnabledAdmins() {
        Long n =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM t_admin_account WHERE enabled = 1 AND role = 'SYSTEM_ADMIN'",
                        Long.class);
        return n == null ? 0 : n;
    }

    private AccountRow findById(long id) {
        List<AccountRow> rows =
                jdbcTemplate.query(
                        "SELECT id, username, password_hash, role, enabled FROM t_admin_account WHERE id = ?",
                        (rs, i) -> {
                            AccountRow row = new AccountRow();
                            row.setId(rs.getLong("id"));
                            row.setUsername(rs.getString("username"));
                            row.setPasswordHash(rs.getString("password_hash"));
                            row.setRole(rs.getString("role"));
                            row.setEnabled(rs.getInt("enabled") == 1);
                            return row;
                        },
                        id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    void seedFromEnvIfEmpty() {
        ensureSchema();
        if (hasAnyRow()) {
            return;
        }
        for (AdminAuthProperties.Account account : properties.getAccounts()) {
            if (account == null
                    || StringUtils.isBlank(account.getUsername())
                    || StringUtils.isBlank(account.getPasswordHash())) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO t_admin_account(username, password_hash, role, enabled, created_by, updated_by) "
                            + "VALUES (?,?,?,1,'env-seed','env-seed')",
                    account.getUsername().trim(),
                    account.getPasswordHash().trim(),
                    AdminRole.parse(account.getRole()).name());
        }
    }

    @Data
    public static class AccountRow {
        private Long id;
        private String username;
        private String passwordHash;
        private String role;
        private boolean enabled;
    }
}
