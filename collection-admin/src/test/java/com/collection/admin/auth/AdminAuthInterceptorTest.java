package com.collection.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminAuthInterceptorTest {

    @Test
    void anonymousRequestReturnsJson401() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(request.getSession(false)).thenReturn(null);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();

        verify(response).setStatus(401);
        verify(response).setContentType("application/json;charset=UTF-8");
        assertThat(body.toString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void authenticatedGetPassesForViewer() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request = requestWithRole("VIEWER", "GET", "/dashboard/today");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void viewerWriteReturnsJson403() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request = requestWithRole("VIEWER", "POST", "/ops/exceptions/1/ack");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();

        verify(response).setStatus(403);
        assertThat(body.toString()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    void operatorHighRiskReturnsJson403() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request = requestWithRole("OPERATOR", "POST", "/ops/dlq/redrive");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void operatorDailyWritePasses() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request = requestWithRole("OPERATOR", "POST", "/compliance/freeze");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void adminHighRiskPasses() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request = requestWithRole("SYSTEM_ADMIN", "POST", "/ops/dlq/redrive");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void unknownRoleIsViewerAndCannotWrite() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request =
                requestWithRole("SUPERUSER", "PUT", "/config/script-templates");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void viewerCannotListAccounts() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request = requestWithRole("VIEWER", "GET", "/admin/accounts");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void adminCanListAccounts() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request = requestWithRole("SYSTEM_ADMIN", "GET", "/admin/accounts");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    private static HttpServletRequest requestWithRole(String role, String method, String path) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(AdminAuthInterceptor.SESSION_USER))
                .thenReturn(Collections.singletonMap("role", role));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(session);
        when(request.getMethod()).thenReturn(method);
        when(request.getServletPath()).thenReturn(path);
        return request;
    }

    private static AdminAuthInterceptor interceptor() {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());
        return interceptor;
    }
}
