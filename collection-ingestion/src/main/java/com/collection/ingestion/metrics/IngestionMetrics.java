package com.collection.ingestion.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 可查询的接入消费结果；不记录 payload、eventId 或 PII。 */
@Component
public class IngestionMetrics {

    private final MeterRegistry registry;

    public IngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void ack(String dataType, String outcome) {
        increment("collection.ingestion.ack", dataType, outcome);
    }

    public void nack(String dataType) {
        increment("collection.ingestion.nack", dataType, "TRANSIENT_FAILURE");
    }

    public void poison(String dataType) {
        increment("collection.ingestion.poison", dataType, "POISON");
    }

    public void deduped(String dataType) {
        increment("collection.ingestion.deduped", dataType, "DUPLICATE");
    }

    /**
     * dueDate 来源分布：UPSTREAM=上游下发，DERIVED=由 occurredAt-dpd 反推，ABSENT=两者皆无。
     *
     * <p>反推只是权宜之计：dpd 口径若在上游变更（宽限期、跨期取值），锚点会整体平移而不报错。 交付契约补上 dueDate 后，这里应恒为 UPSTREAM；DERIVED
     * 不归零就说明契约没落地。 ABSENT 则意味着该案件排不出任何槽位，必然建不出计划。
     */
    public void dueDateSource(String source) {
        Counter.builder("collection.ingestion.due_date.source")
                .tag("source", source)
                .register(registry)
                .increment();
    }

    private void increment(String name, String dataType, String outcome) {
        Counter.builder(name)
                .tag("data_type", dataType == null ? "unknown" : dataType)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
