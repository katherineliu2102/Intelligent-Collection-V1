package com.collection.admin.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Pilot XXL 执行器；调度中心地址和 token 仅由 Nacos/环境变量提供。 */
@Configuration
@ConditionalOnProperty(prefix = "collection.xxl", name = "enabled", havingValue = "true")
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(
            @Value("${collection.xxl.admin-addresses}") String adminAddresses,
            @Value("${collection.xxl.app-name}") String appName,
            @Value("${collection.xxl.access-token}") String accessToken,
            @Value("${collection.xxl.executor-address:}") String executorAddress,
            @Value("${collection.xxl.executor-ip:}") String executorIp,
            @Value("${collection.xxl.executor-port:9999}") int executorPort,
            @Value("${collection.xxl.log-path:/data/applogs/xxl-job}") String logPath,
            @Value("${collection.xxl.log-retention-days:30}") int logRetentionDays) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appName);
        executor.setAccessToken(accessToken);
        executor.setAddress(executorAddress);
        executor.setIp(executorIp);
        executor.setPort(executorPort);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
