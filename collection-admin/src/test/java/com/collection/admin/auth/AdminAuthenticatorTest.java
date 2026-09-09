package com.collection.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminAuthenticatorTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static AdminAuthProperties.Account account(
            String user, String rawPassword, String role) {
        AdminAuthProperties.Account a = new AdminAuthProperties.Account();
        a.setUsername(user);
        a.setPasswordHash(ENCODER.encode(rawPassword));
        a.setRole(role);
        return a;
    }

    private static AdminAuthenticator authenticator(AdminAuthProperties.Account... accounts) {
        AdminAuthProperties props = new AdminAuthProperties();
        props.setAccounts(Arrays.asList(accounts));
        return new AdminAuthenticator(props);
    }

    @Test
    void acceptsCorrectPasswordAndCarriesConfiguredRole() {
        AdminAuthenticator auth = authenticator(account("ops", "s3cret", "SYSTEM_ADMIN"));

        Optional<Map<String, Object>> user = auth.authenticate("ops", "s3cret");

        assertThat(user).isPresent();
        assertThat(user.get())
                .containsEntry("username", "ops")
                .containsEntry("role", "SYSTEM_ADMIN");
    }

    @Test
    void acceptsEachIndependentlyConfiguredAccount() {
        AdminAuthenticator auth =
                authenticator(
                        account("admin-01", "password-01", "SYSTEM_ADMIN"),
                        account("admin-02", "password-02", "SYSTEM_ADMIN"),
                        account("admin-03", "password-03", "SYSTEM_ADMIN"));

        assertThat(auth.authenticate("admin-01", "password-01")).isPresent();
        assertThat(auth.authenticate("admin-02", "password-02")).isPresent();
        assertThat(auth.authenticate("admin-03", "password-03")).isPresent();
        assertThat(auth.authenticate("admin-01", "password-02")).isEmpty();
    }

    @Test
    void rejectsWrongPassword() {
        AdminAuthenticator auth = authenticator(account("ops", "s3cret", "SYSTEM_ADMIN"));

        assertThat(auth.authenticate("ops", "wrong")).isEmpty();
    }

    @Test
    void rejectsUnknownUser() {
        AdminAuthenticator auth = authenticator(account("ops", "s3cret", "SYSTEM_ADMIN"));

        assertThat(auth.authenticate("intruder", "s3cret")).isEmpty();
    }

    /** 这是 2026-08-25 之前的实际行为：请求体里写什么角色就发什么会话，口令根本不看。 该用例锁住"角色由配置决定，不由请求决定"。 */
    @Test
    void roleComesFromConfigNotFromRequest() {
        AdminAuthenticator auth = authenticator(account("ops", "s3cret", "VIEWER"));

        Optional<Map<String, Object>> user = auth.authenticate("ops", "s3cret");

        assertThat(user).isPresent();
        assertThat(user.get()).containsEntry("role", "VIEWER");
    }

    @Test
    void rejectsBlankCredentials() {
        AdminAuthenticator auth = authenticator(account("ops", "s3cret", "SYSTEM_ADMIN"));

        assertThat(auth.authenticate(null, "s3cret")).isEmpty();
        assertThat(auth.authenticate("ops", null)).isEmpty();
        assertThat(auth.authenticate("ops", "  ")).isEmpty();
    }

    /** 没配账号时不能退化成"人人可登录"。 */
    @Test
    void rejectsEverythingWhenNoAccountConfigured() {
        AdminAuthenticator auth = authenticator();

        assertThat(auth.authenticate("ops", "s3cret")).isEmpty();
        assertThat(auth.hasUsableAccount()).isFalse();
    }

    /** 配了用户名但哈希为空的条目视为不可用，否则启动闸门会被一个登不进去的账号骗过。 */
    @Test
    void accountWithBlankHashIsNotUsable() {
        AdminAuthProperties.Account blank = new AdminAuthProperties.Account();
        blank.setUsername("ops");
        blank.setPasswordHash("  ");
        AdminAuthProperties props = new AdminAuthProperties();
        props.setAccounts(Collections.singletonList(blank));
        AdminAuthenticator auth = new AdminAuthenticator(props);

        assertThat(auth.hasUsableAccount()).isFalse();
        assertThat(auth.authenticate("ops", "anything")).isEmpty();
    }

    @Test
    void hasUsableAccountWhenConfigured() {
        assertThat(authenticator(account("ops", "s3cret", "SYSTEM_ADMIN")).hasUsableAccount())
                .isTrue();
    }

    /**
     * htpasswd -B 产出的是 {@code $2y$} 前缀，Spring 的 BCryptPasswordEncoder 也接受 {@code $2a$}/{@code
     * $2b$}。文档里给的生成命令是 htpasswd，故锁住 2y 能被校验通过。
     */
    @Test
    void acceptsHtpasswdStyle2yHash() {
        AdminAuthProperties.Account a = new AdminAuthProperties.Account();
        a.setUsername("admin");
        // htpasswd -bnBC 10 admin local-dev
        a.setPasswordHash("$2y$10$gqISHaFT7yTL7J3kx7LfYONWbirhLLbQ71/CyVVLlXJ40b6TZbMDm");
        AdminAuthProperties props = new AdminAuthProperties();
        props.setAccounts(Collections.singletonList(a));

        assertThat(new AdminAuthenticator(props).authenticate("admin", "local-dev")).isPresent();
    }
}
