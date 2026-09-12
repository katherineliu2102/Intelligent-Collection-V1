package com.collection.admin.web.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.collection.admin.web.WebhookSecurityProperties;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.PlanStatus;
import com.collection.common.enums.Stage;
import com.collection.common.enums.StepStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.event.CollectionEventBus;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.repository.ChannelCallbackAuditRepository;
import com.collection.common.repository.ContactPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class FacadeWebhookServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CollectionEventBus eventBus;
    private ChannelCallbackAuditRepository auditRepository;
    private ContactPlanRepository planRepository;
    private JdbcTemplate jdbcTemplate;
    private FacadeWebhookService service;

    @BeforeEach
    void setUp() {
        eventBus = mock(CollectionEventBus.class);
        auditRepository = mock(ChannelCallbackAuditRepository.class);
        planRepository = mock(ContactPlanRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForMap(contains("t_ai_collection"), any()))
                .thenThrow(new EmptyResultDataAccessException(1));
        ChannelProperties channelProperties = new ChannelProperties();
        channelProperties.getFacade().setCallbackSecret("test-secret");
        WebhookSecurityProperties webhookSecurity = new WebhookSecurityProperties();
        webhookSecurity.setSignatureRequired(true);
        service = new FacadeWebhookService();
        ReflectionTestUtils.setField(service, "eventBus", eventBus);
        ReflectionTestUtils.setField(service, "callbackAuditRepository", auditRepository);
        ReflectionTestUtils.setField(service, "planRepository", planRepository);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "channelProperties", channelProperties);
        ReflectionTestUtils.setField(service, "webhookSecurityProperties", webhookSecurity);
    }

    @Test
    void answeredPublishesWithoutDisposition() throws Exception {
        String json = answeredJson("sess-1", "batch-9");
        when(auditRepository.existsValidByProviderMsgId("sess-1")).thenReturn(false);

        Map<String, Object> out =
                service.handle(objectMapper.readTree(json), sign(json, "test-secret"));

        assertEquals(Boolean.TRUE, out.get("ok"));
        ArgumentCaptor<CollectionEvent> captor = ArgumentCaptor.forClass(CollectionEvent.class);
        verify(eventBus, times(1)).publish(captor.capture());
        CollectionEvent event = captor.getValue();
        assertEquals("ANSWERED", event.getString(CollectionEvent.RESULT));
        assertNull(event.getString(CollectionEvent.DISPOSITION));
        assertEquals("batch-9", event.getString(CollectionEvent.PROVIDER_MSG_ID));
        assertEquals(11L, event.getLong(CollectionEvent.PLAN_ID).longValue());
        assertEquals(22L, event.getLong(CollectionEvent.STEP_ID).longValue());
        verify(auditRepository, times(1)).save(any());
    }

    @Test
    void duplicateSessionDoesNotPublishTwice() throws Exception {
        String json =
                "{\"event\":\"session.completed\",\"session_id\":\"sess-1\","
                        + "\"client_metadata\":{\"plan_id\":11,\"step_id\":22},"
                        + "\"line_outcome\":{\"party\":\"human\",\"reason\":\"NORMAL\",\"was_answered\":true},"
                        + "\"ai_result\":{\"effective_conversation\":true}}";
        when(auditRepository.existsValidByProviderMsgId("sess-1")).thenReturn(true);

        service.handle(objectMapper.readTree(json), sign(json, "test-secret"));
        verify(eventBus, never()).publish(any(CollectionEvent.class));
        verify(auditRepository, times(1)).save(any());
    }

    @Test
    void missingIdentityDoesNotPublish() throws Exception {
        String json =
                "{\"event\":\"session.completed\",\"session_id\":\"sess-x\","
                        + "\"line_outcome\":{\"reason\":\"NORMAL\",\"was_answered\":true,\"was_ai_connected\":true}}";
        when(auditRepository.existsValidByProviderMsgId("sess-x")).thenReturn(false);

        service.handle(objectMapper.readTree(json), sign(json, "test-secret"));
        verify(eventBus, never()).publish(any(CollectionEvent.class));
        verify(planRepository, never()).findActivePlansByCase(any());
        verify(auditRepository, times(1)).save(any());
    }

    @Test
    void uniqueExecutingAiCallFallbackPublishes() throws Exception {
        String json =
                "{\"event\":\"session.completed\",\"session_id\":\"sess-2\",\"external_case_id\":\"9\","
                        + "\"line_outcome\":{\"party\":\"human\",\"reason\":\"NORMAL\",\"was_answered\":true},"
                        + "\"ai_result\":{\"effective_conversation\":true}}";
        when(auditRepository.existsValidByProviderMsgId("sess-2")).thenReturn(false);
        ContactPlan plan = new ContactPlan();
        plan.setId(11L);
        plan.setStatus(PlanStatus.STEP_EXECUTING);
        ContactPlanStep step = new ContactPlanStep();
        step.setId(22L);
        step.setPlanId(11L);
        step.setChannelType(ChannelType.AI_CALL);
        step.setStatus(StepStatus.EXECUTING);
        when(planRepository.findActivePlansByCase(9L)).thenReturn(Collections.singletonList(plan));
        when(planRepository.findStepsByPlan(11L)).thenReturn(Collections.singletonList(step));

        service.handle(objectMapper.readTree(json), sign(json, "test-secret"));

        ArgumentCaptor<CollectionEvent> captor = ArgumentCaptor.forClass(CollectionEvent.class);
        verify(eventBus, times(1)).publish(captor.capture());
        assertEquals(11L, captor.getValue().getLong(CollectionEvent.PLAN_ID).longValue());
        assertEquals(22L, captor.getValue().getLong(CollectionEvent.STEP_ID).longValue());
    }

    @Test
    void badSignatureIs401() throws Exception {
        String json = "{\"event\":\"session.completed\",\"session_id\":\"x\"}";
        assertThrows(
                ResponseStatusException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() throws Exception {
                        service.handle(objectMapper.readTree(json), "deadbeef");
                    }
                });
        verify(eventBus, never()).publish(any(CollectionEvent.class));
        verify(auditRepository, times(1)).save(any());
    }

    @Test
    void writesStageAndDpdSnapshotsFromPlanAndProjection() throws Exception {
        String json = answeredJson("sess-1", "batch-9");
        when(auditRepository.existsValidByProviderMsgId("sess-1")).thenReturn(false);
        ContactPlan plan = new ContactPlan();
        plan.setId(11L);
        plan.setCaseId(9L);
        plan.setStage(Stage.S2);
        when(planRepository.findById(11L)).thenReturn(plan);
        Map<String, Object> projection = new HashMap<String, Object>();
        projection.put("stage", "S2");
        projection.put("dpd", Integer.valueOf(45));
        when(jdbcTemplate.queryForMap(contains("t_ai_collection"), eq(9L))).thenReturn(projection);

        service.handle(objectMapper.readTree(json), sign(json, "test-secret"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate)
                .update(
                        sql.capture(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        assertTrue(sql.getValue().contains("stage_snapshot"));
        assertTrue(sql.getValue().contains("party"));
        assertTrue(sql.getValue().contains("effective_conversation"));
        assertTrue(sql.getValue().contains("disposition"));
        assertTrue(sql.getValue().contains("dpd_snapshot"));
        assertTrue(sql.getValue().contains("COALESCE(stage_snapshot, VALUES(stage_snapshot))"));
        verify(planRepository).findById(11L);
        verify(jdbcTemplate).queryForMap(contains("t_ai_collection"), eq(9L));
    }

    private static String answeredJson(String sessionId, String batchId) {
        return "{\"event\":\"session.completed\",\"session_id\":\""
                + sessionId
                + "\",\"batch_id\":\""
                + batchId
                + "\","
                + "\"client_metadata\":{\"plan_id\":11,\"step_id\":22,\"case_id\":9},"
                + "\"line_outcome\":{\"party\":\"human\",\"reason\":\"NORMAL\",\"was_answered\":true},"
                + "\"ai_result\":{\"effective_conversation\":true,\"disposition\":\"promise_to_pay\"}}";
    }

    private static String sign(String json, String secret) throws Exception {
        String canonical = FacadeCanonicalJson.dumps(new ObjectMapper().readTree(json));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            hex.append(String.format("%02x", raw[i] & 0xff));
        }
        return hex.toString();
    }
}
