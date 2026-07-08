package com.collection.admin.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Data;

/** 更新话术模板（SMS/Push/Email）请求。 */
@Data
public class ScriptTemplateRequest {

    @NotBlank(message = "scriptSlot is required")
    private String scriptSlot;

    @NotBlank(message = "channel is required")
    private String channel;

    private String locale = "en";

    /** SMS/Push 正文；Push 可空（仅改 title）。 */
    private String body;

    /** Push 标题；仅 PUSH 使用。 */
    private String title;

    /** Email 外部模板 ID（如 SendGrid d-xxx）。 */
    private String externalTemplateId;

    /** 乐观锁版本号（读时返回，写时回传）。 */
    @NotNull(message = "version is required")
    private Integer version;

    private String reason;
}
