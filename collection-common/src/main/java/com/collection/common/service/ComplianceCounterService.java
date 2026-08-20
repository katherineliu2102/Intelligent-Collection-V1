package com.collection.common.service;

import java.time.LocalDate;

/** 跨实例合规频控计数器；Redis 故障必须抛异常，供引擎 fail-close。 */
public interface ComplianceCounterService {
    Counts tryConsume(
            Long userId, String channel, LocalDate date, int channelLimit, int totalLimit);

    final class Counts {
        public final long channel;
        public final long total;

        public Counts(long channel, long total) {
            this.channel = channel;
            this.total = total;
        }
    }
}
