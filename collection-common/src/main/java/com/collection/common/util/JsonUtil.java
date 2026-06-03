package com.collection.common.util;

import com.alibaba.fastjson.JSON;

/**
 * 统一 JSON 序列化工具（fastjson）。用于 context_snapshot、决策日志、事件 payload 等。
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    public static String toJson(Object obj) {
        return obj == null ? null : JSON.toJSONString(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return json == null ? null : JSON.parseObject(json, clazz);
    }
}
