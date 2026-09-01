package com.collection.channel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 回调地址拼接：路径段必须与应用唯一入站端点 {@code POST /webhook/channel-callback} 一致。
 *
 * <p>此前拼的是 {@code /lth/voice}，该路径从未有 Controller，供应商按下发地址回调只会拿到 404，而这种错误在单测里 不可见——因此固定路径段本身。入口侧的映射由
 * {@code collection-admin} 的 {@code WebhookControllerTest} 守护； 两侧任一改动都必须同时改另一侧。
 */
class ChannelPropertiesCallbackUrlTest {

    @Test
    void appendsChannelCallbackPathToBaseUrl() {
        ChannelProperties properties = new ChannelProperties();

        properties.getCallback().setBaseUrl("https://pilot.example.com/webhook");
        assertEquals(
                "https://pilot.example.com/webhook/channel-callback", properties.callbackUrl());

        properties.getCallback().setBaseUrl("https://pilot.example.com/webhook/");
        assertEquals(
                "https://pilot.example.com/webhook/channel-callback", properties.callbackUrl());
    }

    /** 未配置回调根 URL 时返回空串，由调用方决定回退，不得拼出以 {@code /} 开头的相对地址下发给供应商。 */
    @Test
    void returnsEmptyWhenBaseUrlMissing() {
        ChannelProperties properties = new ChannelProperties();

        assertEquals("", properties.callbackUrl());

        properties.getCallback().setBaseUrl(null);
        assertEquals("", properties.callbackUrl());
    }
}
