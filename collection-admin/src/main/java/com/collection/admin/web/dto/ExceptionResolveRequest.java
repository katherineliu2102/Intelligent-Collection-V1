package com.collection.admin.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ExceptionResolveRequest {
    @NotBlank(message = "action is required")
    @Pattern(
            regexp = "RETRY|IGNORE|MANUAL_FIXED",
            message = "action must be RETRY, IGNORE, or MANUAL_FIXED")
    private String action;

    private String note;
}
