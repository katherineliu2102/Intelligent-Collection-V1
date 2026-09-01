package com.collection.common.repository;

import com.collection.common.model.EmailSuppression;

/**
 * Email 抑制名单持久层。表 t_email_suppression，写入方为 SendGrid Event Webhook，读取方为 ExecutionGuard。
 *
 * <p>与 SendGrid 自家的 suppression list 并存：供应商侧会拦掉发信，但我们本地不知道， 于是每个里程碑仍会建 EMAIL 步骤、调一次 API、拿一个
 * dropped，step 记成失败。本地留一份 才能在 Guard 就 BLOCK 掉，把「地址已废」表达成合规拦截而非渠道故障。
 */
public interface EmailSuppressionRepository {

    /** 写入或忽略（同一地址重复抑制不覆盖首次原因与时间）。 */
    void suppress(EmailSuppression suppression);

    /** 地址是否在抑制名单内；{@code email} 为空一律返回 false，由 Guard 的 NO_EMAIL 分支负责。 */
    boolean isSuppressed(String email);
}
