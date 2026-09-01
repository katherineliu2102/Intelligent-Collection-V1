package com.collection.ingestion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.collection.common.enums.Stage;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.service.CaseService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IngestionServiceTest {

    @Test
    void caseEventSnapshotDoesNotRequireLegacyDueDate() {
        IngestionService service = new IngestionService();
        CollectionEventBus eventBus = mock(CollectionEventBus.class);
        ReflectionTestUtils.setField(service, "eventBus", eventBus);
        ReflectionTestUtils.setField(service, "caseService", mock(CaseService.class));

        Map<String, Object> fields = new HashMap<>();
        fields.put("dpd", 1);
        fields.put("product", "3");
        fields.put("totalOutstanding", BigDecimal.TEN);
        fields.put("penaltyAmount", BigDecimal.ZERO);

        assertDoesNotThrow(() -> service.ingestCase(525441L, 2145521L, Stage.S1, fields));
        verify(eventBus).publish(org.mockito.ArgumentMatchers.any());
    }
}
