package com.collection.engine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.schedule.OutreachSlotPolicy;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class OutreachSlotPolicyTest {

    @Test
    void nextSlotAfter0915Is1130() {
        assertThat(OutreachSlotPolicy.nextProductSlot(LocalTime.of(9, 15)))
                .isEqualTo(LocalTime.of(11, 30));
    }

    @Test
    void nextSlotAfter1840IsDayEnd() {
        assertThat(OutreachSlotPolicy.nextProductSlot(LocalTime.of(18, 40)))
                .isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void yesterdayAiIsMissedDay() {
        assertThat(
                        OutreachSlotPolicy.decide(
                                LocalDateTime.of(2026, 9, 13, 9, 15),
                                LocalDateTime.of(2026, 9, 14, 8, 0)))
                .isEqualTo(OutreachSlotPolicy.Decision.SKIP_MISSED_DAY);
    }

    @Test
    void sameDay0915At1115StillFires() {
        assertThat(
                        OutreachSlotPolicy.decide(
                                LocalDateTime.of(2026, 9, 14, 9, 15),
                                LocalDateTime.of(2026, 9, 14, 11, 15)))
                .isEqualTo(OutreachSlotPolicy.Decision.FIRE);
    }

    @Test
    void sameDay0915At1130IsPastNextSlot() {
        assertThat(
                        OutreachSlotPolicy.decide(
                                LocalDateTime.of(2026, 9, 14, 9, 15),
                                LocalDateTime.of(2026, 9, 14, 11, 30)))
                .isEqualTo(OutreachSlotPolicy.Decision.SKIP_PAST_NEXT_SLOT);
    }

    @Test
    void futureCalendarDayWaitsOwnSlot() {
        assertThat(
                        OutreachSlotPolicy.decide(
                                LocalDateTime.of(2026, 9, 15, 9, 15),
                                LocalDateTime.of(2026, 9, 14, 8, 0)))
                .isEqualTo(OutreachSlotPolicy.Decision.WAIT_OWN_SLOT);
    }

    @Test
    void smsBefore8amWaitsOwnSlot() {
        assertThat(
                        OutreachSlotPolicy.decide(
                                LocalDateTime.of(2026, 9, 14, 8, 0),
                                LocalDateTime.of(2026, 9, 14, 3, 35)))
                .isEqualTo(OutreachSlotPolicy.Decision.WAIT_OWN_SLOT);
    }
}
