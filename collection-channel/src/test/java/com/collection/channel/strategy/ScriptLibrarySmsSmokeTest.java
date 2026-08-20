package com.collection.channel.strategy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.model.CaseContext;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SMS 还款短链 Mock 冒烟：验证 {repaymentUrl} 注入到全阶段文案。 */
class ScriptLibrarySmsSmokeTest {

    private static final String SHORT_LINK = "https://mocasa.com/s/4cTu";

    private ScriptLibrary scriptLibrary;

    @BeforeEach
    void setUp() {
        ChannelProperties props = new ChannelProperties();
        props.getScripts().setSmsDefaultRepaymentLink(SHORT_LINK);
        props.getScripts()
                .getSms()
                .put(
                        "S1_SMS_STANDARD",
                        "MOCASA Collections: {name}, your account is {dpd} day(s) overdue. Please settle PHP {amount} promptly. Pay: {repaymentUrl}");

        scriptLibrary = new ScriptLibrary();
        try {
            Field field = ScriptLibrary.class.getDeclaredField("channelProperties");
            field.setAccessible(true);
            field.set(scriptLibrary, props);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void renderSms_includesAppRepaymentShortLink() {
        ContextSnapshot snapshot = sampleSnapshot();
        ScriptVars vars = scriptLibrary.buildVars(snapshot);
        String body = scriptLibrary.renderSms("S1_SMS_STANDARD", vars);

        assertTrue(body.contains(SHORT_LINK), "SMS body must contain repayment short link");
        assertTrue(body.contains("Maria"), "SMS body must contain borrower name");
        assertTrue(body.contains("3 day(s) overdue"), "SMS body must contain dpd");
    }

    @Test
    void s0UsesUpcomingAmountAndDoesNotFallBackToOverdue() {
        ChannelProperties props = new ChannelProperties();
        props.getScripts()
                .getSms()
                .put(
                        "S0_REMINDER",
                        "MOCASA: {name}, your PHP {amount} payment is due soon. Pay: {repaymentUrl}");
        try {
            Field field = ScriptLibrary.class.getDeclaredField("channelProperties");
            field.setAccessible(true);
            field.set(scriptLibrary, props);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        ContextSnapshot snapshot = sampleSnapshot();
        snapshot.getCaseContext().setStage(com.collection.common.enums.Stage.S0);
        snapshot.getCaseContext().setDpd(-3);
        snapshot.getCaseContext().setUpcomingAmount(new BigDecimal("1000.00"));
        snapshot.getCaseContext().setTotalOutstanding(new BigDecimal("28000.00"));

        ScriptVars vars = scriptLibrary.buildVars(snapshot);
        String body = scriptLibrary.renderSms("S0_REMINDER", vars);
        assertTrue(body.contains("1,000.00"), "S0 must use upcomingAmount");
        assertTrue(!body.contains("28,000.00"), "S0 must not fall back to overdue total");
    }

    @Test
    void s0MissingUpcomingAmountDoesNotFallBackToOverdue() {
        ChannelProperties props = new ChannelProperties();
        props.getScripts()
                .getSms()
                .put(
                        "S0_REMINDER",
                        "MOCASA: {name}, your PHP {amount} payment is due soon. Pay: {repaymentUrl}");
        try {
            Field field = ScriptLibrary.class.getDeclaredField("channelProperties");
            field.setAccessible(true);
            field.set(scriptLibrary, props);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        ContextSnapshot snapshot = sampleSnapshot();
        snapshot.getCaseContext().setStage(com.collection.common.enums.Stage.S0);
        snapshot.getCaseContext().setDpd(-2);
        snapshot.getCaseContext().setUpcomingAmount(null);
        snapshot.getCaseContext().setTotalOutstanding(new BigDecimal("28000.00"));

        ScriptVars vars = scriptLibrary.buildVars(snapshot);
        String body = scriptLibrary.renderSms("S0_REMINDER", vars);
        assertTrue(!body.contains("28,000.00"), "S0 must not fall back to overdue total");
        assertTrue(body.contains("PHP payment") || body.contains("PHP  payment"));
    }

    private static ContextSnapshot sampleSnapshot() {
        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setName("Maria");
        basic.setPrimaryPhone("+639451373897");

        UserProfile profile = new UserProfile();
        profile.setBasic(basic);

        CaseContext ctx = new CaseContext();
        ctx.setCaseId(94102L);
        ctx.setDpd(3);
        ctx.setTotalOutstanding(new BigDecimal("12500.00"));
        ctx.setRepaymentUrl(SHORT_LINK);

        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setUserProfile(profile);
        snapshot.setCaseContext(ctx);
        return snapshot;
    }
}
