package com.collection.common.model;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 决策上下文快照（不可变）。对应领域模型 §4.4。
 *
 * <p>引擎建计划时序列化为 JSON 写入 t_contact_plan.context_snapshot；阶段变更和续建创建新计划时 carry-forward，
 * 部分还款可受控更新余额。SPI 决策实现读快照而非实时查 DB（零 DB I/O）。
 */
@Data
public class ContextSnapshot {

    private CaseContext caseContext;
    private UserProfile userProfile;
    private ContactHistory contactHistory;
    private LocalDateTime snapshotTime;
    private String snapshotVersion;
}
