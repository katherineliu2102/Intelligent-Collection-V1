package com.collection.common.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 案件投影行。对应表 t_ai_collection，字段口径与数仓 Pub/Sub 完整快照一一对应。
 *
 * <p>数仓不再直连业务库写入本表：接入层消费事实事件后按 {@link #caseVersion} 条件 upsert， 使实时事件与每日全量校准共用同一个写入者，不会互相覆盖。
 */
@Data
public class CaseProjection {

    private Long caseId;
    private Long userId;
    /** 数仓内容指纹；与已入库值不同才覆盖已有投影。 */
    private String caseVersion;

    private Integer dpd;
    private String stage;
    private String collectionStatus;
    private String product;
    private BigDecimal overdueAmount;
    private BigDecimal totalOutstanding;
    private BigDecimal penaltyAmount;
    private BigDecimal remainingAmount;
    private BigDecimal upcomingAmount;
    private LocalDate dueDate;
    private LocalDate nextDueDate;
    /** 增量还款消息显式携带 nextDueDate（可为 null）时为 true。 */
    private boolean nextDueDatePresent;

    /**
     * 增量还款消息显式携带 stage（可为 null）时为 true。
     *
     * <p>数仓口径下 {@code stage=null} 是有含义的取值——下一个未还 dueDate 超过 3 天即不属任何催收阶段。 因此必须把「没给该字段」与「明确给了
     * null」区分开，否则前者会被当成后者清空基线，或后者被当成 前者而永远写不进去。
     */
    private boolean stagePresent;

    private String borrowerName;
    private String borrowerPhone;
    private String borrowerEmail;
    private String borrowerLanguage;
    private String pushToken;
    /** 最近一次还款金额（repaymentEvent.paidAmount），当日回收金额热层数据源。 */
    private BigDecimal lastPaidAmount;
    /** 最近一次还款时间（repaymentEvent.repayTime，PHT）。 */
    private LocalDateTime settledAt;
    /** 数仓事实发生时间（消息 occurredAt）；落库时间由 synced_at 记录。 */
    private LocalDateTime updatedAt;
}
