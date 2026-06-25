# MOCASA 催收系统升级 — Phase 1 基础设施交互规范

> **版本**: Phase 1 · v1.0  
> **日期**: 2026-06-01  
> **范围**: 仅覆盖菲律宾市场  
> **定位**: 定义核心引擎对外部基础设施（线程池、Redis Stream/KV、XXL-Job、MySQL Repository）的交互约束，以及配置管理与可观测性埋点要求。  
> **关联文档**: [产品需求文档 (PRD)](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)、[架构设计文档](./MOCASA催收系统升级_Phase1_架构设计文档.md)、[核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md)

---

## 目录

- [1. 消费线程模型](#1-消费线程模型)
- [2. 事件总线（Redis Stream）](#2-事件总线redis-stream)
- [3. 运行时状态（Redis KV）](#3-运行时状态redis-kv)
- [4. 定时调度（XXL-Job）](#4-定时调度xxl-job)
- [5. 持久层（Repository）](#5-持久层repository)
- [6. 配置管理与可观测性](#6-配置管理与可观测性)
- [附录：配置参数汇总](#附录配置参数汇总)

---

## 1. 消费线程模型

[核心引擎规格 §1.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#12-trigger-to-event-线程隔离) 定义了线程隔离的架构决策（Consumer Pool 与 Cron Thread 分离），本节给出具体规格参数和安全约束。

### 线程池架构

```mermaid
graph LR
  subgraph jvm [JVM Process]
    subgraph consumerPool ["Consumer ThreadPool（有界队列）"]
      CT1[engine-consumer-1]
      CT2[engine-consumer-2]
      CTn[engine-consumer-N]
    end
    subgraph cronPool [Cron Thread]
      Cron1[XXL-Job Handler]
    end
    subgraph daemon [Daemon]
      WD["Watchdog（单线程）"]
      PEL["PEL Scanner（单线程）"]
    end
  end
  RedisStream -->|XREADGROUP| consumerPool
  cronPool -->|"scan DB → XADD"| RedisStream
  daemon -->|heartbeat check| consumerPool
```

三组线程互不共享线程池，任何一组阻塞不影响其他组。

### Consumer 线程池规格

| 参数 | 值 | 说明 |
|---|---|---|
| 类型 | `ThreadPoolExecutor` | 非 `ScheduledThreadPool`，调度由消费循环自驱 |
| corePoolSize | `engine.consumer.thread_pool_size`（默认 8） | 等于消费并发度 |
| maximumPoolSize | = corePoolSize | 固定大小，不动态扩缩；突发流量由队列缓冲 |
| workQueue | `LinkedBlockingQueue(engine.consumer.queue_capacity)`（默认 256） | 有界队列 |
| rejectedExecutionHandler | `CallerRunsPolicy` | 队列满时阻塞消费循环线程，XREADGROUP 暂停拉取，Redis Stream 自然积压但不丢消息 |
| threadFactory | `NamedThreadFactory("engine-consumer-%d")` | 线程命名便于日志 / thread dump 定位 |
| keepAliveTime | 0（core 不回收） | 固定池大小 |

> 拒绝策略选择 `CallerRunsPolicy` 而非 `AbortPolicy`（丢任务抛异常）或 `DiscardPolicy`（静默丢弃）：队列满 → Caller 阻塞 → XREADGROUP 停拉 → Stream 积压 → 上游感知背压。不丢消息、不 OOM、无需额外流控。

### 降级日志防刷

`CallerRunsPolicy` 触发时须输出 WARN 日志，但高负载下可能每秒触发数百次。**约束**：背压日志必须使用 `RateLimiter` 压制（每 5 秒最多一条），内容包含当前队列深度和 Stream 积压量：

```
WARN [engine-consumer-loop] BackpressureTriggered — queue_depth=256, stream_pending=1832
```

> Watchdog 检测心跳时须排除"Caller 线程正在执行被拒绝任务"的场景（通过原子标志位 `callerRunning` 区分），防止将背压误判为假死。

### Daemon 线程组规格

| 守护任务 | 线程模型 | 执行频率 | 安全约束 |
|---|---|---|---|
| PEL Scanner | `ScheduledThreadPoolExecutor(1)`，命名 `engine-pel-scanner` | 每 5 分钟（`engine.consumer.pel_scan_interval_minutes`） | 每次 XPENDING 必须携带 `COUNT`（`engine.consumer.pel_batch_size`，默认 100），防止崩溃重启后一次性捞出海量积压导致 OOM |
| Watchdog | `ScheduledThreadPoolExecutor(1)`，命名 `engine-watchdog` | 每 `watchdog.heartbeat_interval_seconds`（默认 10s）检测一次 | 必须 catch **`Throwable`**（非仅 `Exception`），防止偶发 Redis 连接超时的 `Error` 导致看门狗线程退出 |

> PEL 扫描是低频兜底机制（处理崩溃后遗留消息），5 分钟间隔足够。扫描频率不应高于看门狗超时阈值（60s），避免 PEL 消息在 idle 阈值（10min）内被误判为活跃。

### 背压告警联动

Consumer 线程池必须注册 Micrometer `ExecutorServiceMetrics`，确保 [运维与协作](./MOCASA催收系统升级_Phase1_运维与协作.md) §1.2.2 定义的 `collection.event.consumer.thread.utilization` 和 `collection.event.stream.lag` 指标有数据来源。

---

## 2. 事件总线（Redis Stream）

`CollectionEventBus` 接口定义于 `collection-common`，Phase 1 由 `RedisStreamEventBusImpl` 实现。接口抽象使未来替换消息中间件（Kafka、RabbitMQ）时业务代码零改动。

**实现选型** ✅：技术栈为 Spring Boot 2.7.18，采用 Spring Data Redis 内置的 **`StreamMessageListenerContainer`**（Consumer Group 模式）承载消费循环，无需手写 Lettuce 轮询。容器负责订阅、反序列化分发与基础错误重启；PEL 拾取与看门狗作为崩溃/连接假死的兜底补充（下述）：

```java
public interface CollectionEventBus {
    void publish(CollectionEvent event);
    void subscribe(String eventType, EventHandler handler);
}
```

| 实现细节 | 说明 |
|---|---|
| 发布端 | `XADD` 写入 Redis Stream，事件序列化为 JSON，包含事件信封（eventId、eventType、timestamp、payload） |
| 消费端 | `XREADGROUP` 消费组模式，Consumer Group 保证同一事件仅被组内一个消费者处理 |
| ACK 机制 | 业务处理成功后显式 `XACK`；处理失败不 ACK → pending list → 重投递；不可重试（如反序列化失败）→ 直接 DLQ；retryable 但重投递次数达上限（`engine.consumer.max_delivery_count`，默认 5）→ DLQ + 告警（毒消息防护，避免无限重投占满 Consumer） |

**PEL 拾取机制**：进程在 `XACK` 前崩溃时，消息滞留 PEL，新实例仅读 `>` 会永久漏触达。消费者启动时及 PEL Scanner 定期执行（频率与规格见 [§1](#1-消费线程模型)）：

```
1. XPENDING <stream> <group> - + COUNT  （返回每条消息的投递次数 delivery_count）
2. 对 idle > pel_idle_minutes 的消息执行 XAUTOCLAIM（或 XCLAIM），转移至当前消费者
3. delivery_count > max_delivery_count 的消息判定为毒消息 → XACK 移出 PEL + 写 DLQ + 告警（不再重投）
4. 其余重新投入消费管线处理（幂等保护保证安全重试）
```

> PEL idle 阈值默认 10 分钟（`engine.consumer.pel_idle_minutes`），须大于单条消息最长处理时间，防止误抢活跃消息。

**看门狗机制**：`StreamMessageListenerContainer` 的轮询线程在连接假死（Lettuce 连接断开但无异常退出，容器 ErrorHandler 不触发）时可能静默停摆。线程规格见 [§1](#1-消费线程模型)，核心逻辑：

| 组件 | 行为 |
|---|---|
| 容器投递 | `MessageListener` 在每次投递（含空轮询回调）后更新心跳时间戳（Redis `SET` 或内存变量） |
| 守护线程 | 心跳超时（`watchdog.timeout_seconds`，默认 60s）时：① 先 `container.stop()` 优雅停止旧订阅（等待终止）；② 重建 Lettuce 连接并 `container.start()` 重启订阅；③ 触发告警（[运维与协作](./MOCASA催收系统升级_Phase1_运维与协作.md)） |

> 重启前必须先停止旧订阅，防止旧连接（网络卡顿非真死）与新连接并存导致双重消费。

---

## 3. 运行时状态（Redis KV）

核心引擎涉及的 Redis 数据遵循统一的 key 设计和生命周期管理。

### Key 前缀约定

| 前缀 | 用途 | 数据类型 | 示例 |
|---|---|---|---|
| `compliance:` | 合规计数器（每日/每周触达次数） | String（计数） | `compliance:daily:{user_id}:{channel}:{date}` |
| `processed:` | 事件消费去重标记 | String（标记） | `processed:{event_id}` |
| `lock:plan:` | 分布式幂等锁（步骤级） | String（SETNX） | `lock:plan:{step_idempotency_key}` |
| `idempotency:` | 渠道层二次去重 | String（SETNX） | `idempotency:channel:{idempotency_key}` |

### TTL 策略

| Key 类型 | TTL | 理由 |
|---|---|---|
| 合规计数器（daily） | 当日 23:59:59 过期 | 自然日重置 |
| 合规计数器（weekly） | 7 天 | 自然周重置 |
| 幂等锁 | 15 分钟（`step.idempotency_ttl_minutes`） | 覆盖事件重复消费窗口，过期自动释放 |
| 渠道层去重 | 24 小时 | 覆盖供应商回调延迟窗口 |
| 事件消费去重 | 24 小时 | At-least-once 消费去重 |
| 看门狗心跳 | 无 TTL（持续覆写） | 守护线程主动检查，无需自动过期 |

### 内存淘汰策略

Redis 实例配置 ✅ `maxmemory-policy = volatile-lru`：仅淘汰设有 TTL 的 key，保护无 TTL 的 Stream 数据不被误驱逐。

### 合规计数器实现约束

`ExecutionGuard` 的硬超时为 20ms（[核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览)）。合规计数的读取 + 增加 + 设 TTL 必须在**单次 Redis 交互**内完成，使用 Lua 脚本或 Pipeline，目标延迟 < 5ms：

```lua
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIREAT', KEYS[1], ARGV[1])
end
return current
```

---

## 4. 定时调度（XXL-Job）

核心引擎通过 XXL-Job 实现 Trigger-to-Event 模式（[核心引擎规格 §1.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#12-trigger-to-event-线程隔离)）。本节明确 Job Handler 定义及伪代码中 `register_job()` / `cancel_scheduled_jobs()` 的底层语义。

### Job Handler 定义

| Handler | Cron | 扫描逻辑 | 发布事件 |
|---|---|---|---|
| `planStepDueHandler` | `0 * * * * ?`（每分钟） | `t_contact_plan_step WHERE trigger_time <= NOW()` 且步骤状态为待触发、关联计划为非终态 | `PLAN_STEP_DUE` |
| `callbackTimeoutHandler` | `0 * * * * ?`（每分钟） | `t_contact_plan_step WHERE timeout_time <= NOW() AND status = 'EXECUTING'` 且关联计划为非终态 | `CALLBACK_TIMEOUT`（[核心引擎规格 §4.3.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#434-异步回调超时兜底)） |
| `ptpExpiredHandler`（**Phase 2 预留，Phase 1 不启用**） | — | — | `PTP_EXPIRED`（Phase 1 引擎不消费，见 [核心引擎规格 §2.6](./MOCASA催收系统升级_Phase1_核心引擎规格.md#26-ptp-到期处理)） |

> 触达对时间精度要求低（±1min 可接受），Cron 扫描粒度足够。Phase 2 可评估延迟消息替代。

**扫描分页约束**：每次 Cron 执行必须携带 `LIMIT N`（Phase 1 默认 1000）防止单次全表扫描拖垮 DB。若 `count(result) == LIMIT`，说明存在积压，应**触发告警**（[运维与协作](./MOCASA催收系统升级_Phase1_运维与协作.md)）而非多轮递归消费——剩余消息等下一轮 Cron 处理，避免单次 Job 执行超时。

> Phase 2 扩展建议：数据量增大后可开启 XXL-Job **分片广播**（`ShardingUtil.getShardingVo()`），按 `plan_id % sharding_total == sharding_index` 切分扫描范围，水平扩展 Cron 处理能力。Phase 1 暂不启用，待压测后评估。

### 伪代码中 register_job / cancel_scheduled_jobs 的语义

[核心引擎规格 §2-§3](./MOCASA催收系统升级_Phase1_核心引擎规格.md#2-计划生命周期与状态机) 伪代码中的 `register_job()` 和 `cancel_scheduled_jobs()` 是逻辑抽象，底层实现基于"写 DB + Cron 扫描"模式：

| 伪代码 | 实际操作 |
|---|---|
| `register_job(PLAN_STEP_DUE, trigger_time)` | 设置 `t_contact_plan_step.trigger_time` 为目标时间，步骤状态置为待触发；Cron 到期后扫描拾取并发布事件 |
| `register_job(CALLBACK_TIMEOUT, timeout_minutes)` | 设置 `t_contact_plan_step.timeout_time = NOW() + timeout_minutes`；Cron 到期后扫描拾取 |
| `cancel_scheduled_jobs(plan)` | 计划状态已置为终态（`PLAN_CANCELLED`），Cron 扫描时通过关联计划状态过滤，自动跳过 |

Cron 线程仅做"扫表 → 发事件 → 返回"，**严禁 I/O 阻塞**（[核心引擎规格 §1.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#12-trigger-to-event-线程隔离)），所有业务处理由 Consumer 线程池完成。

> **投诉解冻恢复**：被实时冻结"停住"的计划仍为非终态（[核心引擎规格 §5.1 ②](./MOCASA催收系统升级_Phase1_核心引擎规格.md#51-execute_step-七步管线)）。解冻时 admin 清除案件级实时冻结标记，并对该计划当前步骤重新 `register_job(PLAN_STEP_DUE, NOW())`（即重设 `trigger_time`）让 Cron 重新拾取、从停住步骤恢复——复用既有事件，不新增事件/状态。冻结标记为**实时案件状态字段**，由 PreFlightChecker 实时读取，不写入 snapshot、不走合规计数器（[运行时状态 §3 合规计数器](#3-运行时状态redis-kv) 仅服务 ExecutionGuard）。若解冻发生在原步骤幂等锁 TTL（默认 15 分钟）内，重注入可能被 §5.1 ① 吸收；admin 恢复实现须等待幂等窗口过期，或显式清理/更换该步骤幂等键后再重注入。（可选：若不依赖 admin 重注入，infra 也可在冻结时注册短延迟 `PLAN_STEP_DUE` 自轮询，解冻后下一次重扫自动通过；Phase 1 默认走 admin 重注入，避免冻结期 Job 轮询开销。）

---

## 5. 持久层（Repository）

核心引擎通过 Repository 接口访问持久层。表结构见 [领域模型与数据定义](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md)。

### 生命周期阶段 × 数据操作映射

| 生命周期阶段 | 触发事件 | 数据读取 | 数据写入 |
|---|---|---|---|
| 计划创建 | CASE_INGESTED / STAGE_CHANGED | getCaseInfo, getSnapshot | savePlan(plan+steps) |
| 步骤到期 | PLAN_STEP_DUE | — | updatePlanStatus(STEP_EXECUTING) |
| 步骤执行 | （Orchestrator 内部） | getContactHistory | updateStepStatus, writeTimeline |
| 步骤完成推进 | STEP_COMPLETED | getNextStep | updateStepTriggerTime |
| 中断取消 | REPAYMENT_RECEIVED / STAGE_CHANGED | findActivePlans | updatePlanStatus(CANCELLED) |
| 穷尽续建 | PLAN_EXHAUSTED | getCaseInfo, getSnapshot, getLastPlan | savePlan |
| PTP到期（**Phase 2 预留**） | PTP_EXPIRED（Phase 1 不消费） | — | — |

### Repository 接口清单

| 方法 | 语义 | 事务要求 | 对应表 |
|---|---|---|---|
| findPlanWithLock(planId) | SELECT FOR UPDATE 获取单计划行锁 | 必须在事务内 | t_contact_plan |
| findPlansWithLock(List\<Long\> planIds) | 批量 SELECT FOR UPDATE；**实现内部必须按 planId 升序排列后再加锁**，防止死锁 | 必须在事务内 | t_contact_plan |
| findActivePlansByUser(userId) | 用户所有非终态计划 | 只读 | t_contact_plan |
| findActivePlansByCase(caseId) | 案件所有非终态计划 | 只读 | t_contact_plan |
| savePlan(plan) | 持久化计划 + 步骤序列 | 事务 | t_contact_plan + t_contact_plan_step |
| updatePlanStatus(planId, status, reason) | 计划状态写入 | 事务 | t_contact_plan |
| updateStepStatus(stepId, status, result) | 步骤执行结果 | 事务 | t_contact_plan_step |
| updateStepTriggerTime(stepId, time) | 注册下一步到期 | 事务 | t_contact_plan_step |
| updateStepTimeoutTime(stepId, time) | 注册回调超时 | 事务 | t_contact_plan_step |
| writeTimeline(entry) | 写触达时间线 | 事务 | t_contact_timeline |
| getCaseInfo(caseId) | 案件基本信息 | 只读 | t_collection_case |
| getContextSnapshot(caseId) | 不可变快照 | 只读 | t_contact_plan.context_snapshot |
| getContactHistory(userId, limit) | 近期触达历史 | 只读 | t_contact_timeline |
| isRepaid(caseId) | 实时还款状态 | 只读 | t_collection_case |
| getPtpRecord(ptpId) | PTP 记录（**Phase 2 预留**，Phase 1 不实现） | 只读 | t_collection_ptp_info（Phase 2） |
| updatePtpStatus(ptpId, status) | PTP 状态更新（**Phase 2 预留**，Phase 1 不实现） | 事务 | t_collection_ptp_info（Phase 2） |
| getNextStep(planId, currentStepOrder) | 计划中的下一步 | 只读 | t_contact_plan_step |
| getLastCompletedPlan(caseId) | 最近完成/穷尽的计划 | 只读 | t_contact_plan |

---

## 6. 配置管理与可观测性

### 6.1 配置刷新机制

附录参数通过 `t_system_property` 表管理。刷新规格：

| 项 | 规格 |
|---|---|
| 数据源 | `t_system_property`（MySQL） |
| 加载方式 | 启动时全量加载至内存缓存 |
| 刷新方式 | 定时轮询（`@Scheduled(fixedDelay = 30_000)`），按 key 级别合并 |

> **配置来源（2026-06 更新）**：运行时连接与渠道密钥由 **Nacos** 下发（`intelligent-collection-common.yml` / `intelligent-collection-local.yml`，见 [操作说明.md](../操作说明.md)）。引擎附录参数（`engine.*`、`channel.compliance.*` 等）Phase 1 可同时放在 Nacos；`t_system_property` DB 轮询为 Phase 2 可选方案（参数变更频率低时 Nacos + `@RefreshScope` 已足够）。

**参数热更分类**：附录参数表"热更"列标注每个参数的运行时修改安全等级：

| 分类 | 特征 | 代码行为 |
|---|---|---|
| **Y**（热更安全） | 仅影响下一次执行的决策值，不涉及运行时结构 | 轮询后立即生效 |
| **Y-注意**（窗口类） | TTL/超时窗口参数，改小可能导致新老不一致 | 生效但有新老交替期风险（见下方） |
| **N**（需重启） | 涉及线程池结构、Redis 连接参数 | `PropertyRefreshService` 检测到变更后仅 log WARN，**不生效** |

**窗口类参数"新老交替期"风险**：对于 `idempotency_ttl_minutes` 等窗口类参数，代码在写入 Redis key 时动态读取最新配置值设置 TTL。若将 TTL 从 60 分钟改小到 10 分钟，系统不会追溯修改已存在的 key，但新 key 立即应用 10 分钟。改小后存在一个"新老交替期"（最长等于旧 TTL 值），期间幂等窗口表现不一致。改大则无此风险。

**变更审计**：每次参数变更写入 `t_system_property_audit`（old_value / new_value / operator / timestamp），便于排查"改参数后行为异常"。

### 6.2 可观测性接入约束

指标定义和告警规则见 [运维与协作](./MOCASA催收系统升级_Phase1_运维与协作.md) §1-§2。本节约束引擎代码的埋点位置，确保运维指标有数据来源。

**指标注册点**（对应运维文档 §1.2）：

| 注册位置 | 指标 | 实现方式 |
|---|---|---|
| `RedisStreamEventBusImpl` | `event.published`, `event.consumed`, `event.consume.duration` | `Counter` / `Timer` |
| Consumer 线程池初始化 | `event.consumer.thread.utilization` | `ExecutorServiceMetrics.monitor()` |
| PEL Scanner | `event.pending` | `Gauge`（每次扫描后更新） |
| DLQ 写入 | `event.dlq.size` | `Counter` |
| Watchdog 重建 | `event.watchdog.restart` | `Counter` |
| `StepExecutionOrchestrator` | `touch.total`、步骤耗时 | `Counter` / `Timer` |

**结构化日志字段（MDC）**：引擎关键路径日志必须通过 SLF4J MDC 携带以下字段：

| MDC Key | 来源 | 生命周期 |
|---|---|---|
| `caseId` | 事件 payload | 消费入口 set → 处理完成 clear |
| `planId` | Plan 实体 | PlanLifecycleManager 入口 set |
| `stepId` | Step 实体 | StepExecutionOrchestrator 入口 set |
| `eventType` | 事件信封 | 消费入口 set |
| `eventId` | 事件信封 | 消费入口 set |
| `consumerId` | 本实例 consumer name | 启动时 set，线程级别 |

**跨线程 MDC 传递约束（研发红线）**：MDC 基于 `ThreadLocal`，跨线程时上下文丢失。引擎的 Consumer 线程池本身即为跨线程场景（消费循环 → 工作线程）。

- 严禁直接使用原生 `new Thread()` 或未包装的 `ExecutorService` 执行异步任务
- 所有自建线程池必须使用 `MdcTaskDecorator` 包装（提交时 `MDC.getCopyOfContextMap()`，执行时 `MDC.setContextMap(copy)`，结束时 `MDC.clear()`）
- 若使用 Spring `@Async`，`AsyncConfigurer` 必须返回包装后的 `Executor`

> Phase 1 通过 Code Review checklist 落地，Phase 2 通过 ArchUnit 规则自动检测。

---

## 附录：配置参数汇总

以下参数通过 `t_system_property` 表管理，核心引擎启动时加载。热更分类见 [§6.1](#61-配置刷新机制)。

| 参数 Key | 默认值 | 热更 | 说明 | 影响范围 |
|---|---|---|---|---|
| `engine.step.idempotency_ttl_minutes` | `15` | Y-注意 | 步骤幂等锁 TTL（分钟）。改小有新老交替期风险 | [核心引擎规格 §5.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#51-execute_step-七步管线) 步骤① |
| `engine.step.max_retry_count` | `3` | Y | 步骤渠道发送最大重试次数 | [核心引擎规格 §5.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#51-execute_step-七步管线) 步骤⑥ |
| `engine.step.retry_base_interval_seconds` | `30` | Y | 首次重试退避基准（秒） | [核心引擎规格 §5.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#51-execute_step-七步管线) 步骤⑥ |
| `engine.step.retry_max_interval_seconds` | `300` | Y | 退避上限（秒） | [核心引擎规格 §5.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#51-execute_step-七步管线) 步骤⑥ |
| `engine.step.retry_backoff_factor` | `2` | Y | 退避倍数（第 n 次等待 = min(base × factor^n, max)） | [核心引擎规格 §5.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#51-execute_step-七步管线) 步骤⑥ |
| `engine.step.executing_reaper_minutes` | `30` | Y | step=EXECUTING 滞留判定阈值（分钟）；超时且无 `timeout_time` 由 reaper 复检兜底 | [核心引擎规格 §7.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#74-跨存储一致性修复) |
| `engine.plan.max_rebuild_count` | `2` | Y | 单案件单阶段最大续建次数 | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览) ExhaustionPolicy |
| `engine.spi.plan_factory.timeout_ms` | `50` | Y | PlanFactory 硬超时（毫秒） | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览) |
| `engine.spi.execution_guard.timeout_ms` | `20` | Y | ExecutionGuard 硬超时（毫秒） | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览) |
| `engine.spi.step_resolver.timeout_ms` | `50` | Y | StepResolver 硬超时（毫秒） | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览) |
| `engine.spi.advancement_policy.timeout_ms` | `10` | Y | AdvancementPolicy 硬超时（毫秒） | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览) |
| `engine.spi.exhaustion_policy.timeout_ms` | `50` | Y | ExhaustionPolicy 硬超时（毫秒） | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览)（**SPI 超时五项语义 SSOT=引擎 §4.1**；本附录仅列配置 key 供运维检索） |
| `engine.consumer.thread_pool_size` | `8` | N | Consumer 线程池大小。需重启生效 | [§1](#1-消费线程模型) |
| `engine.consumer.queue_capacity` | `256` | N | Consumer 线程池有界队列容量。需重启生效 | [§1](#1-消费线程模型) |
| `engine.consumer.poll_timeout_ms` | `2000` | Y | 每次 XREADGROUP 阻塞超时（毫秒） | [§2](#2-事件总线redis-stream) |
| `engine.consumer.batch_size` | `10` | Y | 每次 XREADGROUP 最大拉取条数 | [§2](#2-事件总线redis-stream) |
| `engine.consumer.pel_scan_interval_minutes` | `5` | Y | PEL 扫描间隔（分钟） | [§1](#1-消费线程模型) |
| `engine.consumer.pel_idle_minutes` | `10` | Y-注意 | PEL idle 阈值（分钟），须大于单条消息最长处理时间 | [§2](#2-事件总线redis-stream) |
| `engine.consumer.pel_batch_size` | `100` | Y | PEL 单次扫描 COUNT 上限 | [§1](#1-消费线程模型) |
| `engine.consumer.max_delivery_count` | `5` | Y | 单条消息最大重投次数；超限转 DLQ + 告警（毒消息防护） | [§2](#2-事件总线redis-stream) |
| `engine.watchdog.heartbeat_interval_seconds` | `10` | N | 消费线程心跳上报间隔（秒）。需重启生效 | [§2](#2-事件总线redis-stream) |
| `engine.watchdog.timeout_seconds` | `60` | Y | 看门狗判定假死的超时阈值（秒） | [§2](#2-事件总线redis-stream) |
| `engine.redis.key_prefix` | `collection:` | N | Redis key 全局前缀。需重启生效 | [§3](#3-运行时状态redis-kv) |
| `engine.compliance.daily_limit` | 按渠道配置 | Y | 每日触达次数上限（渠道维度） | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览) ExecutionGuard |
| `engine.compliance.weekly_limit` | 按渠道配置 | Y | 每周触达次数上限（渠道维度） | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览) ExecutionGuard |
| `engine.compliance.quiet_hours_start` | `21:00` | Y | 禁呼时段开始（菲律宾本地时间） | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览) ExecutionGuard |
| `engine.compliance.quiet_hours_end` | `08:00` | Y | 禁呼时段结束（菲律宾本地时间） | [核心引擎规格 §4.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#41-接口总览) ExecutionGuard |
| `engine.consumer.group_name` | `collection-engine` | N | Redis Stream Consumer Group 名称。需重启生效 | [§2](#2-事件总线redis-stream) |
| `engine.consumer.stream_key` | `collection:event_stream` | N | Redis Stream key 名称。需重启生效 | [§2](#2-事件总线redis-stream) |
| `engine.step.callback_timeout_minutes` | `60` | Y-注意 | 异步渠道回调超时（分钟）。改小有新老交替期风险 | [核心引擎规格 §4.3.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#434-异步回调超时兜底) |
| `engine.context.history_max_records` | `50` | Y | ExecutionContext.contactHistory 最大记录数 | [核心引擎规格 §6.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#62-共享-dto-定义) |

> 渠道编排侧的配置参数见 [渠道编排规格](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md)。
