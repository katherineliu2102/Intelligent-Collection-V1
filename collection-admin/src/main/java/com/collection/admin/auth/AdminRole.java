package com.collection.admin.auth;

import org.apache.commons.lang3.StringUtils;

/**
 * 管理面三角色。未知或空值按 VIEWER 处理（fail-closed：不能写成超管）。
 *
 * <p>角色只来自账号配置，不来自请求体。
 */
public enum AdminRole {
    VIEWER,
    OPERATOR,
    SYSTEM_ADMIN;

    public static AdminRole parse(Object raw) {
        if (raw == null) {
            return VIEWER;
        }
        String value = String.valueOf(raw).trim();
        if (StringUtils.isBlank(value)) {
            return VIEWER;
        }
        try {
            return AdminRole.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return VIEWER;
        }
    }

    public static AdminRole fromSessionUser(Object user) {
        if (!(user instanceof java.util.Map)) {
            return VIEWER;
        }
        return parse(((java.util.Map<?, ?>) user).get("role"));
    }
}
