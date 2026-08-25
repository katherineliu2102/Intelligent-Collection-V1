package com.collection.channel.config;

import com.collection.channel.client.InsecureSimpleClientHttpRequestFactory;
import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** 渠道模块 Spring 配置：HTTP 客户端、配置属性扫描。 */
@Configuration
public class ChannelAutoConfiguration {

    @Bean
    public RestTemplate channelRestTemplate(RestTemplateBuilder builder) {
        return builder.setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Facade 专用客户端。仅 local/test 可启用 {@code channel.facade.insecure-tls}，以支持供应商联调环境的自签名证书； 该设置不影响
     * JVM 全局 TLS。
     */
    @Bean
    public RestTemplate facadeRestTemplate(ChannelProperties properties) {
        ChannelProperties.Facade facade = properties.getFacade();
        int connectMs = Math.max(1, facade.getConnectTimeoutSeconds()) * 1000;
        int readMs = Math.max(1, facade.getReadTimeoutSeconds()) * 1000;
        SimpleClientHttpRequestFactory factory;
        if (facade.isInsecureTls()) {
            factory = new InsecureSimpleClientHttpRequestFactory();
        } else {
            factory = new SimpleClientHttpRequestFactory();
        }
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }
}
