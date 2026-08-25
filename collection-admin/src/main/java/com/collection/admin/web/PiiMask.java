package com.collection.admin.web;

import org.apache.commons.lang3.StringUtils;

/**
 * 管理面输出的 PII 脱敏口径（管理后台设计文档 §5.3.1）。
 *
 * <p>电话与邮箱一律脱敏后出参；姓名、push token、话术渲染结果与外部 payload 原文不进任何管理面响应—— 那些字段没有「脱敏后仍可用」的形态，只能整体不返回。
 */
public final class PiiMask {

    private PiiMask() {}

    public static String phone(String phone) {
        if (StringUtils.isBlank(phone) || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }

    public static String email(String email) {
        if (StringUtils.isBlank(email) || !email.contains("@")) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(at);
        }
        return email.substring(0, 1) + "***" + email.substring(at);
    }
}
