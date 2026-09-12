package com.collection.admin.web.dto;

import javax.validation.constraints.AssertTrue;
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

    /** 二次确认；缺省或 false 由校验拒绝。 */
    private boolean confirm;

    @AssertTrue(message = "confirm must be true")
    public boolean isConfirmed() {
        return confirm;
    }
}
