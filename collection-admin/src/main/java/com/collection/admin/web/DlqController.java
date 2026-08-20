package com.collection.admin.web;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.enums.EventDlqStatus;
import com.collection.common.enums.EventType;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.EventDlq;
import com.collection.common.repository.EventDlqRepository;
import com.collection.common.util.JsonUtil;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控 DLQ 重放：只接受显式 eventId 列表，鉴权由 {@code /ops/**} 的登录拦截器承担。
 *
 * <p>仅 {@code MAX_DELIVERY_EXCEEDED} 可重放；解析失败与无 Handler 直接终止。 {@code PLAN_STEP_DUE}
 * 会直接驱动渠道发送，触达窗口外保持 PENDING 等待窗口内再重放。
 */
@Validated
@RestController
@RequestMapping("/ops/dlq")
public class DlqController {

    private static final Logger log = LoggerFactory.getLogger(DlqController.class);
    private static final int MAX_REDRIVE_COUNT = 3;
    private static final String RECOVERABLE_REASON = "MAX_DELIVERY_EXCEEDED";

    private final EventDlqRepository repository;
    private final CollectionEventBus eventBus;
    private final ChannelProperties channelProperties;

    public DlqController(
            EventDlqRepository repository,
            CollectionEventBus eventBus,
            ChannelProperties channelProperties) {
        this.repository = repository;
        this.eventBus = eventBus;
        this.channelProperties = channelProperties;
    }

    @PostMapping("/redrive")
    public RedriveResult redrive(@Valid @RequestBody RedriveRequest request) {
        RedriveResult result = new RedriveResult();
        for (String eventId : request.getEventIds()) {
            EventDlq row = repository.findByEventId(eventId);
            if (row == null || row.getStatus() != EventDlqStatus.PENDING) {
                result.skipped++;
                continue;
            }
            if (!RECOVERABLE_REASON.equals(row.getFailureReason())) {
                repository.markTerminated(eventId, "NON_RECOVERABLE:" + row.getFailureReason());
                result.terminated++;
                continue;
            }
            CollectionEvent event = JsonUtil.fromJson(row.getPayload(), CollectionEvent.class);
            if (drivesTouch(event) && !withinTouchWindow()) {
                result.deferred++;
                continue;
            }
            if (!repository.claimForRedrive(eventId, request.getReason(), MAX_REDRIVE_COUNT)) {
                repository.markTerminated(eventId, "REDRIVE_LIMIT_EXCEEDED");
                result.terminated++;
                continue;
            }
            try {
                eventBus.publish(event);
                repository.markRedriven(eventId);
                result.redriven++;
            } catch (Exception e) {
                log.error("[DLQ] redrive publish failed eventId={}", eventId, e);
                repository.markTerminated(eventId, "REDRIVE_PUBLISH_FAILED");
                result.terminated++;
            }
        }
        log.info(
                "[DLQ] redrive reason={} redriven={} deferred={} terminated={} skipped={}",
                request.getReason(),
                result.redriven,
                result.deferred,
                result.terminated,
                result.skipped);
        return result;
    }

    private boolean drivesTouch(CollectionEvent event) {
        return event.getEventType() == EventType.PLAN_STEP_DUE;
    }

    private boolean withinTouchWindow() {
        ChannelProperties.Compliance compliance = channelProperties.getCompliance();
        ZoneId zone = ZoneId.of(compliance.getTimezone());
        LocalTime now = ZonedDateTime.now(zone).toLocalTime();
        LocalTime start = LocalTime.parse(compliance.getTouchWindowStart());
        LocalTime end = LocalTime.parse(compliance.getTouchWindowEnd());
        return !now.isBefore(start) && now.isBefore(end);
    }

    @Data
    public static class RedriveRequest {
        @NotEmpty private List<String> eventIds;
        @NotBlank private String reason;
    }

    @Data
    public static class RedriveResult {
        private int redriven;
        private int deferred;
        private int terminated;
        private int skipped;
    }
}
