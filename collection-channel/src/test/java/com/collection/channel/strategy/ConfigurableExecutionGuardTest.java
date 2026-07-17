package com.collection.channel.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.dto.ExecutionContext;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.enums.ChannelType;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContactPlanStep;
import com.collection.common.model.ContextSnapshot;
import com.collection.common.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConfigurableExecutionGuardTest {

    private ConfigurableExecutionGuard guard;

    @BeforeEach
    void setUp() {
        ChannelProperties properties = new ChannelProperties();
        // 规避测试执行时正好落在 PHT 静默时段。
        properties.getCompliance().setQuietHoursStart("00:00");
        properties.getCompliance().setQuietHoursEnd("00:00");

        guard = new ConfigurableExecutionGuard();
        ReflectionTestUtils.setField(guard, "channelProperties", properties);
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
        ContactPlan plan = new ContactPlan();
        plan.setUserId(1001L);

        ContactPlanStep step = new ContactPlanStep();
        step.setChannelType(channel);

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
