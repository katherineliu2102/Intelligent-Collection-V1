package com.collection.service.repository;

import com.collection.common.enums.*;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ContactPlanRepository;
import com.collection.service.mapper.ContactPlanMapper;
import com.collection.service.mapper.ContactPlanStepMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ContactPlanRepositoryImpl implements ContactPlanRepository {

    @Resource
    private ContactPlanMapper planMapper;
    @Resource
    private ContactPlanStepMapper stepMapper;

    @Override
    public ContactPlan findById(Long planId) {
        return attachSteps(planMapper.selectById(planId));
    }

    @Override
    public ContactPlan findPlanWithLock(Long planId) {
        // FOR UPDATE，必须由调用方包在事务内（PlanLifecycleManager 短事务）
        return attachSteps(planMapper.selectByIdForUpdate(planId));
    }

    @Override
    public List<ContactPlan> findActivePlansByUser(Long userId) {
        return planMapper.selectActiveByUser(userId);
    }

    @Override
    public List<ContactPlan> findActivePlansByCase(Long caseId) {
        return planMapper.selectActiveByCase(caseId);
    }

    @Override
    public ContactPlan findActivePlanByCaseAndStage(Long caseId, Stage stage) {
        return planMapper.selectActiveByCaseAndStage(caseId, stage);
    }

    @Override
    public ContactPlan getLastCompletedPlan(Long caseId) {
        return planMapper.selectLastCompleted(caseId);
    }

    @Override
    public List<ContactPlan> findRecentPlansByCase(Long caseId, int limit) {
        return planMapper.selectRecentByCase(caseId, Math.max(1, Math.min(limit, 50)));
    }

    @Override
    @Transactional
    public void savePlan(ContactPlan plan) {
        if (plan.getStatus() == null) {
            plan.setStatus(PlanStatus.PENDING);
        }
        planMapper.insert(plan);
        int order = 1;
        for (ContactPlanStep step : plan.getSteps()) {
            step.setPlanId(plan.getId());
            if (step.getStepOrder() <= 0) {
                step.setStepOrder(order);
            }
            if (step.getStatus() == null) {
                step.setStatus(StepStatus.PENDING);
            }
            stepMapper.insert(step);
            order++;
        }
    }

    @Override
    public void updatePlanStatus(Long planId, PlanStatus status, CancelReason reason) {
        planMapper.updateStatus(planId, status, reason);
        if (status != null && status.isTerminal()) {
            planMapper.markCompleted(planId);
        }
    }

    @Override
    public void markStarted(Long planId) {
        planMapper.markStarted(planId);
    }

    @Override
    public void markCompleted(Long planId) {
        planMapper.markCompleted(planId);
    }

    @Override
    public void updateCurrentStep(Long planId, int currentStep) {
        planMapper.updateCurrentStep(planId, currentStep);
    }

    @Override
    public ContactPlanStep findStepById(Long stepId) {
        return stepMapper.selectById(stepId);
    }

    @Override
    public List<ContactPlanStep> findStepsByPlan(Long planId) {
        return stepMapper.selectByPlan(planId);
    }

    @Override
    public ContactPlanStep getNextStep(Long planId, int currentStepOrder) {
        return stepMapper.selectByPlanAndOrder(planId, currentStepOrder + 1);
    }

    @Override
    public void updateStepStatus(Long stepId, StepStatus status, ContactResult result) {
        stepMapper.updateStatus(stepId, status, result);
    }

    @Override
    public void updateStepTriggerTime(Long stepId, LocalDateTime triggerTime, StepStatus status) {
        stepMapper.updateTriggerTime(stepId, triggerTime, status);
    }

    @Override
    public void updateStepTimeoutTime(Long stepId, LocalDateTime timeoutTime) {
        stepMapper.updateTimeoutTime(stepId, timeoutTime);
    }

    @Override
    public void incrementRetryCount(Long stepId) {
        stepMapper.incrementRetryCount(stepId);
    }

    @Override
    public List<ContactPlanStep> findDueSteps(LocalDateTime now, int limit) {
        return stepMapper.selectDueSteps(now, limit);
    }

    @Override
    public List<ContactPlanStep> findTimeoutSteps(LocalDateTime now, int limit) {
        return stepMapper.selectTimeoutSteps(now, limit);
    }

    private ContactPlan attachSteps(ContactPlan plan) {
        if (plan != null) {
            plan.setSteps(stepMapper.selectByPlan(plan.getId()));
        }
        return plan;
    }
}
