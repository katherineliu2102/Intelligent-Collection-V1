package com.collection.common.model;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.ContactResult;
import com.collection.common.enums.DataSource;
import com.collection.common.enums.Direction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 统一触达记录。写入 t_contact_timeline 的标准模型，所有渠道/来源统一使用。 对应领域模型 §3.4。 */
@Data
public class ContactRecord {

    private Long id;
    private Long caseId;
    private Long userId;
    private Long planId;
    private Long stepId;
    /** 单次触达尝试的稳定幂等键：planId:stepId:retryCount。 */
    private String attemptKey;

    private ChannelType channel;
    private Direction direction;
    private Long templateId;
    /** 渲染时命中的配置版本（DB 配置版本）。 */
    private Long configVersion;
    /** 可由 channel、scriptSlot 与版本重新定位模板的无 PII 引用。 */
    private String renderedRef;
    /** 内容摘要，≤500 字符。 */
    private String contentSummary;
    /** 解析时选中的无 PII 话术槽位。 */
    private String scriptSlot;
    /** 模板来源及其不可变发布版本（如 db:42、nacos:2026.07.27.1）。 */
    private String templateVersion;
    /** 渲染正文的 HMAC-SHA-256；不保存正文或其明文变量。 */
    private String contentHmac;
    /** 用于生成 contentHmac 的非秘密密钥版本标识。 */
    private String contentKeyId;

    private ContactResult result;
    private String providerMsgId;
    /** 供应商回调原始 JSON（调试用）。 */
    private String providerCallback;

    private BigDecimal cost;
    private DataSource source;
    private LocalDateTime createdAt;
}
