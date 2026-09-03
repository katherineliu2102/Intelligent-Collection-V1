package com.collection.common.repository;

import java.time.LocalDate;
import java.util.List;

/** 当日 owner 对账水位与分页扫描。实现在 collection-service。 */
public interface OwnerReconcileRepository {

    boolean completedOn(LocalDate date);

    int countCaseEventsOn(LocalDate date);

    void markCompleted(LocalDate date, int inboxCaseEventCount);

    List<Long> findLeaveCaseIdsAfter(LocalDate today, long afterCaseId, int limit);

    List<Long> findEnterCaseIdsAfter(LocalDate today, long afterCaseId, int limit);
}
