package com.collection.admin.web;

import com.collection.admin.auth.AdminAuthInterceptor;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 1 最小登录（开发态）。 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @PostMapping("/login")
    public Map<String, Object> login(
            HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        String username = body == null ? "admin" : String.valueOf(body.getOrDefault("username", "admin"));
        String role = body == null ? "SYSTEM_ADMIN" : String.valueOf(body.getOrDefault("role", "SYSTEM_ADMIN"));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("role", role);
        request.getSession(true).setAttribute(AdminAuthInterceptor.SESSION_USER, user);
        return ApiResponse.success(user);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        return ApiResponse.success("OK");
    }
}

