package com.collection.common.enums;

<<<<<<< HEAD
/** 渠道类型。对应领域模型 §6.1。 */
=======
/**
 * 渠道类型。对应领域模型 §2.1。
 */
>>>>>>> origin/ca_branch
public enum ChannelType {
    PUSH("App 推送", ChannelMode.AUTOMATED),
    SMS("短信", ChannelMode.AUTOMATED),
    AI_CALL("AI 机器人外呼", ChannelMode.AUTOMATED),
    TTS("TTS 语音通知（LTH 域外，Phase 1 不生成 plan step）", ChannelMode.AUTOMATED),
    EMAIL("邮件", ChannelMode.AUTOMATED),
    VIBER("Viber 消息", ChannelMode.AUTOMATED),
    WHATSAPP("WhatsApp 消息", ChannelMode.AUTOMATED),
    HUMAN_CALL("人工外呼", ChannelMode.AGENT_ASSISTED);

    private final String displayName;
    private final ChannelMode mode;

    ChannelType(String displayName, ChannelMode mode) {
        this.displayName = displayName;
        this.mode = mode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ChannelMode getMode() {
        return mode;
    }

    /** 消息类渠道：同步返回、有/无观察期。 */
    public boolean isMessageChannel() {
        return this == SMS || this == PUSH || this == EMAIL || this == VIBER || this == WHATSAPP;
    }

    /** 电话/人工类渠道：发送后保持 STEP_EXECUTING，等待异步回调。TTS 由 LTH 独立编排，不在此列。 */
    public boolean isAsyncChannel() {
        return this == AI_CALL || this == HUMAN_CALL;
    }
}
