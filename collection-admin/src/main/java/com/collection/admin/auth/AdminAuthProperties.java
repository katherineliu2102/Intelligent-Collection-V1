package com.collection.admin.auth;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理面登录账号。口令只以 BCrypt 哈希形式配置，明文不进仓库、不进配置中心。
 *
 * <p>YAML 形态：
 *
 * <pre>
 * collection:
 *   admin:
 *     auth:
 *       accounts:
 *         - username: ops
 *           password-hash: $2a$10$....
 *           role: SYSTEM_ADMIN
 * </pre>
 *
 * <p>环境变量形态用 Spring 的松散绑定：{@code COLLECTION_ADMIN_AUTH_ACCOUNTS_0_USERNAME}、 {@code
 * ..._0_PASSWORD_HASH}、{@code ..._0_ROLE}。
 *
 * <p><b>写进 {@code pilot.env} 时哈希必须用单引号包裹</b>——BCrypt 哈希形如 {@code $2a$10$...}， 而部署脚本是
 * {@code set -a; . "$ENV_FILE"} 走 shell 解析的，不加引号时 {@code $2a}、{@code $10} 会被当变量展开成空串，
 * 结果是一个永远匹配不上的残缺哈希，且表现为"口令正确却登录失败"，很难往这上面想。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.admin.auth")
public class AdminAuthProperties {

    private List<Account> accounts = new ArrayList<>();

    @Data
    public static class Account {
        private String username;
        private String passwordHash;
        private String role = "SYSTEM_ADMIN";
    }
}
