package com.collection.admin.web;

import com.collection.admin.auth.AdminAuthInterceptor;
import com.collection.admin.auth.AdminAuthenticator;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理面登录。凭据来自 {@code collection.admin.auth.accounts}，口令以 BCrypt 哈希配置。 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AdminAuthenticator authenticator;

    public AuthController(AdminAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @PostMapping("/login")
    public Map<String, Object> login(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestBody(required = false) Map<String, Object> body) {
        String username = body == null ? null : str(body.get("username"));
        String password = body == null ? null : str(body.get("password"));

        Optional<Map<String, Object>> user = authenticator.authenticate(username, password);
        if (!user.isPresent()) {
            response.setStatus(401);
            return ApiResponse.failure("UNAUTHORIZED", "Invalid credentials");
        }

        // 换一个新 session id，避免固定会话攻击：攻击者预置的 JSESSIONID 不应在登录后继承权限。
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        request.getSession(true).setAttribute(AdminAuthInterceptor.SESSION_USER, user.get());
        return ApiResponse.success(user.get());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        return ApiResponse.success("OK");
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
