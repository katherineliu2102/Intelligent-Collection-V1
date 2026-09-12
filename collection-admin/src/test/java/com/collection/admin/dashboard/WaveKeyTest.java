package com.collection.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WaveKeyTest {

    @Test
    void parsesMocasaBatchId() {
        assertThat(WaveKey.fromBatchId("mocasa-20260907-0915-1")).isEqualTo("20260907-0915");
        assertThat(WaveKey.slotHhmm("20260907-0915")).isEqualTo("0915");
        assertThat(WaveKey.formatDisplay("20260908-0915")).isEqualTo("2026-09-08  09:15");
        assertThat(WaveKey.dateIso("20260908-0915")).isEqualTo("2026-09-08");
        assertThat(WaveKey.dateIso("UNKNOWN")).isNull();
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
        assertThat(WaveKey.slotHhmmFromTrigger(9, 0)).isEqualTo("0915");
        assertThat(WaveKey.slotHhmmFromTrigger(9, 29)).isEqualTo("0915");
        assertThat(WaveKey.slotHhmmFromTrigger(11, 30)).isEqualTo("1130");
        assertThat(WaveKey.slotHhmmFromTrigger(11, 15)).isEqualTo("1130");
        assertThat(WaveKey.slotHhmmFromTrigger(14, 30)).isEqualTo("1430");
        assertThat(WaveKey.slotHhmmFromTrigger(16, 15)).isEqualTo("1615");
        assertThat(WaveKey.slotHhmmFromTrigger(18, 40)).isEqualTo("1840");
        assertThat(WaveKey.slotHhmmFromTrigger(8, 50)).isNull();
        assertThat(WaveKey.slotHhmmFromTrigger(11, 0)).isNull();
        assertThat(WaveKey.slotHhmmFromTrigger(14, 0)).isNull();
        assertThat(WaveKey.slotHhmmFromTrigger(12, 0)).isNull();
        assertThat(WaveKey.fromBatchId("mocasa-20260912-1130-1")).isEqualTo("20260912-1130");
        assertThat(WaveKey.slotHhmm("20260912-1840")).isEqualTo("1840");
    }
}
