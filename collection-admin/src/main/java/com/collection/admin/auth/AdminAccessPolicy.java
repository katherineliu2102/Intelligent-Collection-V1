package com.collection.admin.auth;

import org.apache.commons.lang3.StringUtils;

/**
 * 第一刀 RBAC：安全边界在后端。
 *
 * <ul>
 *   <li>GET/HEAD/OPTIONS：已登录三角色均可
 *   <li>VIEWER：禁止一切写
 *   <li>OPERATOR：日常写（话术/计划模板、评估参数、冻结、异常 ACK/Resolve）
 *   <li>SYSTEM_ADMIN：全部写；独占高危、活计划、目录、{@code /admin} 写口
 * </ul>
 */
public final class AdminAccessPolicy {

    private AdminAccessPolicy() {}

    public static boolean allows(AdminRole role, String method, String servletPath) {
        String path = servletPath == null ? "" : servletPath;
        if (isAccountAdminApi(path) && role != AdminRole.SYSTEM_ADMIN) {
            return false;
        }
        if (isSafeMethod(method)) {
            return true;
        }
        if (StringUtils.isBlank(method)) {
            return false;
        }
        if (role == AdminRole.SYSTEM_ADMIN) {
            return true;
        }
        if (role != AdminRole.OPERATOR) {
            return false;
        }
        return !isAdminOnlyWrite(servletPath);
    }

    static boolean isSafeMethod(String method) {
        if (StringUtils.isBlank(method)) {
            return false;
        }
        String m = method.trim().toUpperCase();
        return "GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m);
    }

    /**
     * OPERATOR 不可写的路径。活计划 / 目录当前几乎没有写接口，先按 fail-closed 锁上，避免后补 POST 默认放开。
     */
    static boolean isAdminOnlyWrite(String servletPath) {
        String path = normalize(servletPath);
        return pathEqualsOrPrefix(path, "/ops/dlq")
                || pathEqualsOrPrefix(path, "/ops/fault-injection")
                || pathEqualsOrPrefix(path, "/config/rollback")
                || pathEqualsOrPrefix(path, "/plans")
                || pathEqualsOrPrefix(path, "/catalog")
                || pathEqualsOrPrefix(path, "/admin");
    }

    static boolean isAccountAdminApi(String servletPath) {
        String path = normalize(servletPath);
        return pathEqualsOrPrefix(path, "/admin/accounts");
    }

    private static String normalize(String servletPath) {
        if (StringUtils.isBlank(servletPath)) {
            return "";
        }
        String path = servletPath.trim();
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static boolean pathEqualsOrPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
