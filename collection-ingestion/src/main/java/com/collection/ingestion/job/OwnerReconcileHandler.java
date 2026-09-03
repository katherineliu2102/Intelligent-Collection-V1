package com.collection.ingestion.job;

import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.repository.OwnerReconcileRepository;
import com.collection.common.service.CaseService;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** dailyRoll 第一阶段：按日 NEW 归属对账。水位写入 {@code t_ai_owner_reconcile} 后才允许 DPD 日切。 */
@Component
public class OwnerReconcileHandler {

    private static final Logger log = LoggerFactory.getLogger(OwnerReconcileHandler.class);
    private static final ZoneId PHT = ZoneId.of("Asia/Manila");

    @Resource private IngestionProperties props;
    @Resource private CaseService caseService;
    @Resource private IngestionService ingestionService;

    @Autowired(required = false)
    private OwnerReconcileRepository ownerReconcileRepository;

    @Autowired(required = false)
    private RedisDailyRollDeduplicator dailyRollDeduplicator;

    public boolean completedToday() {
        if (ownerReconcileRepository == null) {
            return true;
        }
        return ownerReconcileRepository.completedOn(today());
    }

    /** @return 本页处理的案件数；空收推迟时为 0 且不写水位 */
    public int advance() {
        if (ownerReconcileRepository == null || completedToday()) {
            return 0;
        }
        List<Long> whitelist = props.getLoanIdWhitelist();
        boolean isolated = whitelist != null && !whitelist.isEmpty();
        if (!isolated && countTodayCaseEvents() == 0) {
            log.error("[OwnerReconcile] 当日 inbox 无 NEW caseEvent，推迟对账，不得当作零案");
            return 0;
        }
        int limit = Math.max(1, props.getDailyRollBatchSize());
        if (isolated) {
            int n = reconcileIds(whitelist);
            markCompleted(n);
            return n;
        }
        int leave = pageLeave(limit);
        if (leave >= limit) {
            return leave;
        }
        int enter = pageEnter(limit);
        if (enter >= limit) {
            return leave + enter;
        }
        markCompleted(countTodayCaseEvents());
        return leave + enter;
    }

    private int pageLeave(int limit) {
        long after =
                cursor(
                        dailyRollDeduplicator == null
                                ? null
                                : dailyRollDeduplicator.ownerLeaveCursor());
        List<Long> ids = ownerReconcileRepository.findLeaveCaseIdsAfter(today(), after, limit);
        int n = reconcileLeave(ids);
        if (!ids.isEmpty() && dailyRollDeduplicator != null) {
            dailyRollDeduplicator.advanceOwnerLeaveCursor(ids.get(ids.size() - 1));
        }
        return n;
    }

    private int pageEnter(int limit) {
        long after =
                cursor(
                        dailyRollDeduplicator == null
                                ? null
                                : dailyRollDeduplicator.ownerEnterCursor());
        List<Long> ids = ownerReconcileRepository.findEnterCaseIdsAfter(today(), after, limit);
        int n = reconcileEnter(ids);
        if (!ids.isEmpty() && dailyRollDeduplicator != null) {
            dailyRollDeduplicator.advanceOwnerEnterCursor(ids.get(ids.size() - 1));
        }
        return n;
    }

    private int reconcileIds(List<Long> ids) {
        int n = 0;
        LocalDate today = today();
        for (Long id : ids) {
            CaseInfo info = safeInfo(id);
            if (info == null) {
                continue;
            }
            if (info.getOwnerDate() == null || !today.equals(info.getOwnerDate())) {
                n += reconcileLeave(Collections.singletonList(id));
            } else if (!info.isRepaid() && info.getStage() != null) {
                n += reconcileEnter(Collections.singletonList(id));
            }
        }
        return n;
    }

    private int reconcileLeave(List<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            CaseInfo info = safeInfo(id);
            ingestionService.routedToLegacy(id, info == null ? id : info.getUserId());
            n++;
        }
        return n;
    }

    private int reconcileEnter(List<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            CaseInfo info = safeInfo(id);
            if (info == null || info.isRepaid() || info.getStage() == null) {
                continue;
            }
            ContextSnapshot snapshot = caseService.getContextSnapshot(id);
            ingestionService.ingestCase(
                    id,
                    info.getUserId(),
                    info.getStage(),
                    ingestionService.currentSnapshotFields(snapshot));
            n++;
        }
        return n;
    }

    private CaseInfo safeInfo(Long id) {
        try {
            return caseService.getCaseInfo(id);
        } catch (RuntimeException e) {
            log.warn("[OwnerReconcile] caseId={} 读投影失败: {}", id, e.getMessage());
            return null;
        }
    }

    private int countTodayCaseEvents() {
        return ownerReconcileRepository.countCaseEventsOn(today());
    }

    private void markCompleted(int inboxCount) {
        ownerReconcileRepository.markCompleted(today(), inboxCount);
        if (dailyRollDeduplicator != null) {
            dailyRollDeduplicator.markOwnerReconCompletedToday();
        }
        log.info("[OwnerReconcile] completed date={} inboxCaseEvents={}", today(), inboxCount);
    }

    private static long cursor(Long value) {
        return value == null ? 0L : value;
    }

    private static LocalDate today() {
        return LocalDate.now(PHT);
    }
}
