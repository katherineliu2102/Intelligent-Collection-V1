package com.collection.ingestion.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CaseProjectionAssemblerTest {

    @Test
    void coerceOverdueDpd_rewritesNegativeDpdWhenDueDateIsPast() {
        Integer corrected =
                CaseProjectionAssembler.coerceOverdueDpd(
                        -29,
                        new BigDecimal("240.77"),
                        LocalDate.of(2026, 9, 7),
                        LocalDate.of(2026, 9, 8),
                        535728L);
        assertEquals(1, corrected);
    }

    @Test
    void coerceOverdueDpd_keepsNegativeWhenOverdueIsZero() {
        Integer kept =
                CaseProjectionAssembler.coerceOverdueDpd(
                        -29,
                        BigDecimal.ZERO,
                        LocalDate.of(2026, 9, 7),
                        LocalDate.of(2026, 9, 8),
                        535728L);
        assertEquals(-29, kept);
    }
}
