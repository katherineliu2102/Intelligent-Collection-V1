package com.collection.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.admin.dashboard.DashboardQueryService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardControllerTest {

    private DashboardQueryService queries;
    private DashboardController controller;

    @BeforeEach
    void setUp() {
        queries = mock(DashboardQueryService.class);
        controller = new DashboardController(queries);
    }

    @Test
    void todayDelegatesToQueryService() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slots", Collections.emptyList());
        when(queries.todayExecution()).thenReturn(payload);

        Map<String, Object> body = controller.today();

        verify(queries).todayExecution();
        assertThat(body.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(body.get("data")).isSameAs(payload);
    }

    @Test
    void dailyByChannelDoesNotUseLegacyMergedDaily() {
        when(queries.dailyByChannel(7))
                .thenReturn(Collections.singletonMap("series", Collections.emptyList()));

        controller.dailyByChannel(7);

        verify(queries).dailyByChannel(7);
    }
}
