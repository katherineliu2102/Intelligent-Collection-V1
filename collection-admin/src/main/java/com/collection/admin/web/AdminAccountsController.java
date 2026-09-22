package com.collection.admin.web;

import com.collection.admin.auth.AdminAccountStore;
import com.collection.admin.auth.AdminAuthInterceptor;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 最小账号管理：列表 / 创建 / 禁用 / 改角色 / 重置口令。仅 SYSTEM_ADMIN。 */
@Validated
@RestController
@RequestMapping("/admin/accounts")
public class AdminAccountsController {

    private final AdminAccountStore store;

    public AdminAccountsController(AdminAccountStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> list() {
        return ApiResponse.success(store.listPublic());
    }

    @PostMapping
    public Map<String, Object> create(
            @Valid @RequestBody CreateAccountRequest body, HttpServletRequest request) {
        store.create(body.getUsername(), body.getPassword(), body.getRole(), currentUser(request));
        return ApiResponse.success(store.listPublic());
    }

    @PatchMapping("/{id}")
    public Map<String, Object> patch(
            @PathVariable long id,
            @RequestBody PatchAccountRequest body,
            HttpServletRequest request) {
        store.patch(
                id, body.getEnabled(), body.getRole(), body.getPassword(), currentUser(request));
        return ApiResponse.success(store.listPublic());
    }

    private static String currentUser(HttpServletRequest request) {
        Object user =
                request.getSession(false) == null
                        ? null
                        : request.getSession(false).getAttribute(AdminAuthInterceptor.SESSION_USER);
        if (user instanceof Map) {
            Object name = ((Map<?, ?>) user).get("username");
            if (name != null) {
                return String.valueOf(name);
            }
        }
        return "system";
    }

    @Data
    public static class CreateAccountRequest {
        @NotBlank private String username;
        @NotBlank private String password;
        private String role;
    }

    @Data
    public static class PatchAccountRequest {
        private Boolean enabled;
        private String role;
        private String password;
    }
}
