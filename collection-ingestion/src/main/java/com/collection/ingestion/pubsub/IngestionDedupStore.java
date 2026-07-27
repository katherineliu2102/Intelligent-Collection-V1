package com.collection.ingestion.pubsub;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 接入层去重 / 幂等存储（数据接入规格 §3.3）。Phase 1 <b>内存版</b>，与引擎侧 {@code processed:} /
 * {@code lock:plan:} 分层、互不替代；生产切 Redis（同前缀 {@code ingestion:*}）。
 *
 * <p>三道检查各管一类重复：
 *
 * <ul>
 *   <li>{@link #markMessageProcessed} / {@link #isMessageProcessed}：同一条消息重投（{@code
 *       dedup:case_push:{message_id}}）；
 *   <li>{@link #isStale} / {@link #recordSeen}：同 loan 更旧 publish_time 的乱序消息（{@code
 *       last_seen:{loan_id}}）；
 *   <li>{@link #isIngested} / {@link #markIngested} / {@link #clearIngested}：本催收周期已 publish
 *       过 {@code CASE_INGESTED} 后的增量推送（{@code ingested:{loan_id}}），全额结清时清除。
 * </ul>
 *
 * <p><b>标记时机</b>：messageId / ingested 仅在 publish <b>成功后</b>标记，失败 nack 后允许重投重处理
 * （配合 §2.3 ACK 语义，支撑 L4b-7 NACK 重投幂等）。
 */
@Component
public class IngestionDedupStore {

    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastSeenPublishMillis = new ConcurrentHashMap<>();
    private final Set<Long> ingestedLoans = ConcurrentHashMap.newKeySet();

    public boolean isMessageProcessed(String messageId) {
        return messageId != null && processedMessages.contains(messageId);
    }

    public void markMessageProcessed(String messageId) {
        if (messageId != null) {
            processedMessages.add(messageId);
        }
    }

    /** 是否为乱序旧消息：已见过该 loan 更新或同等 publish_time。null publishMillis 视为不旧。 */
    public boolean isStale(Long loanId, Long publishMillis) {
        if (loanId == null || publishMillis == null) {
            return false;
        }
        Long seen = lastSeenPublishMillis.get(loanId);
        return seen != null && publishMillis < seen;
    }

    public void recordSeen(Long loanId, Long publishMillis) {
        if (loanId == null || publishMillis == null) {
            return;
        }
        lastSeenPublishMillis.merge(loanId, publishMillis, Math::max);
    }

    public boolean isIngested(Long loanId) {
        return loanId != null && ingestedLoans.contains(loanId);
    }

    public void markIngested(Long loanId) {
        if (loanId != null) {
            ingestedLoans.add(loanId);
        }
    }

    /** 全额结清：允许下一周期再次 {@code CASE_INGESTED}（§2.2.2）。 */
    public void clearIngested(Long loanId) {
        if (loanId != null) {
            ingestedLoans.remove(loanId);
        }
    }
}
