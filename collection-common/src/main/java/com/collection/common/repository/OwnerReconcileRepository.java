package com.collection.common.repository;

import java.time.LocalDate;
import java.util.List;

/** 当日 owner 对账水位与分页扫描。实现在 collection-service。 */
public interface OwnerReconcileRepository {

    boolean completedOn(LocalDate date);

    /**
     * 零收检测：当日 {@code owner_date = date} 的案件数（即当日收到 caseEvent 的案件数，按案件去重）。 走 {@code
     * idx_ai_collection_owner_date}，时区口径与投影写入一致。
     */
    int countOwnerDateCasesOn(LocalDate date);

    void markCompleted(LocalDate date, int ownerCaseCount);

    List<Long> findLeaveCaseIdsAfter(LocalDate today, long afterCaseId, int limit);

    List<Long> findEnterCaseIdsAfter(LocalDate today, long afterCaseId, int limit);
}
