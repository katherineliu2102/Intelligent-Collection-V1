package com.collection.admin.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 外部渠道回调验签配置。生产默认 fail-closed；local/test 可显式关闭。 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.webhook")
public class WebhookSecurityProperties {

    private boolean signatureRequired = true;
    private String hmacSecret;
}
