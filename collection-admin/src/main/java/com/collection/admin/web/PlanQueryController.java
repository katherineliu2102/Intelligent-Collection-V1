package com.collection.admin.web;

import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactRecord;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划/时间线查询（Phase 1 最小可视化）。对应应用层管理后台 REST API。
 */
@RestController
@RequestMapping("/plans")
public class PlanQueryController {

    @Resource
    private ContactPlanRepository planRepository;
    @Resource
    private TimelineRepository timelineRepository;

    /** 查询计划详情（含步骤序列）。 */
    @GetMapping("/{planId}")
    public ContactPlan getPlan(@PathVariable Long planId) {
        return planRepository.findById(planId);
    }

    /** 查询某案件的活跃计划。 */
    @GetMapping("/active/by-case/{caseId}")
    public List<ContactPlan> activeByCase(@PathVariable Long caseId) {
        return planRepository.findActivePlansByCase(caseId);
    }

    /** 查询用户近期触达时间线。 */
    @GetMapping("/timeline/{userId}")
    public List<ContactRecord> timeline(@PathVariable Long userId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return timelineRepository.getContactHistory(userId, limit);
    }

    /**
     * 渠道编排只读观测聚合：案件活跃计划 + 各计划步骤序列 + 用户近期 timeline。
     * 供 {@code static/orchestration.html} 单页展示，无写操作。
     */
    @GetMapping("/overview/by-case/{caseId}")
    public Map<String, Object> overviewByCase(@PathVariable Long caseId,
                                              @RequestParam(defaultValue = "50") int timelineLimit) {
        List<ContactPlan> plans = planRepository.findActivePlansByCase(caseId);
        List<Map<String, Object>> planViews = new ArrayList<>();
        Long userId = null;
        for (ContactPlan plan : plans) {
            userId = plan.getUserId();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("plan", plan);
            view.put("steps", planRepository.findStepsByPlan(plan.getId()));
            planViews.add(view);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("userId", userId);
        result.put("plans", planViews);
        result.put("timeline", userId != null
                ? timelineRepository.getContactHistory(userId, timelineLimit)
                : Collections.<ContactRecord>emptyList());
        return result;
    }
}
