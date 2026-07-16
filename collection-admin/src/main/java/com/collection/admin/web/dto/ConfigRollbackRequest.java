package com.collection.admin.web.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfigRollbackRequest {
    @NotNull(message = "targetVersion is required")
    @Min(value = 0, message = "targetVersion must be >= 0")
    private Long targetVersion;

    @NotBlank(message = "reason is required")
    private String reason;
}
