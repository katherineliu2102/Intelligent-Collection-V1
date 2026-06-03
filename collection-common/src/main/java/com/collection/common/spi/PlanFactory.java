package com.collection.common.spi;

import com.collection.common.enums.Stage;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContextSnapshot;

/**
 * SPI — 计划工厂。对应核心引擎规格 §4.1。
 *
 * <p>调用时机：案件入库 / 阶段变更 / 穷尽续建。
 * <p>决策问题：创建什么触达计划？
 * <p>引擎应对：抛异常 → NACK 延迟重消费。硬超时 50ms。
 * <p>null 语义：返回 null = 该案件不需要建计划（正常返回值）。
 * <p>幂等约束：同一 case_id + stage 不重复创建计划。
 * <p>副作用约束：禁止写 DB / 发布事件 / 调用外部服务。
 */
public interface PlanFactory {

    ContactPlan create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot);
}
