package com.collection.common.repository;

import com.collection.common.model.CaseProjectionCommand;

/**
 * 案件投影单写者入口（t_ai_collection + t_ai_collection_inbox）。
 *
 * <p>{@link #apply} 必须在一个事务内完成收件箱幂等落盘与版本条件 upsert：投影更新在 MySQL、领域事件在 Redis Stream，
 * 两者无法原子提交，收件箱记录的 publish 状态让消息重投时能区分"整条重复"与"投影已入库但事件未发出"。
 */
public interface CaseProjectionRepository {

    /** 投影写入结果，决定调用方是否发布内部领域事件。 */
    enum Outcome {
        /** 投影已更新，需发布领域事件。 */
        APPLIED,
        /** 投影已更新，但该消息不产生领域事件（每日全量校准）。 */
        APPLIED_WITHOUT_EVENT,
        /** 消息版本不高于已入库版本，投影与事件均跳过。 */
        STALE_VERSION,
        /** 该事件此前已更新投影但领域事件未确认发出，仅需补发。 */
        PENDING_PUBLISH,
        /** 该事件此前已完整处理。 */
        ALREADY_PROCESSED
    }

    Outcome apply(CaseProjectionCommand command);

    /** 合并 repaymentEvent 增量；要求已有完整 caseEvent 投影作为基线。 */
    default Outcome applyRepaymentDelta(CaseProjectionCommand command) {
        return apply(command);
    }

    /** 内部领域事件确认发布后调用；未调用的记录在消息重投时会被补发。 */
    void markEventPublished(String eventId);
}
