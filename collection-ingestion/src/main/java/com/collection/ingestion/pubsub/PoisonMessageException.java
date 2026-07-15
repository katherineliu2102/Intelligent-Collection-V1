package com.collection.ingestion.pubsub;

/**
<<<<<<< HEAD
 * 不可修复的消息（必填缺失 / 格式错误）。按 §3.2 处置：<b>ack + poison/告警</b>，<b>不 nack</b>， 避免毒丸消息无限重投。Phase 1 无 DLQ 表，记
 * warn 日志 + ack；生产接 DLQ（§2.3）。
=======
 * 不可修复的消息（必填缺失 / 格式错误）。按 §3.2 处置：<b>ack + poison/告警</b>，<b>不 nack</b>，
 * 避免毒丸消息无限重投。Phase 1 无 DLQ 表，记 warn 日志 + ack；生产接 DLQ（§2.3）。
>>>>>>> origin/ca_branch
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message) {
        super(message);
    }
}
