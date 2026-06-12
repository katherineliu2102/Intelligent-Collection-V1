package com.collection.channel.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 通知中心统一响应（BaseRespDto）解析结果。
 *
 * <p>SMS 同步返回 {@code data.requestSuccess / channel / requestId}；App Push 异步入队仅返回 {@code code}。
 * 见 Notification 对接说明 §1.3 / §2.4。
 */
public final class NotificationResponse {

    private final Integer code;
    private final String msg;
    private final Boolean requestSuccess;
    private final String requestId;
    private final String channel;

    private NotificationResponse(Integer code, String msg, Boolean requestSuccess,
                                 String requestId, String channel) {
        this.code = code;
        this.msg = msg;
        this.requestSuccess = requestSuccess;
        this.requestId = requestId;
        this.channel = channel;
    }

    public static NotificationResponse parse(String body) {
        if (body == null || body.isEmpty()) {
            return new NotificationResponse(null, "empty body", null, null, null);
        }
        JSONObject json = JSON.parseObject(body);
        Integer code = json.getInteger("code");
        String msg = json.getString("msg");
        Boolean requestSuccess = null;
        String requestId = null;
        String channel = null;
        JSONObject data = json.getJSONObject("data");
        if (data != null) {
            requestSuccess = data.getBoolean("requestSuccess");
            requestId = data.getString("requestId");
            channel = data.getString("channel");
        }
        return new NotificationResponse(code, msg, requestSuccess, requestId, channel);
    }

    /** {@code code == 0} 表示通知中心接口处理完成（不等于用户已送达）。 */
    public boolean isCodeSuccess() {
        return code != null && code == 0;
    }

    /** SMS：仅当 {@code requestSuccess} 显式为 true 才算受理成功；缺省（Push）按 true 处理由调用方决定。 */
    public boolean isRequestSuccess() {
        return Boolean.TRUE.equals(requestSuccess);
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getChannel() {
        return channel;
    }
}
