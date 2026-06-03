package com.collection.common.model;

import com.collection.common.enums.Stage;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 案件上下文。ContextSnapshot 组成部分。对应领域模型 §3.1。
 * 由 CaseService.buildContext(caseId) 构建。
 */
@Data
public class CaseContext {

    private Long caseId;
    private Long userId;
    /** 逾期天数（D-3 起为负数，D0=0，D+1=1）。 */
    private int dpd;
    private Stage stage;
    private String product;
    private BigDecimal loanAmount;
    private BigDecimal overdueAmount;
    private BigDecimal penaltyAmount;
    private BigDecimal totalOutstanding;
    private Integer loanTerms;
    private LocalDate disbursementDate;
    private LocalDate dueDate;
    private String caseStatus;
    private Long assignedAgentId;
    private boolean isFirstLoan;
    private int payCount;
    private Long activePlanId;
}
