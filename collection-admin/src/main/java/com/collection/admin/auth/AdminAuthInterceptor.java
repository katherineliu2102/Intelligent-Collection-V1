package com.collection.admin.auth;

import com.collection.admin.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 登录拦截 + 第一刀三角色授权。未登录 401，已登录越权 403。 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_USER = "ADMIN_USER";

    @Resource private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Object user =
                request.getSession(false) != null
                        ? request.getSession(false).getAttribute(SESSION_USER)
                        : null;
        if (user == null) {
            writeJson(response, 401, ApiResponse.failure("UNAUTHORIZED", "Login required"));
            return false;
        }
        AdminRole role = AdminRole.fromSessionUser(user);
        if (!AdminAccessPolicy.allows(role, request.getMethod(), request.getServletPath())) {
            writeJson(response, 403, ApiResponse.failure("FORBIDDEN", "Insufficient role"));
            return false;
        }
        return true;
    }

    private void writeJson(HttpServletResponse response, int status, Map<String, Object> body)
            throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
