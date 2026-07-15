package com.collection.common.model;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.StepStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 触达计划步骤。对应领域模型 §3.2 / 表 t_contact_plan_step。
 */
@Data
public class ContactPlanStep {

    private Long id;
    private Long planId;
    /** 步骤序号（从 1 开始）。 */
    private int stepOrder;
    private ChannelType channelType;
    private Long templateId;
    /** 相对上一步的延迟（分钟），首步为相对计划创建时间。 */
    private int delayMinutes;
    private LocalDateTime triggerTime;
    private LocalDateTime timeoutTime;
    /** 前置条件表达式。Phase 1 未启用。 */
    private String triggerCondition;
    private StepStatus status;
    /** 观察期（分钟），0=无观察期。 */
    private int observationMinutes;
    private int retryCount;
    private ContactResult result;
    private String idempotencyKey;
    private LocalDateTime executedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
