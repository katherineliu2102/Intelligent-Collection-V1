package com.collection.common.model;

import com.collection.common.enums.EventDlqStatus;
import java.time.LocalDateTime;
import lombok.Data;

/** Redis Stream 死信的 MySQL 审计与受控重放记录。 */
@Data
public class EventDlq {
    private Long id;
    private String eventId;
    private String eventType;
    /** 完整 CollectionEvent JSON。 */
    private String payload;

    private String failureReason;
    private int deliveryCount;
    private int redriveCount;
    private EventDlqStatus status;
    private String redriveReason;
    private LocalDateTime firstFailedAt;
    private LocalDateTime lastFailedAt;
    private LocalDateTime redrivenAt;
    private LocalDateTime terminatedAt;
}
