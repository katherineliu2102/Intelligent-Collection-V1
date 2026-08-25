package com.collection.channel.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.service.ComplianceCounterService;
import java.time.LocalDate;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class InMemoryComplianceCounterServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);

    @Test
    void countsPerChannelAndTotal() {
        InMemoryComplianceCounterService svc = new InMemoryComplianceCounterService();

        assertThat(svc.tryConsume(1L, "SMS", DATE, 5, 5).channel).isEqualTo(1);
        assertThat(svc.tryConsume(1L, "SMS", DATE, 5, 5).channel).isEqualTo(2);

        ComplianceCounterService.Counts push = svc.tryConsume(1L, "PUSH", DATE, 5, 5);
        assertThat(push.channel).isEqualTo(1);
        assertThat(push.total).isEqualTo(3);
    }

    @Test
    void releaseGivesBackBothChannelAndTotal() {
        InMemoryComplianceCounterService svc = new InMemoryComplianceCounterService();
        svc.tryConsume(1L, "SMS", DATE, 5, 5);

        svc.release(1L, "SMS", DATE);

        ComplianceCounterService.Counts next = svc.tryConsume(1L, "SMS", DATE, 5, 5);
        assertThat(next.channel).isEqualTo(1);
        assertThat(next.total).isEqualTo(1);
    }

    /** 负数会凭空放大该用户当天的额度，比少还一次危险得多。 */
    @Test
    void releaseNeverDrivesCountersNegative() {
        InMemoryComplianceCounterService svc = new InMemoryComplianceCounterService();
        svc.tryConsume(1L, "SMS", DATE, 5, 5);

        svc.release(1L, "SMS", DATE);
        svc.release(1L, "SMS", DATE);
        svc.release(1L, "SMS", DATE);

        assertThat(svc.tryConsume(1L, "SMS", DATE, 5, 5).channel).isEqualTo(1);
    }

    @Test
    void releaseOnUntrackedKeyIsNoOp() {
        InMemoryComplianceCounterService svc = new InMemoryComplianceCounterService();

        svc.release(42L, "EMAIL", DATE);

        assertThat(svc.tryConsume(42L, "EMAIL", DATE, 5, 5).channel).isEqualTo(1);
    }

    @Test
    void clearResetsOnlyTheGivenUser() {
        InMemoryComplianceCounterService svc = new InMemoryComplianceCounterService();
        svc.tryConsume(94999L, "SMS", DATE, 5, 5);
        svc.tryConsume(9L, "SMS", DATE, 5, 5);

        // 前缀带分隔符，否则 9 会连 94999 一起清掉。
        svc.clear(Collections.singletonList(9L));

        assertThat(svc.tryConsume(9L, "SMS", DATE, 5, 5).channel).isEqualTo(1);
        assertThat(svc.tryConsume(94999L, "SMS", DATE, 5, 5).channel).isEqualTo(2);
    }
}
