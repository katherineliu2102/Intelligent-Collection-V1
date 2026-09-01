package com.collection.service.repository;

import java.util.Locale;

/**
 * 抑制名单的地址规范化。
 *
 * <p>写入与查询必须共用同一套规范化，否则 SendGrid 回传的 {@code Wzy@126.COM} 会写成一行， 而 Guard 用档案里的 {@code wzy@126.com}
 * 查不到，抑制静默失效。{@code Locale.ROOT} 而非 默认 locale：土耳其语环境下 {@code toLowerCase()} 会把 I 变成无点 ı。
 */
final class EmailSuppressionSupport {

    private EmailSuppressionSupport() {}

    /** 返回规范化地址；输入为空白则返回 {@code null}。 */
    static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
