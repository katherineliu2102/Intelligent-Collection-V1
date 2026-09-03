package com.collection.ingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.repository.OwnerReconcileRepository;
import com.collection.common.service.CaseService;
import com.collection.ingestion.IngestionService;
import com.collection.ingestion.config.IngestionProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnerReconcileHandlerTest {

    @Mock private IngestionProperties props;
    @Mock private CaseService caseService;
    @Mock private IngestionService ingestionService;
    @Mock private OwnerReconcileRepository ownerReconcileRepository;
    @InjectMocks private OwnerReconcileHandler handler;

    @BeforeEach
    void defaults() {
        when(props.getLoanIdWhitelist()).thenReturn(Collections.singletonList(1001L));
        when(ownerReconcileRepository.completedOn(any())).thenReturn(false);
    }

    @Test
    void whitelistLeave_publishesRoutedToLegacy() {
        CaseInfo info = new CaseInfo();
        info.setUserId(9L);
        info.setOwnerDate(LocalDate.now(ZoneId.of("Asia/Manila")).minusDays(1));
        when(caseService.getCaseInfo(1001L)).thenReturn(info);

        int n = handler.advance();

        assertThat(n).isEqualTo(1);
        verify(ingestionService).routedToLegacy(1001L, 9L);
        verify(ingestionService, never()).ingestCase(anyLong(), any(), any(), any());
        verify(ownerReconcileRepository).markCompleted(any(), eq(1));
    }

    @Test
    void emptyInbox_fullScan_doesNotMarkCompleted() {
        when(props.getLoanIdWhitelist()).thenReturn(Collections.emptyList());
        when(ownerReconcileRepository.countCaseEventsOn(any())).thenReturn(0);

        int n = handler.advance();

        assertThat(n).isZero();
        verify(ingestionService, never()).routedToLegacy(anyLong(), anyLong());
        verify(ingestionService, never()).ingestCase(anyLong(), any(), any(), any());
        verify(ownerReconcileRepository, never()).markCompleted(any(), anyInt());
    }

    @Test
    void whitelistEnter_publishesCaseIngested() {
        CaseInfo info = new CaseInfo();
        info.setUserId(9L);
        info.setStage(Stage.S1);
        info.setOwnerDate(LocalDate.now(ZoneId.of("Asia/Manila")));
        info.setRepaid(false);
        when(caseService.getCaseInfo(1001L)).thenReturn(info);
        when(caseService.getContextSnapshot(1001L)).thenReturn(new ContextSnapshot());
        when(ingestionService.currentSnapshotFields(any())).thenReturn(Collections.emptyMap());

        int n = handler.advance();

        assertThat(n).isEqualTo(1);
        verify(ingestionService).ingestCase(eq(1001L), eq(9L), eq(Stage.S1), any());
        verify(ingestionService, never()).routedToLegacy(anyLong(), anyLong());
    }
}
