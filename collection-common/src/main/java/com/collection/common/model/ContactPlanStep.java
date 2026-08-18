package com.collection.common.model;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.StepStatus;
import java.time.LocalDateTime;
import lombok.Data;

/** 触达计划步骤。对应领域模型 §3.2 / 表 t_contact_plan_step。 */
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

    /** 待触发的绝对时间。被扫描拾取后置空，重试/延后时被改写，因此不能用于排期分析。 */
    private LocalDateTime triggerTime;

    /** 建计划时的原始排期，只在插入时写入。用于「计划 vs 实际」偏差分析。 */
    private LocalDateTime originalTriggerTime;

    private LocalDateTime timeoutTime;
    /** 前置条件表达式。Phase 1 未启用。 */
    private String triggerCondition;

    private StepStatus status;
    /** 观察期（分钟），0=无观察期。 */
    private int observationMinutes;

    private int retryCount;
    private ContactResult result;
    private String idempotencyKey;
    /** 引擎开始尝试的时间。 */
    private LocalDateTime executedAt;

    /** 渠道受理时间（供应商已接单）。区分「卡在调用前」与「已发出未回写」。 */
    private LocalDateTime dispatchedAt;

    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
