package com.collection.admin.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理面凭据校验。
 *
 * <p>2026-08-25 之前 {@code AuthController} 不校验任何口令，请求体里写什么用户名角色就发什么会话； 该端口当时还绑在 {@code
 * 0.0.0.0}，等于把债务人 PII 与 DLQ 重放敞开给互联网（台账 F8）。 端口已收回环回，此处补上锁本身。
 */
@Slf4j
@Component
public class AdminAuthenticator {

    /** 用户名不存在时也要跑一次 BCrypt，否则「立即返回」与「算 100ms」的耗时差可用来枚举有效用户名。 这是一个固定的无效哈希，任何口令都匹配不上，只为把两条路径的耗时拉平。 */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AdminAuthProperties properties;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminAuthenticator(AdminAuthProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验用户名口令。
     *
     * @return 通过时返回写入会话的用户信息，否则 {@link Optional#empty()}
     */
    public Optional<Map<String, Object>> authenticate(String username, String password) {
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            return Optional.empty();
        }
        AdminAuthProperties.Account account =
                properties.getAccounts().stream()
                        .filter(a -> username.equals(a.getUsername()))
                        .filter(a -> StringUtils.isNotBlank(a.getPasswordHash()))
                        .findFirst()
                        .orElse(null);

        String hash = account == null ? DUMMY_HASH : account.getPasswordHash();
        boolean matched = encoder.matches(password, hash);

        if (account == null || !matched) {
            // 不区分「用户名不存在」与「口令错误」，对外只有一种失败。
            log.warn("[AdminAuth] 登录失败 username={}", username);
            return Optional.empty();
        }

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", account.getUsername());
        user.put("role", account.getRole());
        log.info("[AdminAuth] 登录成功 username={} role={}", account.getUsername(), account.getRole());
        return Optional.of(user);
    }

    /** 是否配了可用账号。供启动闸门判断，避免带着"无人可登录"或"人人可登录"的状态上线。 */
    public boolean hasUsableAccount() {
        return properties.getAccounts().stream()
                .anyMatch(
                        a ->
                                StringUtils.isNotBlank(a.getUsername())
                                        && StringUtils.isNotBlank(a.getPasswordHash()));
    }
}
