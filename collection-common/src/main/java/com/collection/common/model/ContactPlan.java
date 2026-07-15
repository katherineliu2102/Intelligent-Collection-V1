package com.collection.common.model;

import com.collection.common.enums.CancelReason;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 触达计划（核心引擎状态机主实体）。对应领域模型 §3.1 / 表 t_contact_plan。
 */
@Data
public class ContactPlan {

    private Long id;
    private Long caseId;
    private Long userId;
    private Stage stage;
    private Long planTemplateId;
    private PlanStatus status;
    /** 当前执行到第几步（从 0 开始）。 */
    private int currentStep;
    private int totalSteps;
    private CancelReason cancelReason;
    /** ContextSnapshot 的 JSON 序列化（DB 列 context_snapshot）。 */
    private String contextSnapshot;
    private String idempotencyKey;
    private boolean renewalPending;
    /** 乐观锁版本号。 */
    private int version;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 计划步骤序列。仅在内存态（计划创建、ExecutionContext 组装）使用，
     * 不对应 t_contact_plan 单表列；持久化时落 t_contact_plan_step。
     */
    private List<ContactPlanStep> steps = new ArrayList<>();

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}
