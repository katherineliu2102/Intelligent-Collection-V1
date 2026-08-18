package com.collection.admin.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.collection.admin.job.PlanStepTriggerPublisher;
import com.collection.admin.job.TriggerScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** 调度入口启动闸门：配置完整性、阈值不变量与「生产只有一个调度入口」防回归。 */
class SchedulerEntrypointValidatorTest {

    private SchedulerProperties props;
    private DefaultListableBeanFactory beanFactory;

    @BeforeEach
    void setUp() {
        props = new SchedulerProperties();
        props.setEnabled(true);
        props.setProjectId("test-project");
        props.setSubscription("collection-schedule-test-sub");
        beanFactory = new DefaultListableBeanFactory();
    }

    @Test
    void passesWithCompletePilotConfiguration() {
        assertThatCode(() -> validator().validate()).doesNotThrowAnyException();
    }

    /** 本地 / CI 关闭调度：不校验 GCP 配置，也允许 TriggerScanner 存在。 */
    @Test
    void skipsAllChecksWhenSchedulingDisabled() {
        props.setEnabled(false);
        props.setProjectId(null);
        props.setSubscription(null);
        registerTriggerScanner();

        assertThatCode(() -> validator().validate()).doesNotThrowAnyException();
    }

    @Test
    void failsWhenSubscriptionMissing() {
        props.setSubscription("");

        assertThatThrownBy(() -> validator().validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collection.scheduler.subscription");
    }

    @Test
    void failsWhenProjectIdMissing() {
        props.setProjectId(null);

        assertThatThrownBy(() -> validator().validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collection.scheduler.project-id");
    }

    /** 阈值大于任务周期会让上一轮 tick 也被判为新鲜，等于取消了陈旧消息防抖。 */
    @Test
    void failsWhenPerMinuteThresholdExceedsItsPeriod() {
        props.setStaleThresholdSeconds(120);

        assertThatThrownBy(() -> validator().validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planStepDue");
    }

    @Test
    void failsWhenDailyRollThresholdExceedsItsPeriod() {
        props.setDailyRollStaleThresholdSeconds(600);

        assertThatThrownBy(() -> validator().validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dailyRoll");
    }

    @Test
    void failsWhenThresholdIsNotPositive() {
        props.setStaleThresholdSeconds(0);

        assertThatThrownBy(() -> validator().validate()).isInstanceOf(IllegalStateException.class);
    }

    /** 防回归：生产 profile 下不得同时激活 @Scheduled 扫描器与 Pub/Sub 调度订阅。 */
    @Test
    void failsWhenScheduledTriggerScannerIsAlsoActive() {
        registerTriggerScanner();

        assertThatThrownBy(() -> validator().validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TriggerScanner");
    }

    private void registerTriggerScanner() {
        beanFactory.registerSingleton(
                "triggerScanner", new TriggerScanner(mock(PlanStepTriggerPublisher.class)));
    }

    private SchedulerEntrypointValidator validator() {
        return new SchedulerEntrypointValidator(
                props, beanFactory.getBeanProvider(TriggerScanner.class));
    }
}
