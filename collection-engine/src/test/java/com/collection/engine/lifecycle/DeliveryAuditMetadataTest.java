package com.collection.engine.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.collection.common.dto.StepCommand;
import com.collection.common.enums.ChannelType;
import com.collection.common.model.ContactRecord;
import com.collection.engine.config.EngineProperties;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryAuditMetadataTest {

    @Test
    void appliesProvenanceAndHmacWithoutLeakingRenderedBody() throws Exception {
        EngineProperties properties = new EngineProperties();
        properties.getDeliveryAudit().setHmacKey("test-audit-key");
        properties.getDeliveryAudit().setContentKeyId("test-key-v1");

        DeliveryAuditMetadata metadata = new DeliveryAuditMetadata();
        Field propertiesField = DeliveryAuditMetadata.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        propertiesField.set(metadata, properties);

        Map<String, Object> commandMetadata = new HashMap<>();
        commandMetadata.put(StepCommand.META_SCRIPT_SLOT, "S1_SMS_OVERDUE_NOTICE");
        commandMetadata.put(StepCommand.META_TEMPLATE_VERSION, "db:42");
        commandMetadata.put(StepCommand.META_CONFIG_VERSION, 42L);
        commandMetadata.put(StepCommand.META_SMS_BODY, "Alice owes PHP 5000. Pay now.");
        StepCommand command =
                StepCommand.builder()
                        .channelType(ChannelType.SMS)
                        .templateId("101")
                        .metadata(commandMetadata)
                        .build();

        ContactRecord record = new ContactRecord();
        metadata.apply(record, command);

        assertEquals("S1_SMS_OVERDUE_NOTICE", record.getScriptSlot());
        assertEquals("db:42", record.getTemplateVersion());
        assertEquals(Long.valueOf(42L), record.getConfigVersion());
        assertEquals("SMS:S1_SMS_OVERDUE_NOTICE@db:42", record.getRenderedRef());
        assertEquals("test-key-v1", record.getContentKeyId());
        assertNotNull(record.getContentHmac());
        assertEquals(64, record.getContentHmac().length());
        assertFalse(record.getContentSummary().contains("Alice"));
        assertFalse(record.getContentSummary().contains("5000"));
    }
}
