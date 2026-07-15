package com.collection.common.model;

import com.collection.common.enums.Stage;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 案件上下文。ContextSnapshot 组成部分。对应领域模型 §4.1。
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
    /** 编排强度：STANDARD / FIRM（读 snapshot，PlanFactory 匹配模板）。 */
    private String strategyTone;
    /** 投诉/争议冻结标记（ExecutionGuard BLOCK 全渠道）。 */
    private boolean complaintFrozen;
    /** 案件催收生命周期：CEASED = D+91 完全停催。 */
    private String collectionStatus;
    /** App 还款深链（ingestion 写入；Push/Email/SMS 变量渲染）。 */
    private String repaymentUrl;
    /**
     * Phase 1 Mock：显式指定 Email 里程碑 scriptSlot（E2E 联调）。
     * 为空时由 {@code EmailMilestoneScriptSlots.resolveByDpd(dpd)} 推断。
     */
    private String emailScriptSlot;
}
