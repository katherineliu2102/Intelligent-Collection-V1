package com.collection.engine.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.common.enums.EventType;
import com.collection.common.enums.OutboxStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.OutboxEvent;
import com.collection.common.repository.EventOutboxRepository;
import com.collection.common.util.JsonUtil;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.metrics.CollectionMetrics;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 发件箱兜底发布器（核心引擎规格 §7.2）。全 mock，不连库。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherTest {

    @Mock private EventOutboxRepository repository;
    @Mock private CollectionEventBus eventBus;
    @Spy private EngineProperties props = new EngineProperties();
    @Spy private CollectionMetrics metrics = CollectionMetrics.local();

    @InjectMocks private OutboxPublisher publisher;

    @Test
    @DisplayName("到期未销账 → 重发并置 PUBLISHED")
    void republishesDueRowAndMarksPublished() {
        when(repository.claimDueForRepublish(any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(row("STEP_COMPLETED:100:1:0", 0)));

        publisher.republishDue();

        ArgumentCaptor<CollectionEvent> published = ArgumentCaptor.forClass(CollectionEvent.class);
        verify(eventBus).publish(published.capture());
        assertThat(published.getValue().getEventType()).isEqualTo(EventType.STEP_COMPLETED);
        assertThat(published.getValue().getLong(CollectionEvent.PLAN_ID)).isEqualTo(100L);
        verify(repository).markPublished("STEP_COMPLETED:100:1:0");
    }

    @Test
    @DisplayName("扫描时传入短租约，保证多实例只能由认领方发布")
    void claimsRowsWithConfiguredLease() {
        props.getOutbox().setLeaseSeconds(75);
        when(repository.claimDueForRepublish(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        LocalDateTime before = LocalDateTime.now();
        publisher.republishDue();

        ArgumentCaptor<LocalDateTime> leaseUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).claimDueForRepublish(any(), leaseUntil.capture(), anyInt());
        assertThat(leaseUntil.getValue()).isAfter(before.plusSeconds(70));
    }

    @Test
    @DisplayName("重发失败 → 退避推迟，不丢记录")
    void schedulesBackoffRetryOnPublishFailure() {
        when(repository.claimDueForRepublish(any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(row("STEP_COMPLETED:100:1:0", 2)));
        doThrow(new IllegalStateException("bus down")).when(eventBus).publish(any());

        LocalDateTime before = LocalDateTime.now();
        publisher.republishDue();

        ArgumentCaptor<LocalDateTime> nextRetry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).scheduleRetry(eq("STEP_COMPLETED:100:1:0"), nextRetry.capture(), any());
        // grace=30s, factor=3, retryCount=2 → 30*3*3 = 270s
        assertThat(nextRetry.getValue()).isAfter(before.plusSeconds(260));
        verify(repository, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("重发次数耗尽 → FAILED 转人工，不再自动重发")
    void marksFailedWhenRetriesExhausted() {
        props.getOutbox().setMaxRetryCount(3);
        when(repository.claimDueForRepublish(any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(row("STEP_COMPLETED:100:1:0", 2)));
        doThrow(new IllegalStateException("bus down")).when(eventBus).publish(any());

        publisher.republishDue();

        verify(repository).markFailed(eq("STEP_COMPLETED:100:1:0"), any());
        verify(repository, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    @DisplayName("payload 反序列化不出事件 → 直接 FAILED，不占用后续扫描批次")
    void marksFailedOnUndeserializablePayload() {
        OutboxEvent broken = row("STEP_COMPLETED:100:1:0", 0);
        broken.setPayload("{\"eventType\":null}");
        when(repository.claimDueForRepublish(any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(broken));

        publisher.republishDue();

        verify(eventBus, never()).publish(any());
        verify(repository).markFailed(eq("STEP_COMPLETED:100:1:0"), any());
    }

    @Test
    @DisplayName("payload 反序列化抛异常 → 仅标 FAILED，后续批次仍可处理")
    void marksFailedWhenPayloadDeserializationThrows() {
        OutboxEvent broken = row("STEP_COMPLETED:100:1:0", 0);
        broken.setPayload("{not-json");
        when(repository.claimDueForRepublish(any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(broken));

        publisher.republishDue();

        verify(eventBus, never()).publish(any());
        verify(repository).markFailed(eq("STEP_COMPLETED:100:1:0"), any());
    }

    @Test
    @DisplayName("关闭发件箱 → 不扫描不重发")
    void doesNothingWhenDisabled() {
        props.getOutbox().setEnabled(false);

        publisher.republishDue();

        verify(repository, never()).claimDueForRepublish(any(), any(), anyInt());
        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("扫描抛错只记日志，交由下一轮自愈")
    void swallowsScanFailure() {
        when(repository.claimDueForRepublish(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        publisher.republishDue();

        verify(eventBus, never()).publish(any());
    }

    private static OutboxEvent row(String eventId, int retryCount) {
        CollectionEvent event =
                CollectionEvent.of(EventType.STEP_COMPLETED)
                        .with(CollectionEvent.CASE_ID, 1002L)
                        .with(CollectionEvent.PLAN_ID, 100L)
                        .with(CollectionEvent.STEP_ID, 200L);
        event.setEventId(eventId);

        OutboxEvent row = new OutboxEvent();
        row.setId(1L);
        row.setEventId(eventId);
        row.setEventType(EventType.STEP_COMPLETED.name());
        row.setPlanId(100L);
        row.setCaseId(1002L);
        row.setPayload(JsonUtil.toJson(event));
        row.setStatus(OutboxStatus.PENDING);
        row.setRetryCount(retryCount);
        row.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return row;
    }

    @Test
    @DisplayName("单批上限取自配置，防止积压时一次性捞空表")
    void usesConfiguredBatchSize() {
        props.getOutbox().setBatchSize(7);
        when(repository.claimDueForRepublish(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        publisher.republishDue();

        verify(repository).claimDueForRepublish(any(), any(), eq(7));
    }
}
