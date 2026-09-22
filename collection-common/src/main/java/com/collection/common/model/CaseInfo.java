package com.collection.common.model;

import com.collection.common.enums.Stage;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/**
 * 案件基本信息（SPI 入参）。对应领域模型 §5.1。 引擎通过 Repository.getCaseInfo(caseId) 实时读取（来源 t_collection 及关联）。 与
 * {@link CaseContext} 的区别：CaseInfo 是实时案件主数据（含还款实时状态）， CaseContext 是写入快照的决策视图。Phase 1 字段集可按需扩展。
 */
@Data
public class CaseInfo {

    private Long caseId;
    private Long userId;
    private int dpd;
    private Stage stage;
    private String product;
    private String caseStatus;
    private BigDecimal overdueAmount;
    private BigDecimal totalOutstanding;
    private BigDecimal penaltyAmount;
    private BigDecimal upcomingAmount;
    private LocalDate dueDate;
    private LocalDate nextDueDate;
    /** 实时还款状态：true 表示已结清（PreFlightChecker 使用）。 */
    private boolean repaid;
    /** 是否冻结（投诉冻结等）。 */
    private boolean frozen;
    /** PHT 归属日；空表示尚未收到带 owner 的 caseEvent。 */
    private LocalDate ownerDate;
}
