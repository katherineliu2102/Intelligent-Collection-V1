package com.collection.service.mapper;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * t_collection 原始行（只取 Phase 1 组装 ContextSnapshot 所需列）。
 * 依赖 map-underscore-to-camel-case=true 完成列名→字段映射。
 * 注意：{@code collecitonStatus} 对应旧库列 {@code colleciton_status}（旧库列名拼写如此）。
 */
@Data
public class CollectionCaseRow {

    /** 旧库 loan_id（数字串），作为引擎 caseId 来源（t_collection.id 是 hex 串，不用）。 */
    private String loanId;
    private String userId;
    private Integer overdueDays;
    private LocalDate repaymentDate;
    private BigDecimal totalNotPaid;
    private LocalDateTime fullRepayTime;
    private String realName;
    private String phone;
    private String email;
    private String appName;
    private String collecitonStatus;
}
