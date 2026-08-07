package com.collection.admin.config;

import com.collection.admin.job.ScheduledJob;
import com.collection.admin.job.TriggerScanner;
import javax.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 调度入口启动闸门（基础设施规范 §5.1）。启用调度但配置缺失、阈值失当或存在第二个调度入口时，禁止进程就绪。
 *
 * <p>三条不变量：
 *
 * <ul>
 *   <li><b>配置完整</b>：Pilot / 生产启用调度却缺 projectId / subscription 时必须启动失败，而不是静默不消费。
 *   <li><b>阈值 ≤ 任务周期</b>：陈旧阈值大于周期意味着上一轮的 tick 也会被当作新鲜放行，重启时会连跑多轮扫描， 使陈旧消息防抖形同虚设。
 *   <li><b>调度入口唯一</b>：{@link TriggerScanner}（Spring {@code @Scheduled}，仅 {@code local}/{@code
 *       test}）与 Pub/Sub 调度订阅不得同时生效，否则同一扫描会被两个入口重复驱动。
 * </ul>
 */
@Component
public class SchedulerEntrypointValidator {

    private final SchedulerProperties props;
    private final ObjectProvider<TriggerScanner> triggerScanner;

    public SchedulerEntrypointValidator(
            SchedulerProperties props, ObjectProvider<TriggerScanner> triggerScanner) {
        this.props = props;
        this.triggerScanner = triggerScanner;
    }

    @PostConstruct
    public void validate() {
        if (!props.isEnabled()) {
            // 本地 / CI：TriggerScanner 是唯一入口，无需校验 GCP 配置。
            return;
        }
        require(
                StringUtils.isNotBlank(props.getProjectId()),
                "collection.scheduler.enabled=true 时必须配置 collection.scheduler.project-id"
                        + "（映射 GCP_PUBSUB_PROJECT）");
        require(
                StringUtils.isNotBlank(props.getSubscription()),
                "collection.scheduler.enabled=true 时必须配置 collection.scheduler.subscription"
                        + "（映射 GCP_SCHEDULER_SUBSCRIPTION；不得复用案件接入订阅）");
        for (ScheduledJob job : ScheduledJob.values()) {
            int threshold = props.staleThresholdSeconds(job);
            require(
                    threshold > 0 && threshold <= job.getPeriodSeconds(),
                    "调度任务 "
                            + job.attribute()
                            + " 的陈旧消息阈值必须落在 (0, "
                            + job.getPeriodSeconds()
                            + "] 秒内，当前为 "
                            + threshold
                            + " 秒；超过任务周期会让上一轮 tick 被误判为新鲜");
        }
        require(
                triggerScanner.getIfAvailable() == null,
                "生产调度入口必须唯一：collection.scheduler.enabled=true 时不得同时激活 TriggerScanner"
                        + "（@Scheduled，仅限 local/test profile）");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
