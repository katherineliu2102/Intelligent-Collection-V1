package com.collection.common.service;

import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactHistory;
import com.collection.common.model.ContextSnapshot;

/**
 * 数据服务层 — 案件服务。对应架构设计文档 §1.5 数据服务层、领域模型 §3.1/§3.3。
 *
 * <p>Phase 1 骨架提供读 mock 数据的实现；后续由服务层开发者映射旧表（t_collection 等）。
 */
public interface CaseService {

    /** 实时案件基本信息（含还款/冻结实时状态）。来源 t_collection。 */
    CaseInfo getCaseInfo(Long caseId);

    /** 构建案件决策视图。 */
    CaseContext buildContext(Long caseId);

    /** 构建触达历史摘要。 */
    ContactHistory buildContactHistory(Long userId, Long caseId);

    /** 读取计划上的不可变快照（t_contact_plan.context_snapshot 反序列化）。 */
    ContextSnapshot getContextSnapshot(Long caseId);

    /** 实时还款状态（PreFlightChecker / PTP 使用）。 */
    boolean isRepaid(Long caseId);
}
