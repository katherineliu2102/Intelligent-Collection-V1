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
