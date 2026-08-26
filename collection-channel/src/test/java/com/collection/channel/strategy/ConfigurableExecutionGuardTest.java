package com.collection.channel.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.collection.channel.compliance.InMemoryComplianceCounterService;
import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.enums.ChannelType;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.EmailSuppression;
import com.collection.common.model.UserProfile;
import com.collection.common.repository.EmailSuppressionRepository;
import com.collection.common.service.ComplianceCounterService;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConfigurableExecutionGuardTest {

    private ConfigurableExecutionGuard guard;
    private Set<String> suppressedEmails;

    @BeforeEach
    void setUp() {
        ChannelProperties properties = new ChannelProperties();
        // 规避测试执行时正好落在 PHT 静默时段。
        properties.getCompliance().setQuietHoursStart("00:00");
        properties.getCompliance().setQuietHoursEnd("00:00");

        suppressedEmails = new HashSet<>();
        guard = new ConfigurableExecutionGuard();
        ReflectionTestUtils.setField(guard, "channelProperties", properties);
        ReflectionTestUtils.setField(
                guard, "complianceCounterService", new InMemoryComplianceCounterService());
        ReflectionTestUtils.setField(
                guard, "emailSuppressionRepository", suppressionRepository(suppressedEmails));
    }

    private static EmailSuppressionRepository suppressionRepository(Set<String> suppressed) {
        return new EmailSuppressionRepository() {
            @Override
            public void suppress(EmailSuppression suppression) {
                suppressed.add(suppression.getEmail());
            }

            @Override
            public boolean isSuppressed(String email) {
                return suppressed.contains(email);
            }
        };
    }

    @Test
    void blocksEmailOnSuppressionList() {
        suppressedEmails.add("user@example.com");

        GuardVerdict verdict = guard.evaluate(context(ChannelType.EMAIL));

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getBlockedReason()).isEqualTo("EMAIL_SUPPRESSED");
    }

    @Test
    void suppressionDoesNotAffectOtherChannels() {
        suppressedEmails.add("user@example.com");

        assertThat(guard.evaluate(context(ChannelType.SMS)).isAllowed()).isTrue();
    }

    @Test
    void allowsEmailWhenNotSuppressed() {
        assertThat(guard.evaluate(context(ChannelType.EMAIL)).isAllowed()).isTrue();
    }

    @Test
    void failsCloseWhenCounterUnavailable() {
        ReflectionTestUtils.setField(
                guard,
                "complianceCounterService",
                new ComplianceCounterService() {
                    @Override
                    public Counts tryConsume(
                            Long userId,
                            String channel,
                            LocalDate date,
                            int channelLimit,
                            int totalLimit) {
                        throw new IllegalStateException("redis down");
                    }

                    @Override
                    public void release(Long userId, String channel, LocalDate date) {
                        throw new UnsupportedOperationException();
                    }
                });

        assertThatThrownBy(() -> guard.evaluate(context(ChannelType.SMS)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void firstAttemptReportsQuotaReservationForEngineToReleaseOnFailure() {
        GuardVerdict verdict = guard.evaluate(context(ChannelType.SMS));

        assertThat(verdict.isAllowed()).isTrue();
        assertThat(verdict.getQuotaReservation()).isNotNull();
        assertThat(verdict.getQuotaReservation().getUserId()).isEqualTo(1001L);
        assertThat(verdict.getQuotaReservation().getChannel()).isEqualTo("SMS");
    }

    /** F2：日限为 1 时，若重试再占一次配额，退避后的重试必被自己的首次尝试挡掉，且终态被记成合规拦截。 */
    @Test
    void retryReusesFirstAttemptQuotaInsteadOfConsumingAnother() {
        assertThat(guard.evaluate(context(ChannelType.SMS)).isAllowed()).isTrue();

        GuardVerdict retry = guard.evaluate(retryContext(ChannelType.SMS, 1));

        assertThat(retry.isAllowed()).isTrue();
        assertThat(retry.getQuotaReservation()).isNull();
    }

    @Test
    void retryDoesNotBurnQuotaForOtherTouchesOfTheSameDay() {
        assertThat(guard.evaluate(retryContext(ChannelType.SMS, 2)).isAllowed()).isTrue();

        // 重试未计数，当天首次真实触达仍应放行。
        assertThat(guard.evaluate(context(ChannelType.SMS)).isAllowed()).isTrue();
    }

    @Test
    void blocksSecondTouchOnSameChannel() {
        assertThat(guard.evaluate(context(ChannelType.SMS)).isAllowed()).isTrue();

        GuardVerdict verdict = guard.evaluate(context(ChannelType.SMS));

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getBlockedReason()).contains("DAILY_LIMIT_EXCEEDED SMS 2/1");
    }

    @Test
    void blocksFourthTouchAcrossChannels() {
        assertThat(guard.evaluate(context(ChannelType.SMS)).isAllowed()).isTrue();
        assertThat(guard.evaluate(context(ChannelType.PUSH)).isAllowed()).isTrue();
        assertThat(guard.evaluate(context(ChannelType.EMAIL)).isAllowed()).isTrue();

        GuardVerdict verdict = guard.evaluate(context(ChannelType.AI_CALL));

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getBlockedReason()).contains("DAILY_TOTAL_LIMIT_EXCEEDED 4/3");
    }

    private static ExecutionContext context(ChannelType channel) {
        return retryContext(channel, 0);
    }

    private static ExecutionContext retryContext(ChannelType channel, int retryCount) {
        ContactPlan plan = new ContactPlan();
        plan.setUserId(1001L);

        ContactPlanStep step = new ContactPlanStep();
        step.setChannelType(channel);
        step.setRetryCount(retryCount);

        UserProfile.BasicInfo basic = new UserProfile.BasicInfo();
        basic.setPrimaryPhone("+639171234567");
        basic.setEmail("user@example.com");
        UserProfile.DeviceInfo device = new UserProfile.DeviceInfo();
        device.setJpushToken("token");
        UserProfile profile = new UserProfile();
        profile.setBasic(basic);
        profile.setDevice(device);
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setUserProfile(profile);

        return ExecutionContext.builder()
                .plan(plan)
                .currentStep(step)
                .contextSnapshot(snapshot)
                .build();
    }
}
