package com.collection.common.model;

import com.collection.common.enums.OutboxStatus;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 发件箱记录。对应表 t_event_outbox。
 *
 * <p>派生事件与产生它的状态迁移写在同一事务，因此"事件已产生"这一事实不会因提交后发布失败而消失。 {@link #eventId} 与 {@link
 * com.collection.common.event.CollectionEvent#getEventId()} 一致， 兜底重发与即时发布落到同一 eventId，由消费侧幂等吸收。
 */
@Data
public class OutboxEvent {

    private Long id;
    private String eventId;
    private String eventType;
    private Long planId;
    private Long caseId;
    /** 完整 CollectionEvent 信封 JSON。 */
    private String payload;

    private OutboxStatus status;
    private int retryCount;
    /** 兜底重发时间。入库时置为 now + 宽限期，让提交后的即时发布先完成。 */
    private LocalDateTime nextRetryAt;
    /** 多实例发布器的短租约；PROCESSING 租约到期后可被重新认领。 */
    private LocalDateTime leaseUntil;

    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
