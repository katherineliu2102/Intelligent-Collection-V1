package com.collection.channel.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.collection.common.enums.ChannelType;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseContext;
import org.junit.jupiter.api.Test;

/** DefaultStepResolver / ScriptLibrary 纯逻辑单测（变量注入、name 兜底清理、scriptSlot 推导）。 */
class ScriptResolverLogicTest {

    private static CaseContext ctx(Stage stage, int dpd, String tone) {
        CaseContext c = new CaseContext();
        c.setStage(stage);
        c.setDpd(dpd);
        c.setStrategyTone(tone);
        return c;
    }

    @Test
    void inject_repaymentUrl_appendsShortLink() {
        String tpl =
                "MOCASA Collections: {name}, please settle PHP {amount}. Pay: {repaymentUrl}";
        String out =
                ScriptLibrary.inject(
                        tpl, new ScriptVars("Juan", "1,500.00", 3, "https://mocasa.com/s/4cTu"));
        assertEquals(
                "MOCASA Collections: Juan, please settle PHP 1,500.00. Pay: https://mocasa.com/s/4cTu",
                out);
    }

    @Test
    void inject_replacesAllPlaceholders() {
        String tpl =
                "MOCASA Collections: {name}, your account is {dpd} day(s) overdue. Please settle PHP {amount} promptly.";
        String out = ScriptLibrary.inject(tpl, new ScriptVars("Juan", "1,500.00", 3));
        assertEquals(
                "MOCASA Collections: Juan, your account is 3 day(s) overdue. Please settle PHP 1,500.00 promptly.",
                out);
    }

    @Test
    void inject_emptyName_cleansPunctuation() {
        String tpl = "MOCASA Collections: {name}, your account is {dpd} days overdue.";
        String out = ScriptLibrary.inject(tpl, new ScriptVars(null, "200.00", 5));
        assertEquals("MOCASA Collections: your account is 5 days overdue.", out);
    }

    @Test
    void inject_emptyName_stripsLeadingComma() {
        String tpl = "{name}, PHP {amount} is due soon. Tap to pay.";
        String out = ScriptLibrary.inject(tpl, new ScriptVars("", "99.00", -3));
        assertEquals("PHP 99.00 is due soon. Tap to pay.", out);
    }

    @Test
    void deriveSlot_s0_byDpd() {
        assertEquals(
                "S0_REMINDER",
                DefaultStepResolver.deriveMsgScriptSlot(ChannelType.SMS, ctx(Stage.S0, -3, null)));
        assertEquals(
                "S0_REMINDER_URGENT",
                DefaultStepResolver.deriveMsgScriptSlot(ChannelType.SMS, ctx(Stage.S0, -1, null)));
        assertEquals(
                "S0_DUE_TODAY",
                DefaultStepResolver.deriveMsgScriptSlot(ChannelType.SMS, ctx(Stage.S0, 0, null)));
        // S0 槽 SMS / PUSH 共用
        assertEquals(
                "S0_DUE_TODAY",
                DefaultStepResolver.deriveMsgScriptSlot(ChannelType.PUSH, ctx(Stage.S0, 0, null)));
    }

    @Test
    void deriveSlot_firmOnlyForSmsAtS2Plus() {
        assertEquals(
                "S2_SMS_FIRM",
                DefaultStepResolver.deriveMsgScriptSlot(ChannelType.SMS, ctx(Stage.S2, 6, "FIRM")));
        assertEquals(
                "S2_SMS_STANDARD",
                DefaultStepResolver.deriveMsgScriptSlot(
                        ChannelType.SMS, ctx(Stage.S2, 6, "STANDARD")));
        // Push 无 FIRM 槽，恒为 STANDARD
        assertEquals(
                "S2_PUSH_STANDARD",
                DefaultStepResolver.deriveMsgScriptSlot(
                        ChannelType.PUSH, ctx(Stage.S2, 6, "FIRM")));
        // dpd=35 → S4（边界对齐后 S4_PLUS 已合并入 S4）
        assertEquals(
                "S4_SMS_FIRM",
                DefaultStepResolver.deriveMsgScriptSlot(
                        ChannelType.SMS, ctx(Stage.S4, 35, "FIRM")));
    }

    @Test
    void deriveSlot_nullStage_fallsBackToS1() {
        assertEquals(
                "S1_SMS_STANDARD", DefaultStepResolver.deriveMsgScriptSlot(ChannelType.SMS, null));
        assertTrue(
                DefaultStepResolver.deriveMsgScriptSlot(ChannelType.PUSH, ctx(null, 0, null))
                        .startsWith("S1_PUSH"));
    }
}
