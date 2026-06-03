package com.collection.common.model;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.DataSource;
import com.collection.common.enums.Direction;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 统一触达记录。写入 t_contact_timeline 的标准模型，所有渠道/来源统一使用。
 * 对应领域模型 §5.1。
 */
@Data
public class ContactRecord {

    private Long id;
    private Long caseId;
    private Long userId;
    private Long planId;
    private Long stepId;
    private ChannelType channel;
    private Direction direction;
    private Long templateId;
    /** 内容摘要，≤500 字符。 */
    private String contentSummary;
    private ContactResult result;
    private String providerMsgId;
    /** 供应商回调原始 JSON（调试用）。 */
    private String providerCallback;
    private BigDecimal cost;
    private DataSource source;
    private LocalDateTime createdAt;
}
