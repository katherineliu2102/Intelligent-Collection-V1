package com.collection.channel.config;

import com.collection.channel.client.InsecureSimpleClientHttpRequestFactory;
import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 渠道模块 Spring 配置：HTTP 客户端、配置属性扫描。
 */
@Configuration
public class ChannelAutoConfiguration {

    @Bean
    public RestTemplate channelRestTemplate(RestTemplateBuilder builder) {
        return builder.setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Facade 专用客户端。{@code channel.facade.insecure-tls=true} 时信任自签名，且不改 JVM 全局 SSL。
     */
    @Bean
    public RestTemplate facadeRestTemplate(ChannelProperties properties) {
        ChannelProperties.Facade facade = properties.getFacade();
        int connectMs = Math.max(1, facade.getConnectTimeoutSeconds()) * 1000;
        int readMs = Math.max(1, facade.getReadTimeoutSeconds()) * 1000;
        if (facade.isInsecureTls()) {
            InsecureSimpleClientHttpRequestFactory factory =
                    new InsecureSimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectMs);
            factory.setReadTimeout(readMs);
            return new RestTemplate(factory);
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }
}
