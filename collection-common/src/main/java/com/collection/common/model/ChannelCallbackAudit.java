package com.collection.common.model;

import java.time.LocalDateTime;
import lombok.Data;

/** 供应商回调原始审计；与 t_contact_timeline 的最终触达事实分离。 */
@Data
public class ChannelCallbackAudit {

    private Long id;
    private Long planId;
    private Long stepId;
    private Long caseId;
    private String providerMsgId;
    private String result;
    private String disposition;
    private String canonicalPayload;
    private String signature;
    private boolean signatureValid;
    private LocalDateTime receivedAt;
}
