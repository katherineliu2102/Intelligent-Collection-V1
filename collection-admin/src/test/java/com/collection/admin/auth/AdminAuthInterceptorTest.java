package com.collection.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
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
    void authenticatedSessionPasses() throws Exception {
        AdminAuthInterceptor interceptor = interceptor();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(AdminAuthInterceptor.SESSION_USER)).thenReturn(new Object());

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    private static AdminAuthInterceptor interceptor() {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());
        return interceptor;
    }
}
