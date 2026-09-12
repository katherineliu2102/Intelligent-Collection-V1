package com.collection.service.repository;

import com.collection.common.repository.OwnerReconcileRepository;
import com.collection.service.mapper.OwnerReconcileMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "collection", name = "case-service", havingValue = "ai")
public class AiOwnerReconcileRepository implements OwnerReconcileRepository {

    private static final ZoneId PHT = ZoneId.of("Asia/Manila");

    @Resource private OwnerReconcileMapper mapper;

    @Override
    public boolean completedOn(LocalDate date) {
        return mapper.countByDate(date) > 0;
    }

    @Override
    public int countOwnerDateCasesOn(LocalDate date) {
        return mapper.countOwnerDateCases(date);
    }

    @Override
    public void markCompleted(LocalDate date, int ownerCaseCount) {
        mapper.upsertCompleted(date, LocalDateTime.now(PHT), ownerCaseCount);
    }

    @Override
    public List<Long> findLeaveCaseIdsAfter(LocalDate today, long afterCaseId, int limit) {
        return mapper.selectLeaveCaseIdsAfter(today, afterCaseId, limit);
    }

    @Override
    public List<Long> findEnterCaseIdsAfter(LocalDate today, long afterCaseId, int limit) {
        return mapper.selectEnterCaseIdsAfter(today, afterCaseId, limit);
    }
}
