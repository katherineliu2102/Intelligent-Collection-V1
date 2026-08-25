package com.collection.channel.strategy;

import com.collection.common.enums.ChannelType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Phase 1 SMS/Push scriptSlot 全集 —— {@link DefaultStepResolver#deriveMsgScriptSlot} 的值域。
 *
 * <p>对应 [渠道模板清单与配置 §7]：SMS 10 槽 + Push 7 槽。S0 三档（{@code S0_REMINDER} / {@code S0_REMINDER_URGENT} /
 * {@code S0_DUE_TODAY}）不带渠道后缀，SMS 与 Push 共用槽名但内容分别配置。
 *
 * <p>Resolver 对缺槽是 fail-close（跳过不发），因此漏配只会在客户端表现为「没收到」；本清单用于 Pilot 启动闸门把漏配提前暴露成启动失败。
 */
public final class MsgScriptSlots {

    private static final List<String> S0_SLOTS =
            Arrays.asList("S0_REMINDER", "S0_REMINDER_URGENT", "S0_DUE_TODAY");

    private static final List<String> SMS_SLOTS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "S0_REMINDER",
                            "S0_REMINDER_URGENT",
                            "S0_DUE_TODAY",
                            "S1_SMS_STANDARD",
                            "S2_SMS_STANDARD",
                            "S2_SMS_FIRM",
                            "S3_SMS_STANDARD",
                            "S3_SMS_FIRM",
                            "S4_SMS_STANDARD",
                            "S4_SMS_FIRM"));

    private static final List<String> PUSH_SLOTS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "S0_REMINDER",
                            "S0_REMINDER_URGENT",
                            "S0_DUE_TODAY",
                            "S1_PUSH_STANDARD",
                            "S2_PUSH_STANDARD",
                            "S3_PUSH_STANDARD",
                            "S4_PUSH_STANDARD"));

    private MsgScriptSlots() {}

    public static List<String> smsSlots() {
        return SMS_SLOTS;
    }

    public static List<String> pushSlots() {
        return PUSH_SLOTS;
    }

    public static List<String> slotsOf(ChannelType channel) {
        return channel == ChannelType.PUSH ? PUSH_SLOTS : SMS_SLOTS;
    }

    /** S0 的三档槽位由 SMS 与 Push 共用，告警文案需注明渠道以免误判为重复。 */
    public static boolean isSharedS0Slot(String scriptSlot) {
        return S0_SLOTS.contains(scriptSlot);
    }
}
