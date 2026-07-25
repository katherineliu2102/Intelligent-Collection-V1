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
import java.util.List;
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
        LocalDateTime windowStart =
                LocalDateTime.now(ZoneId.of("Asia/Manila")).toLocalDate().atStartOfDay();
        if (snapshot != null
                && snapshot.getContactHistory() != null
                && snapshot.getContactHistory().getStageEntryDate() != null) {
            LocalDateTime stageEntry =
                    snapshot.getContactHistory().getStageEntryDate().atStartOfDay();
            if (stageEntry.isAfter(windowStart)) {
                windowStart = stageEntry;
            }
        }
        List<ContactRecord> recent =
                timelineRepository.getContactHistory(
                        plan.getUserId(), windowStart, props.getContext().getHistoryMaxRecords());
        return ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(snapshot)
                .recentTimeline(recent)
                .build();
    }
}
