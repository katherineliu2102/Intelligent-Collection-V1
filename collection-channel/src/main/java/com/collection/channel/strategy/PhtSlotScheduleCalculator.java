package com.collection.channel.strategy;

import com.collection.channel.config.ChannelProperties;
import com.collection.common.model.CaseContext;
import com.collection.common.model.ContextSnapshot;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 把 DayBlock 固定槽位转换为 PHT 绝对触发时间。 */
@Component
public class PhtSlotScheduleCalculator {

    public static final ZoneId PHT = ZoneId.of("Asia/Manila");
    private static final Logger log = LoggerFactory.getLogger(PhtSlotScheduleCalculator.class);

    /** 只展开当前 DPD 及未来日块；已过日块或已过时段不补发。 返回值按绝对 trigger_time 排序，供 PlanFactory 写成有序步骤。 */
    public List<ScheduledSlot> futureSlots(
            ContextSnapshot snapshot,
            List<ChannelProperties.DayBlock> dayBlocks,
            LocalDateTime planCreateTime) {
        if (snapshot == null
                || snapshot.getCaseContext() == null
                || snapshot.getCaseContext().getDueDate() == null
                || dayBlocks == null
                || dayBlocks.isEmpty()) {
            return new ArrayList<>();
        }
        CaseContext context = snapshot.getCaseContext();
        LocalDate dueDate = context.getDueDate();
        List<ScheduledSlot> result = new ArrayList<>();
        for (ChannelProperties.DayBlock dayBlock : dayBlocks) {
            if (dayBlock == null || dayBlock.getDpdDay() < context.getDpd()) {
                continue;
            }
            if (dayBlock.getSlots() == null) {
                continue;
            }
            LocalDate slotDate = dueDate.plusDays(dayBlock.getDpdDay());
            for (ChannelProperties.Slot slot : dayBlock.getSlots()) {
                LocalTime slotTime = parseTime(slot, dayBlock.getDpdDay());
                if (slotTime == null) {
                    continue;
                }
                LocalDateTime triggerTime = LocalDateTime.of(slotDate, slotTime);
                if (triggerTime.isBefore(planCreateTime)) {
                    continue;
                }
                result.add(new ScheduledSlot(dayBlock.getDpdDay(), slot, triggerTime));
            }
        }
        result.sort(Comparator.comparing(ScheduledSlot::getTriggerTime));
        return result;
    }

    private LocalTime parseTime(ChannelProperties.Slot slot, int dpdDay) {
        if (slot == null || slot.getTime() == null) {
            return null;
        }
        try {
            return LocalTime.parse(slot.getTime());
        } catch (RuntimeException e) {
            log.warn(
                    "[PhtSlotSchedule] ignore invalid slot time={} dpdDay={}",
                    slot.getTime(),
                    dpdDay);
            return null;
        }
    }

    /** 已计算的单个槽位，保留原模板字段供 PlanFactory 生成步骤。 */
    public static final class ScheduledSlot {
        private final int dpdDay;
        private final ChannelProperties.Slot slot;
        private final LocalDateTime triggerTime;

        ScheduledSlot(int dpdDay, ChannelProperties.Slot slot, LocalDateTime triggerTime) {
            this.dpdDay = dpdDay;
            this.slot = slot;
            this.triggerTime = triggerTime;
        }

        public int getDpdDay() {
            return dpdDay;
        }

        public ChannelProperties.Slot getSlot() {
            return slot;
        }

        public LocalDateTime getTriggerTime() {
            return triggerTime;
        }
    }
}
