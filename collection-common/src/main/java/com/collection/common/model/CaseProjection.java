package com.collection.common.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 案件投影行。对应表 t_ai_collection，字段口径与数仓 Pub/Sub 完整快照一一对应。
 *
 * <p>数仓不再直连业务库写入本表：接入层消费事实事件后按 {@link #caseVersion} 条件 upsert，
 * 使实时事件与每日全量校准共用同一个写入者，不会互相覆盖。
 */
@Data
public class CaseProjection {

    private Long caseId;
    private Long userId;
    /** 同一案件单调递增；仅更高版本可覆盖已有投影。 */
    private Long caseVersion;
    private Integer dpd;
    private String stage;
    private String collectionStatus;
    private String product;
    private BigDecimal totalOutstanding;
    private BigDecimal penaltyAmount;
    private BigDecimal remainingAmount;
    private LocalDate dueDate;
    private String borrowerName;
    private String borrowerPhone;
    private String borrowerEmail;
    private String borrowerLanguage;
    private String pushToken;
    /** 数仓事实发生时间（消息 occurredAt）；落库时间由 synced_at 记录。 */
    private LocalDateTime updatedAt;
}
