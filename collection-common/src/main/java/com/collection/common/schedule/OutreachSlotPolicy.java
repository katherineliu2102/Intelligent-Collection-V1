package com.collection.common.schedule;

import com.collection.common.model.ContactPlanStep;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 过日槽不作废补打；当天只宽限到下一产品槽之前。产品槽 PHT：08:00、09:15、11:30、12:00、14:00、14:30、16:15、18:40；末日截止 21:00。
 */
public final class OutreachSlotPolicy {

    private static final LocalTime[] PRODUCT_SLOTS = {
        LocalTime.of(8, 0),
        LocalTime.of(9, 15),
        LocalTime.of(11, 30),
        LocalTime.of(12, 0),
        LocalTime.of(14, 0),
        LocalTime.of(14, 30),
        LocalTime.of(16, 15),
        LocalTime.of(18, 40)
    };

    private static final LocalTime DAY_END = LocalTime.of(21, 0);

    public enum Decision {
        FIRE,
        SKIP_MISSED_DAY,
        SKIP_PAST_NEXT_SLOT,
        WAIT_OWN_SLOT
    }

    private OutreachSlotPolicy() {}

    public static LocalDateTime anchorTime(ContactPlanStep step) {
        if (step == null) {
            return null;
        }
        if (step.getOriginalTriggerTime() != null) {
            return step.getOriginalTriggerTime();
        }
        return step.getTriggerTime();
    }

    public static Decision decide(LocalDateTime originalTrigger, LocalDateTime now) {
        if (originalTrigger == null || now == null) {
            return Decision.FIRE;
        }
        LocalDate slotDay = originalTrigger.toLocalDate();
        LocalDate today = now.toLocalDate();
        if (slotDay.isBefore(today)) {
            return Decision.SKIP_MISSED_DAY;
        }
        if (slotDay.isAfter(today)) {
            return Decision.WAIT_OWN_SLOT;
        }
        LocalTime slot = originalTrigger.toLocalTime();
        if (!now.isBefore(LocalDateTime.of(today, nextProductSlot(slot)))) {
            return Decision.SKIP_PAST_NEXT_SLOT;
        }
        if (now.isBefore(LocalDateTime.of(today, slot))) {
            return Decision.WAIT_OWN_SLOT;
        }
        return Decision.FIRE;
    }

    public static LocalTime nextProductSlot(LocalTime slot) {
        for (LocalTime candidate : PRODUCT_SLOTS) {
            if (candidate.isAfter(slot)) {
                return candidate;
            }
        }
        return DAY_END;
    }
}
