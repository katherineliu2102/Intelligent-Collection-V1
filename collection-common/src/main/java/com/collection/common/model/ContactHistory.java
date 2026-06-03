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
    private int ptpCount;
    private int ptpFulfilledCount;
    private LocalDate stageEntryDate;
}
