package com.collection.channel.gateway;

import com.collection.channel.adapter.ChannelAdapter;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.channel.ChannelGateway;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.service.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行子层 Gateway：渠道幂等 + Adapter 路由。
 *
 * <p>未注册 Adapter 的渠道委托 {@link MockChannelGateway}，便于渐进替换。
 * HUMAN_CALL 禁止路由（对齐待办 E4）。
 */
@Primary
@Component
public class ChannelGatewayImpl implements ChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(ChannelGatewayImpl.class);
    private static final String IDEMPOTENCY_PREFIX = "channel:";

    @Resource
    private List<ChannelAdapter> adapters;

    @Resource
    private MockChannelGateway mockChannelGateway;

    @Resource
    private IdempotencyService idempotencyService;

    @Resource
    private ChannelProperties channelProperties;

    private Map<ChannelType, ChannelAdapter> adapterMap = new ConcurrentHashMap<>();

    /** 渠道幂等：重复 dispatch 返回首次结果（内存缓存，与 IdempotencyService TTL 对齐）。 */
    private final Map<String, StepResult> dispatchCache = new ConcurrentHashMap<>();

    @PostConstruct
    void initAdapterMap() {
        adapterMap = adapters.stream()
                .collect(Collectors.toMap(ChannelAdapter::channelType, Function.identity(), (a, b) -> a));
        log.info("[ChannelGatewayImpl] registered adapters: {}", adapterMap.keySet());
    }

    @Override
    public StepResult dispatch(StepCommand command) {
        if (command.getChannelType() == ChannelType.HUMAN_CALL) {
            throw new IllegalStateException("Phase 1 禁止 HUMAN_CALL 路由（对齐待办 E4）");
        }

        String idempotencyKey = command.getIdempotencyKey();
        String lockKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        int ttlMinutes = channelProperties.getIdempotencyTtlHours() * 60;

        if (!idempotencyService.acquire(lockKey, ttlMinutes)) {
            StepResult cached = dispatchCache.get(idempotencyKey);
            if (cached != null) {
                log.info("[ChannelGatewayImpl] duplicate dispatch, return cached key={}", idempotencyKey);
                return cached;
            }
            return StepResult.builder()
                    .success(true)
                    .contactResult(ContactResult.DELIVERED)
                    .retryable(false)
                    .providerMsgId("dedup:" + idempotencyKey)
                    .build();
        }

        Object caseId = command.getMetadata() != null
                ? command.getMetadata().get(StepCommand.META_CASE_ID) : null;
        log.info("[ChannelGatewayImpl] dispatch channel={} caseId={} key={}",
                command.getChannelType(), caseId, idempotencyKey);

        StepResult result = route(command);
        if (result.isSuccess()) {
            dispatchCache.put(idempotencyKey, result);
        }
        return result;
    }

    private StepResult route(StepCommand command) {
        ChannelAdapter adapter = adapterMap.get(command.getChannelType());
        if (adapter != null) {
            StepResult result = adapter.send(command);
            if (shouldFallbackToMock(result)) {
                log.warn("[ChannelGatewayImpl] adapter not configured for {}, use mock", command.getChannelType());
                return mockChannelGateway.dispatch(command);
            }
            return result;
        }
        log.debug("[ChannelGatewayImpl] no adapter for {}, use mock", command.getChannelType());
        return mockChannelGateway.dispatch(command);
    }

    /** 本地/Nacos 未配密钥时回退 Mock，避免阻断 Mock 链路验收。 */
    private static boolean shouldFallbackToMock(StepResult result) {
        if (result == null || result.isSuccess()) {
            return false;
        }
        String code = result.getErrorCode();
        return code != null && code.endsWith("_NOT_CONFIGURED");
    }
}
