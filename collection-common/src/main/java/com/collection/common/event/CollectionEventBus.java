package com.collection.common.event;

import com.collection.common.enums.EventType;

/**
 * 事件总线接口。对应基础设施规范 §2、架构设计文档 §1.3.5。
 *
 * <p>接口抽象使未来替换消息中间件（Phase 1 内存版 → Redis Stream → Kafka）时业务代码零改动。
 * Phase 1 提供 InMemoryEventBus（链路验证）与 RedisStreamEventBus（生产）两套实现，
 * 通过 spring.profiles / 配置切换。
 */
public interface CollectionEventBus {

    /** 发布事件（XADD / 内存队列）。 */
    void publish(CollectionEvent event);

    /** 订阅指定类型事件。文档签名为 String eventType，此处用枚举增强类型安全。 */
    void subscribe(EventType eventType, EventHandler handler);
}
