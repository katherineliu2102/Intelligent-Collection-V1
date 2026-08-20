package com.collection.admin.web.dto;

import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Data;

/** 更新触达计划模板请求。 */
@Data
public class PlanTemplateRequest {

    @NotBlank(message = "templateCode is required")
    private String templateCode;

    @NotBlank(message = "stage is required")
    private String stage;

    private String tone = "STANDARD";

    private String productCode;

    private List<Step> steps;
    /** 生产绝对槽位模板；配置时优先于扁平 steps。 */
    private List<DayBlock> dayBlocks;

    @NotNull(message = "version is required")
    private Integer version;

    private String reason;

    @Data
    public static class Step {
        @NotBlank(message = "channel is required")
        private String channel;

        private int delayMin = 0;
        private int observeMin = 0;
        private long templateId = 0L;
    }

    @Data
    public static class DayBlock {
        private int dpdDay;
        private List<Slot> slots;
    }

    @Data
    public static class Slot {
        @NotBlank(message = "channel is required")
        private String channel;

        @NotBlank(message = "time is required")
        private String time;

        private int observeMin = 0;
        private long templateId = 0L;
    }
}
