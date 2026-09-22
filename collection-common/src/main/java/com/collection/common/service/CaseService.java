package com.collection.common.service;

import com.collection.common.model.CaseContext;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactHistory;
import com.collection.common.model.ContextSnapshot;
import java.util.Collections;
import java.util.List;

/**
 * 数据服务层 — 案件服务。对应架构设计文档 §1.5.2 领域服务 Service、领域模型 §4.1/§4.3。
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

    /** 实时还款状态（PreFlightChecker 使用）。 */
    boolean isRepaid(Long caseId);

    /** 日切全量扫描的有界 keyset 页。默认实现用于 Mock/未接入旧库的环境，明确返回空页而非加载全量案件。 */
    default List<Long> findActiveCaseIdsAfter(Long lastCaseId, int limit) {
        return Collections.emptyList();
    }

    /** 当日 PHT owner 对账水位是否已写入。Mock 默认 true，避免既有单测被门控打断。 */
    default boolean isOwnerReconciledToday() {
        return true;
    }

    /**
     * 是否强制校验 {@link com.collection.common.model.CaseInfo#getOwnerDate()}。生产 AI 投影为 true；Mock 为
     * false（ownerDate 为空仍可执行）。
     */
    default boolean requiresOwnerDate() {
        return false;
    }
}
