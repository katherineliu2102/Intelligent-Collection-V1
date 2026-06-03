package com.collection.common.event;

/**
 * 事件处理器。由核心引擎 EventConsumerDispatcher 按事件类型注册。
 */
@FunctionalInterface
public interface EventHandler {

    /**
     * 处理一条事件。
     * 抛异常表示处理失败：Redis Stream 实现下不 ACK → 重投递 / DLQ（基础设施规范 §2）。
     */
    void handle(CollectionEvent event) throws Exception;
}
