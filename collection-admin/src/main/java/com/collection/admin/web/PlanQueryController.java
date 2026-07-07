package com.collection.admin.web;

import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactRecord;
import com.collection.common.service.CaseService;
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
    @Resource
    private CaseService caseService;

    /** 查询计划详情（含步骤序列）。 */
    @GetMapping("/{planId}")
    public ContactPlan getPlan(@PathVariable Long planId) {
        return planRepository.findById(planId);
    }

    /** 查询某案件活跃计划。 */
    @GetMapping("/active/by-case/{caseId}")
    public List<ContactPlan> activeByCase(@PathVariable Long caseId) {
        return planRepository.findActivePlansByCase(caseId);
    }

    /** 查询某案件最近计划（含终态），供 L4a cancelReason / 幂等断言。 */
    @GetMapping("/by-case/{caseId}/history")
    public List<ContactPlan> historyByCase(@PathVariable Long caseId,
                                           @RequestParam(defaultValue = "10") int limit) {
        return planRepository.findRecentPlansByCase(caseId, limit);
    }

    /** 查询计划下全部步骤。 */
    @GetMapping("/{planId}/steps")
    public List<com.collection.common.model.ContactPlanStep> stepsByPlan(@PathVariable Long planId) {
        return planRepository.findStepsByPlan(planId);
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
        com.collection.common.model.CaseInfo info = caseService.getCaseInfo(caseId);
        result.put("caseId", caseId);
        result.put("userId", userId);
        result.put("stage", info == null || info.getStage() == null ? null : info.getStage().name());
        result.put("dpd", info == null ? null : info.getDpd());
        result.put("frozen", info != null && info.isFrozen());
        result.put("plans", planViews);
        result.put("timeline", userId != null
                ? timelineRepository.getContactHistory(userId, timelineLimit)
                : Collections.<ContactRecord>emptyList());
        return result;
    }
}
