package com.collection.engine.outbox;

import com.collection.common.enums.OutboxStatus;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.OutboxEvent;
import com.collection.common.repository.EventOutboxRepository;
import com.collection.common.util.JsonUtil;
import com.collection.engine.config.EngineProperties;
import java.time.LocalDateTime;
import javax.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 派生事件入箱。对应核心引擎规格 §7.4。
 *
 * <p>状态迁移提交后才发布事件，这一点本身是对的（消费者不能读到未提交状态）。 问题在于发布失败之后：原事件重投时步骤已是终态， {@code prepareStepDue} / {@code
 * recordTerminal} 一律按幂等 no-op 返回，派生事件不会被重新推导，计划就此静默停摆。
 *
 * <p>{@link #enqueue} 把"该事件已产生"和状态迁移写在同一事务，于是发布失败只是延迟而非丢失： {@link OutboxPublisher}
 * 会在宽限期后重发。即时发布成功则调用 {@link #markDelivered} 销账，轮询器不会重复投递。
 */
@Component
public class OutboxEventSink {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventSink.class);

    /** 未装配持久层的上下文（纯逻辑单测、无 collection-service 的模块）退化为"提交后发布"。 */
    @Autowired(required = false)
    private EventOutboxRepository repository;

    @Resource private EngineProperties props;

    /** 必须在产生该事件的业务事务内调用。仓储实现声明为 {@code Propagation.MANDATORY}， 脱离事务调用会立即失败而不是悄悄退化成"提交后发布"。 */
    public void enqueue(CollectionEvent event) {
        if (!active() || event == null) {
            return;
        }
        OutboxEvent row = new OutboxEvent();
        row.setEventId(event.getEventId());
        row.setEventType(event.getEventType().name());
        row.setPlanId(event.getLong(CollectionEvent.PLAN_ID));
        row.setCaseId(event.getLong(CollectionEvent.CASE_ID));
        row.setPayload(JsonUtil.toJson(event));
        row.setStatus(OutboxStatus.PENDING);
        row.setNextRetryAt(LocalDateTime.now().plusSeconds(props.getOutbox().getGraceSeconds()));
        repository.enqueue(row);
    }

    /** 即时发布成功后的销账。失败只影响一次多余重发，由消费侧幂等吸收，因此不上抛。 */
    public void markDelivered(CollectionEvent event) {
        if (!active() || event == null) {
            return;
        }
        try {
            repository.markPublished(event.getEventId());
        } catch (Exception e) {
            log.warn(
                    "[outbox] mark delivered failed, will be republished: eventId={} type={} err={}",
                    event.getEventId(),
                    event.getEventType(),
                    e.toString());
        }
    }

    /** 无仓储实现（纯逻辑单测）或显式关闭时整体退化为"提交后发布"。 */
    private boolean active() {
        return repository != null && props != null && props.getOutbox().isEnabled();
    }
}
