package com.collection.admin.config;

import com.collection.admin.web.WebhookSecurityProperties;
import com.collection.channel.config.ChannelProperties;
import com.collection.channel.gateway.MockChannelGateway;
import com.collection.channel.strategy.MockExecutionGuard;
import com.collection.common.channel.ChannelGateway;
import com.collection.common.service.CaseService;
import com.collection.common.spi.ExecutionGuard;
import com.collection.ingestion.config.IngestionProperties;
import com.collection.service.impl.RealCaseService;
import javax.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Pilot 启动闸门：任何真实依赖、白名单或密钥缺失均禁止进程就绪。 */
@Component
@Profile("pilot")
public class PilotReadinessValidator {

    private final CaseService caseService;
    private final ChannelGateway channelGateway;
    private final ExecutionGuard executionGuard;
    private final IngestionProperties ingestionProperties;
    private final WebhookSecurityProperties webhookProperties;
    private final ChannelProperties channelProperties;

    public PilotReadinessValidator(
            CaseService caseService,
            ChannelGateway channelGateway,
            ExecutionGuard executionGuard,
            IngestionProperties ingestionProperties,
            WebhookSecurityProperties webhookProperties,
            ChannelProperties channelProperties) {
        this.caseService = caseService;
        this.channelGateway = channelGateway;
        this.executionGuard = executionGuard;
        this.ingestionProperties = ingestionProperties;
        this.webhookProperties = webhookProperties;
        this.channelProperties = channelProperties;
    }

    @PostConstruct
    public void validate() {
        require(
                caseService instanceof RealCaseService,
                "Pilot requires collection.case-service=real");
        require(
                ingestionProperties.getLoanIdWhitelist() != null
                        && !ingestionProperties.getLoanIdWhitelist().isEmpty(),
                "Pilot requires a non-empty collection.ingestion.loan-id-whitelist");
        require(
                !(channelGateway instanceof MockChannelGateway)
                        && !channelProperties.isFallbackToMock(),
                "Pilot requires a real ChannelGateway and channel.fallback-to-mock=false");
        require(
                !(executionGuard instanceof MockExecutionGuard),
                "Pilot requires a real ExecutionGuard");
        require(
                webhookProperties.isSignatureRequired()
                        && StringUtils.isNotBlank(webhookProperties.getHmacSecret()),
                "Pilot requires collection.webhook signature verification and HMAC secret");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
