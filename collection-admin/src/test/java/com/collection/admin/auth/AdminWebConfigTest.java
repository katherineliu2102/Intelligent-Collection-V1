package com.collection.admin.auth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class AdminWebConfigTest {

    @Test
    void protectsEveryManagementRouteAndKeepsAuthPublic() {
        AdminAuthInterceptor interceptor = mock(AdminAuthInterceptor.class);
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(interceptor)).thenReturn(registration);
        when(registration.addPathPatterns(
                        "/cases/**",
                        "/compliance/**",
                        "/ops/**",
                        "/admin/**",
                        "/config/**",
                        "/dashboard/**",
                        "/plans/**",
                        "/catalog/**"))
                .thenReturn(registration);

        new AdminWebConfig(interceptor).addInterceptors(registry);

        verify(registration)
                .addPathPatterns(
                        "/cases/**",
                        "/compliance/**",
                        "/ops/**",
                        "/admin/**",
                        "/config/**",
                        "/dashboard/**",
                        "/plans/**",
                        "/catalog/**");
        verify(registration).excludePathPatterns("/auth/**");
    }
}
