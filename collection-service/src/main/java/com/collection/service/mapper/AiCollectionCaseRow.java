package com.collection.service.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** t_ai_collection 运行态案件快照行。 */
@Data
public class AiCollectionCaseRow {

    private Long caseId;
    private Long userId;
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
    private String borrowerName;
    private String borrowerPhone;
    private String borrowerEmail;
    private String borrowerLanguage;
    private String pushToken;
    private String owner;
    private LocalDate ownerDate;
    private LocalDateTime updatedAt;
}
