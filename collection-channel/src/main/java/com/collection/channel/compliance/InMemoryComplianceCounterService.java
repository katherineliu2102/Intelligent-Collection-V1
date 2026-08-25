package com.collection.channel.compliance;

import com.collection.common.service.ComplianceCounterService;
import java.time.LocalDate;
import java.util.Collection;
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

    @Override
    public void release(Long userId, String channel, LocalDate date) {
        String prefix = userId + ":" + date + ":";
        decrementToFloor(prefix + channel);
        decrementToFloor(prefix + "ALL");
    }

    /** 夹在 0 以上：未占先还或重复归还都不得把配额扣成负数，否则等于凭空放大该用户当天的额度。 */
    private void decrementToFloor(String key) {
        AtomicLong counter = counters.get(key);
        if (counter == null) {
            return;
        }
        counter.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /**
     * 清掉指定用户的当日计数。仅供联调重置使用（Redis 实现无对应能力，故不上提到 SPI）。
     *
     * <p>计数是进程内的，清库不会清它；同一个应用实例里重复跑 L4a 会让日限先耗尽，后续用例全线被 FREQUENCY 挡掉。
     */
    public void clear(Collection<Long> userIds) {
        for (Long userId : userIds) {
            String prefix = userId + ":";
            counters.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }
}
