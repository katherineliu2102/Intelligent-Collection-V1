package com.collection.admin.alert;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 告警通道配置。webhook 不入库、不进前端（设计文档 §5.5.4）。 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.alert")
public class AlertProperties {

    private Dingtalk dingtalk = new Dingtalk();

    @Data
    public static class Dingtalk {
        /** Nacos / 环境变量 {@code collection.alert.dingtalk.webhook}；未配置只打日志不抛。 */
        private String webhook = "";
    }
}
