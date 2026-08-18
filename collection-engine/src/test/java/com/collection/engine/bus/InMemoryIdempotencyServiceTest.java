package com.collection.engine.bus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InMemoryIdempotencyServiceTest {

    @Test
    void releaseAllowsPreDispatchRetryToAcquireSameLock() {
        InMemoryIdempotencyService service = new InMemoryIdempotencyService();

        assertThat(service.acquire("lock:plan:100:1:0", 60)).isTrue();
        assertThat(service.acquire("lock:plan:100:1:0", 60)).isFalse();

        service.release("lock:plan:100:1:0");

        assertThat(service.acquire("lock:plan:100:1:0", 60)).isTrue();
    }
}
