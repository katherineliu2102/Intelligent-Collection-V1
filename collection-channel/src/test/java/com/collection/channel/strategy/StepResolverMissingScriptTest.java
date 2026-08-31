package com.collection.channel.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.StepCommand;
import com.collection.common.enums.ChannelType;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseContext;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 缺文案时 SMS/Push 必须跳过而非发占位串。
 *
 * <p>回归的是一个会直接打到客户手机上的缺陷：漏配槽位时旧实现发出的正文是 {@code [MOCK] S0_DUE_TODAY
 * https://app.mocasa.test/repay/529225}。
 */
class StepResolverMissingScriptTest {

    private ChannelProperties properties;
    private DefaultStepResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new ChannelProperties();
        properties.getScripts().setSmsDefaultRepaymentLink("https://mocasa.com/s/4cTu");

        ScriptLibrary scriptLibrary = new ScriptLibrary();
        ReflectionTestUtils.setField(scriptLibrary, "channelProperties", properties);

        resolver = new DefaultStepResolver();
        ReflectionTestUtils.setField(resolver, "channelProperties", properties);
        ReflectionTestUtils.setField(resolver, "scriptLibrary", scriptLibrary);
    }

    private void putSms(String slot) {
        properties.getScripts().getSms().put(slot, "MOCASA: {name}, pay PHP {amount}.");
    }

    private void putPush(String slot) {
        ChannelProperties.PushScript push = new ChannelProperties.PushScript();
        push.setTitle("Due today");
        push.setBody("{name}, PHP {amount} is due.");
        properties.getScripts().getPush().put(slot, push);
    }

    private ExecutionContext context(ChannelType channel) {
        CaseContext caseContext = new CaseContext();
        caseContext.setCaseId(529225L);
        caseContext.setStage(Stage.S0);
        caseContext.setDpd(0);
        caseContext.setStrategyTone("STANDARD");

        UserProfile profile = new UserProfile();
        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setName("Juan");
        basic.setPrimaryPhone("+639171234567");
        profile.setBasic(basic);

        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(caseContext);
        snapshot.setUserProfile(profile);

        ContactPlan plan = new ContactPlan();
        plan.setId(1L);
        plan.setCaseId(529225L);
        plan.setStage(Stage.S0);

        ContactPlanStep step = new ContactPlanStep();
        step.setId(10L);
        step.setStepOrder(1);
        step.setChannelType(channel);
        step.setTemplateId(106L);

        return ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(snapshot)
                .build();
    }

    @Test
    void smsWithoutConfiguredSlotIsSkipped() {
        assertThat(resolver.resolve(context(ChannelType.SMS))).isNull();
    }

    @Test
    void pushWithoutConfiguredSlotIsSkipped() {
        assertThat(resolver.resolve(context(ChannelType.PUSH))).isNull();
    }

    /** Push 无 token 时会同槽改发 SMS，故缺 SMS 文案的 Push 步骤同样不能放行。 */
    @Test
    void pushIsSkippedWhenOnlyItsSmsFallbackIsMissing() {
        putPush("S0_DUE_TODAY");

        assertThat(resolver.resolve(context(ChannelType.PUSH))).isNull();
    }

    @Test
    void configuredSlotsRenderRealContent() {
        putSms("S0_DUE_TODAY");
        putPush("S0_DUE_TODAY");

        StepCommand sms = resolver.resolve(context(ChannelType.SMS));
        StepCommand push = resolver.resolve(context(ChannelType.PUSH));

        assertThat(sms).isNotNull();
        assertThat((String) sms.getMetadata().get(StepCommand.META_SMS_BODY))
                .doesNotContain("[MOCK]")
                .contains("Juan");
        assertThat(push).isNotNull();
        assertThat((String) push.getMetadata().get(StepCommand.META_BODY)).doesNotContain("[MOCK]");
        assertThat((String) push.getMetadata().get(StepCommand.META_FALLBACK_SMS_BODY))
                .doesNotContain("[MOCK]");
    }

    @Test
    void s0AiCallUsesUpcomingAmountNotZeroOverdue() {
        CaseContext caseContext = new CaseContext();
        caseContext.setCaseId(519673L);
        caseContext.setStage(Stage.S0);
        caseContext.setDpd(-1);
        caseContext.setOverdueAmount(java.math.BigDecimal.ZERO);
        caseContext.setTotalOutstanding(java.math.BigDecimal.ZERO);
        caseContext.setUpcomingAmount(new java.math.BigDecimal("1800"));
        caseContext.setNextDueDate(java.time.LocalDate.of(2026, 9, 1));

        UserProfile profile = new UserProfile();
        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setName("Ana");
        basic.setPrimaryPhone("+639171234567");
        profile.setBasic(basic);

        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(caseContext);
        snapshot.setUserProfile(profile);

        ContactPlan plan = new ContactPlan();
        plan.setId(1L);
        plan.setCaseId(519673L);
        plan.setStage(Stage.S0);

        ContactPlanStep step = new ContactPlanStep();
        step.setId(10L);
        step.setStepOrder(1);
        step.setChannelType(ChannelType.AI_CALL);
        step.setTemplateId(1L);

        StepCommand cmd =
                resolver.resolve(
                        ExecutionContext.builder()
                                .plan(plan)
                                .currentStep(step)
                                .contextSnapshot(snapshot)
                                .build());

        assertThat(cmd).isNotNull();
        assertThat(cmd.getMetadata().get("overdue_amount")).isEqualTo("1800");
        assertThat(cmd.getMetadata().get("due_date")).isEqualTo("2026-09-01");
        assertThat(cmd.getMetadata().get("borrower_name")).isEqualTo("Ana");
    }
}
