package com.collection.admin.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ComplianceActionRequest {
    @NotNull(message = "caseId is required")
    private Long caseId;

    private Long userId;

    @NotBlank(message = "reason is required")
    private String reason;
}
