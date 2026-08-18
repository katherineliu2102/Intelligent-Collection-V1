package com.collection.ingestion.pubsub;

/**
 * 接入层去重 / 幂等存储（数据接入规格 §3.3）。与引擎侧 {@code processed:} / {@code lock:plan:} 分层、互不替代。
 *
 * <p>三道检查各管一类重复：
 *
 * <ul>
 *   <li>{@link #markMessageProcessed} / {@link #isMessageProcessed}：同一条消息重投；
 *   <li>{@link #isStale} / {@link #recordSeen}：同 loan 更旧 publish_time 的乱序消息；
 *   <li>{@link #isIngested} / {@link #markIngested} / {@link #clearIngested}：本催收周期已 publish 过
 *       {@code CASE_INGESTED} 后的增量推送，全额结清时清除。
 * </ul>
 *
 * <p><b>标记时机</b>：messageId / ingested 仅在 publish <b>成功后</b>标记，失败 nack 后允许重投重处理 （配合 §2.3 ACK 语义，支撑
 * L4b-7 NACK 重投幂等）。
 *
 * <p>实现选择由 {@code collection.ingestion.redis-dedup-enabled} 决定：Pilot / 生产走 {@link
 * RedisIngestionDedupStore}（跨重启、跨实例共享），本地与 CI 走 {@link InMemoryIngestionDedupStore}。
 */
public interface IngestionDedupStore {

    boolean isMessageProcessed(String messageId);

    void markMessageProcessed(String messageId);

    /** 是否为乱序旧消息：已见过该 loan 更新的 publish_time。null publishMillis 视为不旧。 */
    boolean isStale(Long loanId, Long publishMillis);

    void recordSeen(Long loanId, Long publishMillis);

    boolean isIngested(Long loanId);

    void markIngested(Long loanId);

    /** 全额结清：允许下一周期再次 {@code CASE_INGESTED}（§2.2.2）。 */
    void clearIngested(Long loanId);
}
