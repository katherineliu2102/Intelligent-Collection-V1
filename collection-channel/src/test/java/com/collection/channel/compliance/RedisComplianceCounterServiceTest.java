package com.collection.channel.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.collection.common.service.ComplianceCounterService;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisComplianceCounterServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 5);

    @SuppressWarnings("unchecked")
    @Test
    void incrementsChannelAndTotalInOneCall() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenReturn(Arrays.asList(1L, 2L));

        ComplianceCounterService.Counts counts =
                new RedisComplianceCounterService(redis).tryConsume(7L, "SMS", DATE, 1, 3);

        assertThat(counts.channel).isEqualTo(1);
        assertThat(counts.total).isEqualTo(2);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        Mockito.verify(redis).execute(any(RedisScript.class), keys.capture(), any());
        assertThat(keys.getValue())
                .containsExactly(
                        "collection:compliance:daily:7:SMS:2026-08-05",
                        "collection:compliance:daily:7:ALL:2026-08-05");
    }

    @SuppressWarnings("unchecked")
    @Test
    void expiresAtNextPhtMidnight() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenReturn(Arrays.asList(1L, 1L));

        new RedisComplianceCounterService(redis).tryConsume(7L, "SMS", DATE, 1, 3);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(redis).execute(any(RedisScript.class), any(List.class), args.capture());
        long expected =
                DATE.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Manila")).toEpochSecond();
        assertThat(args.getValue()).isEqualTo(String.valueOf(expected));
    }

    @SuppressWarnings("unchecked")
    @Test
    void propagatesRedisFailureForFailClose() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(
                        () ->
                                new RedisComplianceCounterService(redis)
                                        .tryConsume(7L, "SMS", DATE, 1, 3))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void rejectsEmptyScriptResponse() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any())).thenReturn(null);

        assertThatThrownBy(
                        () ->
                                new RedisComplianceCounterService(redis)
                                        .tryConsume(7L, "SMS", DATE, 1, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void memoryCounterKeepsChannelAndTotalSeparate() {
        InMemoryComplianceCounterService counter = new InMemoryComplianceCounterService();

        counter.tryConsume(7L, "SMS", DATE, 1, 3);
        ComplianceCounterService.Counts second = counter.tryConsume(7L, "PUSH", DATE, 1, 3);

        assertThat(second.channel).isEqualTo(1);
        assertThat(second.total).isEqualTo(2);
    }
}
