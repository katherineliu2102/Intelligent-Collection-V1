package com.collection.admin.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 允许携带 Cookie 的管理面来源。浏览器即使同源也会带 {@code Origin}，Spring CorsFilter 对不在名单里的 Origin 直接 403 Invalid CORS
 * request；生产域名漏配则登录页永远登不进去。
 */
@Configuration
public class AdminCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:4173",
                        "http://127.0.0.1:4173",
                        "https://collection-admin.mocasa.com")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
