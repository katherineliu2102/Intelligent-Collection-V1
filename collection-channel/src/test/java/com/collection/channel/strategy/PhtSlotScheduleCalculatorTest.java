package com.collection.channel.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.CaseContext;
import com.collection.common.model.ContextSnapshot;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PhtSlotScheduleCalculatorTest {

    private final PhtSlotScheduleCalculator calculator = new PhtSlotScheduleCalculator();

    @Test
    void skipsPastSlotsAndKeepsCurrentAndFutureDpdDays() {
        ChannelProperties.Slot sms0800 = slot("SMS", "08:00");
        ChannelProperties.Slot push1200 = slot("PUSH", "12:00");
        ChannelProperties.DayBlock d1 = block(1, sms0800, push1200);
        ChannelProperties.DayBlock d2 = block(2, slot("SMS", "08:00"));
        ContextSnapshot snapshot = snapshot(1, LocalDate.of(2026, 8, 6));

        List<PhtSlotScheduleCalculator.ScheduledSlot> slots =
                calculator.futureSlots(
                        snapshot,
                        Arrays.asList(d1, d2),
                        LocalDateTime.of(2026, 8, 7, 11, 0));

        assertThat(slots).extracting(PhtSlotScheduleCalculator.ScheduledSlot::getTriggerTime)
                .containsExactly(
                        LocalDateTime.of(2026, 8, 7, 12, 0),
                        LocalDateTime.of(2026, 8, 8, 8, 0));
    }

    @Test
    void skipsExpiredDpdDaysWithoutRetroactiveSend() {
        ContextSnapshot snapshot = snapshot(2, LocalDate.of(2026, 8, 6));

        List<PhtSlotScheduleCalculator.ScheduledSlot> slots =
                calculator.futureSlots(
                        snapshot,
                        Arrays.asList(block(1, slot("SMS", "08:00"))),
                        LocalDateTime.of(2026, 8, 8, 9, 0));

        assertThat(slots).isEmpty();
    }

    @Test
    void absoluteTemplateNeverFallsBackToRelativeStepsAfterAllSlotsExpired() {
        ChannelProperties properties = new ChannelProperties();
        ChannelProperties.PlanStepDef fallback = new ChannelProperties.PlanStepDef();
        fallback.setChannel("SMS");
        fallback.setDelayMin(0);
        ChannelProperties.PlanTemplate template = new ChannelProperties.PlanTemplate();
        template.setSteps(Arrays.asList(fallback));
        template.setDayBlocks(Arrays.asList(block(0, slot("SMS", "08:00"))));
        properties.getPlanTemplates().put("S1", template);

        DefaultPlanFactory factory = new DefaultPlanFactory();
        ReflectionTestUtils.setField(factory, "channelProperties", properties);
        ReflectionTestUtils.setField(factory, "slotScheduleCalculator", calculator);
        CaseInfo caseInfo = new CaseInfo();
        caseInfo.setCaseId(11L);
        caseInfo.setUserId(22L);
        caseInfo.setCaseStatus("OVERDUE");

        ContactPlan plan =
                factory.create(
                        caseInfo,
                        Stage.S1,
                        snapshot(0, LocalDate.now(PhtSlotScheduleCalculator.PHT).minusDays(1)));

        assertThat(plan).isNull();
    }

    private static ContextSnapshot snapshot(int dpd, LocalDate dueDate) {
        CaseContext context = new CaseContext();
        context.setDpd(dpd);
        context.setDueDate(dueDate);
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setCaseContext(context);
        return snapshot;
    }

    private static ChannelProperties.DayBlock block(
            int dpdDay, ChannelProperties.Slot... slots) {
        ChannelProperties.DayBlock block = new ChannelProperties.DayBlock();
        block.setDpdDay(dpdDay);
        block.setSlots(Arrays.asList(slots));
        return block;
    }

    private static ChannelProperties.Slot slot(String channel, String time) {
        ChannelProperties.Slot slot = new ChannelProperties.Slot();
        slot.setChannel(channel);
        slot.setTime(time);
        slot.setTemplateId(1L);
        return slot;
    }
}
