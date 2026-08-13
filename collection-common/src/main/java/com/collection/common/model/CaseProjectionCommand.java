package com.collection.common.model;

import lombok.Data;

/** 一条入站事实事件的投影写入请求。{@link #eventId} 为数仓生成的业务幂等键，重投/重放必须复用。 */
@Data
public class CaseProjectionCommand {

    private String eventId;
    /** caseEvent / repaymentEvent。 */
    private String messageType;
    private String eventType;
    /** 完整外部 payload，收件箱留存以供审计与重放。 */
    private String payload;
    /**
     * 是否需要在投影提交后发布内部领域事件。每日全量校准只刷新投影，不触发引擎。
     */
    private boolean publishRequired = true;

    private CaseProjection projection;
}
