package com.collection.common.service;

import java.time.LocalDate;

/** 跨实例合规频控计数器；Redis 故障必须抛异常，供引擎 fail-close。 */
public interface ComplianceCounterService {
    Counts tryConsume(
            Long userId, String channel, LocalDate date, int channelLimit, int totalLimit);

    /**
     * 归还一次 {@link #tryConsume} 占用的配额，用于触达确认<b>未发出</b>时抵消预占。
     *
     * <p>仅在渠道能证明请求未写给供应商时调用（见 {@code StepResult.retryable}）；结果未知一律不归还， 少发一次好过重复骚扰。实现须把计数夹在 0
     * 以上：重复归还或未占先还都不得出现负数，否则 该用户当天的配额会被凭空放大。
     *
     * <p>非事务操作，与 dispatch 之间无原子性——进程在此刻被杀只会少还一次（偏保守），可接受。
     */
    void release(Long userId, String channel, LocalDate date);

    final class Counts {
        public final long channel;
        public final long total;

        public Counts(long channel, long total) {
            this.channel = channel;
            this.total = total;
        }
    }
}
