package com.collection.common.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 决策上下文快照（不可变）。对应领域模型 §3.4。
 *
 * <p>案件接入时由数据接入层序列化为 JSON 写入 t_contact_plan.context_snapshot。
 * SPI 决策实现读快照而非实时查 DB（零 DB I/O）。
 */
@Data
public class ContextSnapshot {

    private CaseContext caseContext;
    private UserProfile userProfile;
    private ContactHistory contactHistory;
    private LocalDateTime snapshotTime;
    private String snapshotVersion;
}
