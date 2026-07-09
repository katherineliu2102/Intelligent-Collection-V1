package com.collection.admin.web.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EvaluationSettingsRequest {
    @NotNull(message = "holdoutRatio is required")
    @DecimalMin(value = "0.01", message = "holdoutRatio must be >= 0.01")
    @DecimalMax(value = "0.20", message = "holdoutRatio must be <= 0.20")
    private BigDecimal holdoutRatio;

    @NotNull(message = "version is required")
    @Min(value = 0, message = "version must be >= 0")
    private Integer version;

    private String reason;
}
