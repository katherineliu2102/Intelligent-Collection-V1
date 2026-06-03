package com.collection.admin.web;

import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactRecord;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.common.repository.TimelineRepository;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

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
}
