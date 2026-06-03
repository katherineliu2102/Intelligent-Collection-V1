package com.collection.engine.lifecycle;

import com.collection.common.dto.ExecutionContext;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContactRecord;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.repository.TimelineRepository;
import com.collection.common.util.JsonUtil;
import com.collection.engine.config.EngineProperties;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 组装 SPI 调用的统一输入 {@link ExecutionContext}。
 * 快照来自 plan.contextSnapshot（JSON 反序列化，零额外 DB I/O）；
 * 近期触达历史按配置上限读取。
 */
@Component
public class ContextAssembler {

    @Resource
    private TimelineRepository timelineRepository;
    @Resource
    private EngineProperties props;

    public ExecutionContext assemble(ContactPlan plan, ContactPlanStep step) {
        ContextSnapshot snapshot = JsonUtil.fromJson(plan.getContextSnapshot(), ContextSnapshot.class);
        List<ContactRecord> recent = timelineRepository.getContactHistory(
                plan.getUserId(), props.getContext().getHistoryMaxRecords());
        return ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(snapshot)
                .recentTimeline(recent)
                .build();
    }
}
