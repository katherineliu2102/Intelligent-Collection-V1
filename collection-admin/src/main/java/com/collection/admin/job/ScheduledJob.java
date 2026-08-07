package com.collection.admin.job;

/**
 * 生产调度的三个定时任务（基础设施规范 §5）。外部 Cloud Scheduler 按 {@link #attribute()} 作为调度消息的 {@code job} 属性发布， {@link
 * PubSubScheduleConsumer} 据此路由到 {@link ScheduledJobRunner}。
 *
 * <p>{@link #getPeriodSeconds()} 是该任务在 Cloud Scheduler 上的触发周期，同时是陈旧消息阈值的上界： 阈值大于周期意味着上一轮的 tick
 * 也会被当作"新鲜"放行，启动时会一次性跑多轮扫描。该不变量由 {@link com.collection.admin.config.SchedulerEntrypointValidator}
 * 在启动时强制。
 */
public enum ScheduledJob {

    /** 步骤到期扫描，每分钟一次 → {@code PLAN_STEP_DUE}。 */
    PLAN_STEP_DUE("planStepDue", 60),

    /** 回调超时哨兵，每分钟一次 → {@code CALLBACK_TIMEOUT}；Phase 1 仅服务 AI_CALL。 */
    CALLBACK_TIMEOUT("callbackTimeout", 60),

    /** DPD 日切，00:35–02:55 PHT 每 5 分钟一次 → {@code STAGE_CHANGED} / {@code CASE_CEASED}。 */
    DAILY_ROLL("dailyRoll", 300);

    private final String attribute;
    private final int periodSeconds;

    ScheduledJob(String attribute, int periodSeconds) {
        this.attribute = attribute;
        this.periodSeconds = periodSeconds;
    }

    public String attribute() {
        return attribute;
    }

    public int getPeriodSeconds() {
        return periodSeconds;
    }

    /** 未知或缺失取值返回 {@code null}——调用方须记录并 ack，不得重投。 */
    public static ScheduledJob fromAttribute(String attribute) {
        for (ScheduledJob job : values()) {
            if (job.attribute.equals(attribute)) {
                return job;
            }
        }
        return null;
    }
}
