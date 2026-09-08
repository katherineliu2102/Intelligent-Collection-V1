package com.collection.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WaveKeyTest {

    @Test
    void parsesMocasaBatchId() {
        assertThat(WaveKey.fromBatchId("mocasa-20260907-0915-1")).isEqualTo("20260907-0915");
        assertThat(WaveKey.slotHhmm("20260907-0915")).isEqualTo("0915");
        assertThat(WaveKey.formatDisplay("20260908-0915")).isEqualTo("2026-09-08  09:15");
        assertThat(WaveKey.formatDisplay("UNKNOWN")).isEqualTo("未分波次");
    }

    @Test
    void rejectsUnparseable() {
        assertThat(WaveKey.fromBatchId(null)).isNull();
        assertThat(WaveKey.fromBatchId("batch-9")).isNull();
        assertThat(WaveKey.slotHhmm("UNKNOWN")).isNull();
    }

    @Test
    void mapsTriggerClockToSlot() {
        assertThat(WaveKey.slotHhmmFromTrigger(9, 15)).isEqualTo("0915");
        assertThat(WaveKey.slotHhmmFromTrigger(8, 50)).isEqualTo("0915");
        assertThat(WaveKey.slotHhmmFromTrigger(14, 30)).isEqualTo("1430");
        assertThat(WaveKey.slotHhmmFromTrigger(14, 0)).isNull();
        assertThat(WaveKey.slotHhmmFromTrigger(12, 0)).isNull();
    }
}
