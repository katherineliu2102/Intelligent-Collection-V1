package com.collection.channel.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseContext;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link MsgScriptSlots} 必须覆盖 {@link DefaultStepResolver#deriveMsgScriptSlot} 的全部值域。
 *
 * <p>两者一旦漂移，Pilot 启动闸门就会漏检某个槽位；而 Resolver 对缺槽是静默跳过， 线上表现只是「部分客户没收到」，很难在观测里发现。
 */
class MsgScriptSlotsCoverageTest {

    /** 覆盖 S0 的三个 dpd 分档，以及 S2+ 的 FIRM/STANDARD 分叉。 */
    private static final int[] DPD_SAMPLES = {-3, -2, -1, 0, 1, 3, 4, 15, 16, 30, 31, 60, 90};

    private static Set<String> derivedSlots(ChannelType channel) {
        Set<String> slots = new LinkedHashSet<>();
        slots.add(DefaultStepResolver.deriveMsgScriptSlot(channel, null));
        for (Stage stage : Stage.values()) {
            for (int dpd : DPD_SAMPLES) {
                for (String tone : new String[] {"STANDARD", "FIRM"}) {
                    CaseContext ctx = new CaseContext();
                    ctx.setStage(stage);
                    ctx.setDpd(dpd);
                    ctx.setStrategyTone(tone);
                    slots.add(DefaultStepResolver.deriveMsgScriptSlot(channel, ctx));
                }
            }
        }
        return slots;
    }

    @Test
    void registryCoversEverySmsSlotTheResolverCanDerive() {
        assertThat(MsgScriptSlots.smsSlots()).containsAll(derivedSlots(ChannelType.SMS));
    }

    @Test
    void registryCoversEveryPushSlotTheResolverCanDerive() {
        assertThat(MsgScriptSlots.pushSlots()).containsAll(derivedSlots(ChannelType.PUSH));
    }

    /** 反向：清单里不应有推导不出来的槽位，否则闸门会拦住一个永远用不到的配置。 */
    @Test
    void registryHasNoUnreachableSlots() {
        assertThat(derivedSlots(ChannelType.SMS)).containsAll(MsgScriptSlots.smsSlots());
        assertThat(derivedSlots(ChannelType.PUSH)).containsAll(MsgScriptSlots.pushSlots());
    }
}
