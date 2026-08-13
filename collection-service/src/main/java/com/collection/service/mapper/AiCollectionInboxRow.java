package com.collection.service.mapper;

import lombok.Data;

/** t_ai_collection_inbox 行。 */
@Data
public class AiCollectionInboxRow {

    private String eventId;
    private Long caseId;
    private Long caseVersion;
    private String messageType;
    private String eventType;
    private String payload;
    private boolean projectionApplied;
    /** PENDING / PUBLISHED / SKIPPED。 */
    private String publishStatus;
}
