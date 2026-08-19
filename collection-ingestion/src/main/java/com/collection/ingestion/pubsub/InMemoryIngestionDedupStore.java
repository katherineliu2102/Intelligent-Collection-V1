package com.collection.ingestion.pubsub;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 本地与 CI 用：进程内存，跨重启丢失、跨实例不共享，因此不可用于 Pilot / 生产。 */
@Component
@ConditionalOnProperty(
        prefix = "collection.ingestion",
        name = "redis-dedup-enabled",
        havingValue = "false",
        matchIfMissing = true)
public class InMemoryIngestionDedupStore implements IngestionDedupStore {

    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastSeenPublishMillis = new ConcurrentHashMap<>();
    private final Set<Long> ingestedLoans = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isMessageProcessed(String messageId) {
        return messageId != null && processedMessages.contains(messageId);
    }

    @Override
    public void markMessageProcessed(String messageId) {
        if (messageId != null) {
            processedMessages.add(messageId);
        }
    }

    @Override
    public boolean isStale(Long loanId, Long publishMillis) {
        if (loanId == null || publishMillis == null) {
            return false;
        }
        Long seen = lastSeenPublishMillis.get(loanId);
        return seen != null && publishMillis < seen;
    }

    @Override
    public void recordSeen(Long loanId, Long publishMillis) {
        if (loanId == null || publishMillis == null) {
            return;
        }
        lastSeenPublishMillis.merge(loanId, publishMillis, Math::max);
    }

    @Override
    public boolean isIngested(Long loanId) {
        return loanId != null && ingestedLoans.contains(loanId);
    }

    @Override
    public void markIngested(Long loanId) {
        if (loanId != null) {
            ingestedLoans.add(loanId);
        }
    }

    @Override
    public void clearIngested(Long loanId) {
        if (loanId != null) {
            ingestedLoans.remove(loanId);
        }
    }
}
