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
    /** App 还款深链；SMS/Push/Email 模板变量 payment_link / deep_link。来源 ingestion / 信贷结账链路。 */
    private String repaymentUrl;
    /** 编排强度 STANDARD / FIRM；接入层按难催条件预计算，SPI 只读不重算。 */
    private String strategyTone;
    /** 争议冻结；true 时 ExecutionGuard BLOCK 机器轨。 */
    private boolean complaintFrozen;
    /** 催收生命周期；CEASED = D+91 停催。 */
    private String collectionStatus;
}
