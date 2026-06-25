package com.collection.common.model;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 触达历史摘要。ContextSnapshot 组成部分。对应领域模型 §3.3。
 * 由 CaseService.buildContactHistory(userId, caseId) 构建。
 */
@Data
public class ContactHistory {

    private int totalTouchCount;
    private Map<ChannelType, Integer> channelTouchCounts = new HashMap<>();
    private int todayTouchCount;
    private boolean todayPhoneAnswered;
    private LocalDateTime lastTouchTime;
    private ChannelType lastTouchChannel;
    private ContactResult lastTouchResult;
    private int currentPlanAiBotFailCount;
    /** PTP 承诺总次数。Phase 1 不计算，为 null；Phase 2 从 t_contact_timeline 聚合。 */
    private Integer ptpCount;
    /** PTP 兑现次数。Phase 1 不计算，为 null；Phase 2 从 t_contact_timeline 聚合。 */
    private Integer ptpFulfilledCount;
    private LocalDate stageEntryDate;
}
