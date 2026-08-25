package com.collection.common.repository;

/**
 * {@code repaymentEvent} 找不到完整 {@code caseEvent} 投影基线。按数据接入规格 §3.3：视为异常事实， 不从旧库回填产品/借款人/设备，由接入层按
 * poison 处置（ack + 告警），重投同一条消息也不会补出基线。
 */
public class MissingCaseBaselineException extends RuntimeException {

    private final Long caseId;

    public MissingCaseBaselineException(Long caseId) {
        super("repaymentEvent 缺完整 caseEvent 基线，caseId=" + caseId);
        this.caseId = caseId;
    }

    public Long getCaseId() {
        return caseId;
    }
}
