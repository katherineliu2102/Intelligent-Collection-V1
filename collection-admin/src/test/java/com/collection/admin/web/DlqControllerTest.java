package com.collection.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.enums.EventDlqStatus;
import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.EventDlq;
import com.collection.common.repository.EventDlqRepository;
import com.collection.common.util.JsonUtil;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 受控重放的状态机与触达窗口门控。 */
class DlqControllerTest {

    private EventDlqRepository repository;
    private CollectionEventBus eventBus;
    private ChannelProperties channelProperties;
    private DlqController controller;

    @BeforeEach
    void setUp() {
        repository = mock(EventDlqRepository.class);
        eventBus = mock(CollectionEventBus.class);
        channelProperties = new ChannelProperties();
        openTouchWindow();
        controller = new DlqController(repository, eventBus, channelProperties);
    }

    private void openTouchWindow() {
        channelProperties.getCompliance().setTouchWindowStart("00:00");
        channelProperties.getCompliance().setTouchWindowEnd("23:59");
    }

    private void closeTouchWindow() {
        channelProperties.getCompliance().setTouchWindowStart("00:00");
        channelProperties.getCompliance().setTouchWindowEnd("00:01");
    }

    @Test
    void redrivesRecoverableEvent() {
        stub("e1", EventType.PLAN_STEP_DUE, "MAX_DELIVERY_EXCEEDED", EventDlqStatus.PENDING);
        when(repository.claimForRedrive(eq("e1"), anyString(), anyInt())).thenReturn(true);

        DlqController.RedriveResult result = controller.redrive(request("e1"));

        assertThat(result.getRedriven()).isEqualTo(1);
        verify(eventBus).publish(any(CollectionEvent.class));
        verify(repository).markRedriven("e1");
    }

    @Test
    void defersTouchEventOutsideWindow() {
        closeTouchWindow();
        stub("e1", EventType.PLAN_STEP_DUE, "MAX_DELIVERY_EXCEEDED", EventDlqStatus.PENDING);

        DlqController.RedriveResult result = controller.redrive(request("e1"));

        assertThat(result.getDeferred()).isEqualTo(1);
        verify(repository, never()).claimForRedrive(anyString(), anyString(), anyInt());
        verify(eventBus, never()).publish(any());
    }

    @Test
    void redrivesNonTouchEventOutsideWindow() {
        closeTouchWindow();
        stub("e1", EventType.STEP_COMPLETED, "MAX_DELIVERY_EXCEEDED", EventDlqStatus.PENDING);
        when(repository.claimForRedrive(eq("e1"), anyString(), anyInt())).thenReturn(true);

        assertThat(controller.redrive(request("e1")).getRedriven()).isEqualTo(1);
    }

    @Test
    void terminatesNonRecoverableEvent() {
        stub("e1", EventType.PLAN_STEP_DUE, "NO_HANDLER", EventDlqStatus.PENDING);

        DlqController.RedriveResult result = controller.redrive(request("e1"));

        assertThat(result.getTerminated()).isEqualTo(1);
        verify(repository).markTerminated("e1", "NON_RECOVERABLE:NO_HANDLER");
        verify(eventBus, never()).publish(any());
    }

    @Test
    void terminatesWhenRedriveLimitExceeded() {
        stub("e1", EventType.PLAN_STEP_DUE, "MAX_DELIVERY_EXCEEDED", EventDlqStatus.PENDING);
        when(repository.claimForRedrive(eq("e1"), anyString(), anyInt())).thenReturn(false);

        DlqController.RedriveResult result = controller.redrive(request("e1"));

        assertThat(result.getTerminated()).isEqualTo(1);
        verify(repository).markTerminated("e1", "REDRIVE_LIMIT_EXCEEDED");
    }

    @Test
    void skipsAlreadyRedrivenEvent() {
        stub("e1", EventType.PLAN_STEP_DUE, "MAX_DELIVERY_EXCEEDED", EventDlqStatus.REDRIVEN);

        assertThat(controller.redrive(request("e1")).getSkipped()).isEqualTo(1);
        verify(eventBus, never()).publish(any());
    }

    @Test
    void terminatesWhenPublishFails() {
        stub("e1", EventType.PLAN_STEP_DUE, "MAX_DELIVERY_EXCEEDED", EventDlqStatus.PENDING);
        when(repository.claimForRedrive(eq("e1"), anyString(), anyInt())).thenReturn(true);
        doThrow(new IllegalStateException("stream down")).when(eventBus).publish(any());

        DlqController.RedriveResult result = controller.redrive(request("e1"));

        assertThat(result.getTerminated()).isEqualTo(1);
        verify(repository).markTerminated("e1", "REDRIVE_PUBLISH_FAILED");
    }

    private void stub(String eventId, EventType type, String failureReason, EventDlqStatus status) {
        EventDlq row = new EventDlq();
        row.setEventId(eventId);
        row.setEventType(type.name());
        row.setFailureReason(failureReason);
        row.setStatus(status);
        row.setPayload(JsonUtil.toJson(CollectionEvent.of(type).with(CollectionEvent.CASE_ID, 1L)));
        when(repository.findByEventId(eventId)).thenReturn(row);
    }

    private DlqController.RedriveRequest request(String eventId) {
        DlqController.RedriveRequest request = new DlqController.RedriveRequest();
        request.setEventIds(Collections.singletonList(eventId));
        request.setReason("T5 drill");
        return request;
    }
}
