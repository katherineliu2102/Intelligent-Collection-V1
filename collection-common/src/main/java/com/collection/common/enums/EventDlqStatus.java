package com.collection.common.enums;

/** MySQL DLQ 处置状态。 */
public enum EventDlqStatus {
    PENDING,
    REDRIVING,
    REDRIVEN,
    TERMINATED
}
