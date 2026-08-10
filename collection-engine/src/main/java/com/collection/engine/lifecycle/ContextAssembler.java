package com.collection.engine.lifecycle;

import com.collection.common.dto.ExecutionContext;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContactRecord;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.util.JsonUtil;
import com.collection.engine.config.EngineProperties;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 组装 SPI 调用的统一输入 {@link ExecutionContext}。 快照来自 plan.contextSnapshot（JSON 反序列化，零额外 DB I/O）；
 * 近期触达历史按配置上限读取。
 */
@Component
public class ContextAssembler {

    @Resource private TimelineRepository timelineRepository;
    @Resource private EngineProperties props;

    public ExecutionContext assemble(ContactPlan plan, ContactPlanStep step) {
        ContextSnapshot snapshot =
                JsonUtil.fromJson(plan.getContextSnapshot(), ContextSnapshot.class);
        LocalDateTime todayStart =
                LocalDateTime.now(ZoneId.of("Asia/Manila")).toLocalDate().atStartOfDay();
        LocalDateTime stageEntry = todayStart;
        if (snapshot != null
                && snapshot.getContactHistory() != null
                && snapshot.getContactHistory().getStageEntryDate() != null) {
            stageEntry = snapshot.getContactHistory().getStageEntryDate().atStartOfDay();
        }
        int limit = props.getContext().getHistoryMaxRecords();
        List<ContactRecord> userToday =
                timelineRepository.getContactHistory(plan.getUserId(), todayStart, limit);
        List<ContactRecord> caseSinceStage =
                timelineRepository.getContactHistoryByCase(plan.getCaseId(), stageEntry, limit);
        List<ContactRecord> recent = mergeByRecordId(userToday, caseSinceStage, limit);
        return ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(snapshot)
                .recentTimeline(recent)
                .build();
    }

    private List<ContactRecord> mergeByRecordId(
            List<ContactRecord> userToday, List<ContactRecord> caseSinceStage, int limit) {
        Map<String, ContactRecord> merged = new LinkedHashMap<>();
        addDistinct(merged, userToday);
        addDistinct(merged, caseSinceStage);
        List<ContactRecord> records = new ArrayList<>(merged.values());
        records.sort(
                Comparator.comparing(
                        ContactRecord::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return records.subList(0, Math.min(records.size(), Math.max(1, limit)));
    }

    private void addDistinct(Map<String, ContactRecord> target, List<ContactRecord> records) {
        if (records == null) {
            return;
        }
        for (ContactRecord record : records) {
            String key =
                    record.getId() != null
                            ? "id:" + record.getId()
                            : record.getAttemptKey() != null
                                    ? "attempt:" + record.getAttemptKey()
                                    : "transient:" + System.identityHashCode(record);
            target.putIfAbsent(key, record);
        }
    }
}
