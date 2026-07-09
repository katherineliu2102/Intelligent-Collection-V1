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

/** 最小登录拦截：基于 HttpSession。 */
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
        if (user != null) {
            return true;
        }
        response.setStatus(401);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = ApiResponse.failure("UNAUTHORIZED", "Login required");
        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }
}
