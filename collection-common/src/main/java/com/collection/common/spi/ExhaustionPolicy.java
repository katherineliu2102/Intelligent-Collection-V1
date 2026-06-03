package com.collection.common.spi;

import com.collection.common.dto.ExhaustionResult;
import com.collection.common.model.CaseInfo;
import com.collection.common.model.ContactPlan;
import com.collection.common.model.ContextSnapshot;

/**
 * SPI — 穷尽策略。对应核心引擎规格 §4.1、§2.5、§2.6。
 *
 * <p>决策问题：所有步骤用完怎么办？（续建 / 升档 / 完成）
 * <p>引擎应对：抛异常 → NACK 延迟重消费（穷尽是生命周期关键节点）。硬超时 50ms。
 * <p>null 语义：不允许返回 null。
 * <p>副作用约束：禁止写 DB / 发布事件 / 调用外部服务。
 * ESCALATE 时的 STAGE_CHANGED 事件由引擎发布，非实现方职责。
 */
public interface ExhaustionPolicy {

    ExhaustionResult handle(ContactPlan plan, CaseInfo caseInfo, ContextSnapshot snapshot);
}
