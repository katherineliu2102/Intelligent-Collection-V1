package com.collection.common.enums;

/**
 * 触达记录数据来源。对应领域模型 §6.11。
 */
public enum DataSource {
    /** 系统实时触达。 */
    SYSTEM,
    /** 历史数据迁移。 */
    ETL_SYNC,
    /** 过渡期增量同步。 */
    PUBSUB_SYNC
}
