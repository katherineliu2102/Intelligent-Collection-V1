package com.collection.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.service.support.ServiceClock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * JVM 默认时区必须是 PHT。
 *
 * <p>全仓仍有大量裸 {@code LocalDateTime.now()}（outbox 重试时间、停摆巡检宽限、观察窗口）取 JVM 默认时区， 而它们比较与写入的库列走的是 {@code
 * +08:00} 会话时区。容器基础镜像默认 UTC，两者一旦不一致就是稳定的 8 小时错位， 且不会报错——只会让 08:00 的触达槽位在 16:00 才执行。
 */
class ApplicationTimeZoneTest {

    @Test
    void phtConstantIsManilaWithFixedPlusEightOffset() {
        assertThat(CollectionApplication.PHT.getID()).isEqualTo("Asia/Manila");
        assertThat(CollectionApplication.PHT.getRawOffset()).isEqualTo(8 * 60 * 60 * 1000);
        assertThat(CollectionApplication.PHT.useDaylightTime()).isFalse();
    }

    /** 设成默认时区后，裸 now() 必须与显式 PHT 的 ServiceClock 同轴。 */
    @Test
    void defaultZoneMakesBareNowAgreeWithServiceClock() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(CollectionApplication.PHT);

            long driftSeconds =
                    Math.abs(
                            Duration.between(ServiceClock.now(), LocalDateTime.now()).getSeconds());

            assertThat(driftSeconds).isLessThan(5);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /** 反向确认这个测试确实能抓到问题：UTC 默认时区下必然出现 8 小时偏差。 */
    @Test
    void utcDefaultZoneWouldDriftEightHours() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            long driftSeconds =
                    Math.abs(
                            Duration.between(ServiceClock.now(), LocalDateTime.now()).getSeconds());

            assertThat(driftSeconds).isCloseTo(8 * 3600, org.assertj.core.data.Offset.offset(5L));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
