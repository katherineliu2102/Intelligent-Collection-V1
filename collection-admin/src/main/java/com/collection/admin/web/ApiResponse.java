package com.collection.admin.web;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 管理后台通用响应 envelope。 */
public final class ApiResponse {

    private ApiResponse() {}

    public static Map<String, Object> success(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", OffsetDateTime.now().toString());
        return body;
    }

    public static Map<String, Object> failure(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", OffsetDateTime.now().toString());
        return body;
    }
}

