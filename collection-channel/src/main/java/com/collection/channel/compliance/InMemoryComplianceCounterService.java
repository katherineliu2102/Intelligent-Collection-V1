package com.collection.channel.compliance;

import com.collection.common.service.ComplianceCounterService;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "collection.compliance.counter",
        havingValue = "memory",
        matchIfMissing = true)
public class InMemoryComplianceCounterService implements ComplianceCounterService {
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public Counts tryConsume(
            Long userId, String channel, LocalDate date, int channelLimit, int totalLimit) {
        String prefix = userId + ":" + date + ":";
        long channelCount =
                counters.computeIfAbsent(prefix + channel, k -> new AtomicLong()).incrementAndGet();
        long total =
                counters.computeIfAbsent(prefix + "ALL", k -> new AtomicLong()).incrementAndGet();
        return new Counts(channelCount, total);
    }
}
