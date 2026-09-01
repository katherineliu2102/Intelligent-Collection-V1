package com.collection.common.model;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * Email 抑制名单条目。写入 t_email_suppression。
 *
 * <p>按地址而非按案件抑制：hard bounce 与投诉都是地址级事实，同一地址换个案件再发一次仍会 bounce， 并继续损耗发信域的信誉。{@code caseId}
 * 只记首次触发者，供溯源。
 */
@Data
public class EmailSuppression {

    private Long id;
    /** 收件地址，写入前统一转小写并去空白。 */
    private String email;
    /** HARD_BOUNCE / DROPPED / SPAM_REPORT / UNSUBSCRIBE。 */
    private String reason;
    /** 供应商给出的原因原文，截断到 512 字符。 */
    private String detail;
    /** 首次触发抑制的案件，仅作溯源，不参与判定。 */
    private Long caseId;

    private LocalDateTime createdAt;
}
