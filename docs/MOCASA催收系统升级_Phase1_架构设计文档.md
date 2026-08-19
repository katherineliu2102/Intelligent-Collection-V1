# MOCASA 催收系统升级 — Phase 1 架构设计文档

> **版本**: Phase 1 · 仅覆盖菲律宾市场  
> **日期**: 2026-08-18
> **关联文档**: [产品需求文档 (PRD)](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)、[核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md)、[基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md)、[数据接入规格](./MOCASA催收系统升级_Phase1_数据接入规格.md)、[领域模型与数据定义](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md)

---

## 目录

- [1. 系统架构设计](#1-系统架构设计)
  - [1.1 架构总览](#11-架构总览)
  - [1.2 系统边界（北向入站）](#12-系统边界北向入站)
    - [1.2.1 上游数据接入](#121-上游数据接入)
    - [1.2.2 应用入站](#122-应用入站)
  - [1.3 核心引擎](#13-核心引擎)
  - [1.4 渠道编排层（南向出站）](#14-渠道编排层南向出站)
  - [1.5 数据服务层](#15-数据服务层)
    - [1.5.1 持久层 Repository](#151-持久层-repository)
    - [1.5.2 领域服务 Service（common 契约）](#152-领域服务-servicecommon-契约)
  - [1.6 关键架构机制](#16-关键架构机制)
    - [1.6.1 事件编排与调度边界](#161-事件编排与调度边界)
    - [1.6.2 数据所有权与快照边界](#162-数据所有权与快照边界)
    - [1.6.3 幂等、并发与终态单调](#163-幂等并发与终态单调)
    - [1.6.4 事务边界与可靠投递](#164-事务边界与可靠投递)
    - [1.6.5 外部交互安全](#165-外部交互安全)
    - [1.6.6 可观测性与人工处置](#166-可观测性与人工处置)
    - [附：基础设施实现索引](#附基础设施实现索引)
- [2. 技术栈决策](#2-技术栈决策)
- [3. 扩展性与演进路径](#3-扩展性与演进路径)
  - [3.1 演进预留](#31-演进预留)
- [附录 A：架构决策记录 (ADR)](#附录-a架构决策记录-adr)

---

## 1. 系统架构设计

> 业务目标、功能需求、渠道选型理由、监管合规约束的产品层定义见 [产品需求文档 (PRD)](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)。本文档专注于系统分层边界、组件交互方式与关键架构决策。

### 1.1 架构总览

本节给出系统级视图：结构图与流程图呈现组件归属与主链路顺序。各模块的边界与入/出契约见后续章节。入站边界（案件 PubSub / Webhook / 调度 PubSub）见 [§1.2](#12-系统边界北向入站)；引擎内部行为见 [核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md)。

#### 分层结构图

静态分层视图：模块边界、组件归属及主路径调用方向（接入 → 引擎 → 渠道）。接入细节见 [§1.2.1](#121-上游数据接入)。

```
              ┌─────────────────────┐       ┌──────────────────────────────┐
              │ 数仓 Scheduler / Publisher │       │ Cloud Scheduler → 调度 PubSub │
              │ caseEvent / repaymentEvent │       │ dailyRoll（03:35–05:55 PHT）  │
              └──────────┬──────────┘       └────────────┬─────────────────┘
                         │ 案件 PubSub                     │ 调度订阅
                         ▼                                 ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                          数据接入层 (collection-ingestion)                        │
│                                                                                  │
│  ┌─ PubSub 入案 / 还款 ─────────────────────────────────────────────────────┐   │
│  │ 消息路由 → 校验 / 组装 payload → CASE_INGESTED / REPAYMENT_RECEIVED /   │   │
│  │                                      CASE_BALANCE_UPDATED               │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│  ┌─ DPD 日切 ───────────────────────────────────────────────────────────────┐   │
│  │ 只读 t_ai_collection → STAGE_CHANGED / CASE_CEASED                        │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│  两路径均 publish 领域事件；context_snapshot 由引擎建计划时冻结。                │
└───────────────────┬──────────────────────────────────────────────────────────────┘
                    │ 内部事件 (Redis Stream)
                    ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              核心引擎 (engine.lifecycle)                         │
│                                                                                  │
│  EventConsumerDispatcher  → 事件消费路由 + 并发控制 (SELECT FOR UPDATE)            │
│  PlanLifecycleManager     → 状态机驱动 (6 状态, 2 终态)                           │
│  StepExecutionOrchestrator→ 步骤执行骨架                                        │
│  PreFlightChecker         → 系统级守卫                                          │
│                                                                                  │
│  SpiInvoker → 统一硬超时调用 common.spi 契约                                      │
└───────────────────┬──────────────────────────────────────────────────────────────┘
                    │ SPI 调用 (接口契约)
                    ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           渠道编排 (collection-channel)                            │
│                                                                                  │
│  ┌─ 策略子层 (collection-channel.strategy) ───────────────────────────────┐      │
│  │  DefaultPlanFactory / ConfigurableExecutionGuard / DefaultStepResolver  │      │
│  │  ScriptLibrary / ConfigTemplateProvider（DB / Nacos 热更新）             │      │
│  │  DefaultAdvancementPolicy / DefaultExhaustionPolicy                     │      │
│  └────────────────────────────────────────────────────────────────────────┘      │
│                              │ StepCommand                                        │
│  ┌─ 执行子层 (collection-channel, 哑管道) ────────────────────────────────┐      │
│  │  ChannelGateway → 模板渲染 → 幂等 / 熔断 → ChannelAdapter.send()        │      │
│  │  ┌─────┐ ┌────┐ ┌───────┐ ┌─────┐                                  │      │
│  │  │ SMS │ │Push│ │AI_Call│ │Email│  ← Phase 1 机器轨（4 渠道）        │      │
│  │  └─────┘ └────┘ └───────┘ └─────┘                                  │      │
│  │  Notification（SMS / Push）/ SendGrid（Email）/ AI_CALL Mock           │      │
│  └────────────────────────────────────────────────────────────────────────┘      │
│  注：TTS / 人工外呼由 LTH 现网独立编排。Viber / WhatsApp 等演进见 §3.1            │
│                                                                                  │
│  所有执行结果 → StepResult 回传核心引擎 + 写入 t_contact_timeline               │
└──────────────────────────────────────────────────────────────────────────────────┘
```

**横切依赖**（数据服务层 `collection-service` + `collection-common` 契约）：各层按需经 **Repository**（引擎贴表读写）与 **Service**（跨模块聚合 / Redis / 外部桥接）访问 MySQL / Redis / BigQuery，为贯穿各层的持久化能力，与主链路并行调用。完整清单见 [§1.5](#15-数据服务层)。

**并列入站**（应用层 `collection-admin`）：Webhook（供应商回调）与调度订阅（外部 Scheduler 触发的步骤到期扫描）与接入层案件 PubSub **同为北向入站**，均收敛为 EventBus 事件后由引擎消费。管理后台 REST 为南向只读查询面（见 [§1.2.2](#122-应用入站)）。

#### 主链路流程图

动态流程视图：一次触达从入案到步骤推进的跨层事件顺序（happy path）。引擎内部执行见 [核心引擎规格 §4–§6](./MOCASA催收系统升级_Phase1_核心引擎规格.md#4-计划生命周期与状态机)。

```
  数仓 Cloud Scheduler → Publisher
       │  caseEvent / repaymentEvent（案件 PubSub）
       ▼
  接入层 (collection-ingestion)
       │  校验 / 写入投影 / 组装内部 payload
       │  publish CASE_INGESTED / REPAYMENT_RECEIVED / CASE_BALANCE_UPDATED
       ▼
  EventBus (Redis Stream) ─────────────────────────────────────┐
       │                                                        │
       ▼                                                        │
  核心引擎 (engine.lifecycle)                                    │
       │  SpiInvoker → PlanFactory 建计划 (PENDING) + 注册首步 Job│
       │                                                        │
       │  ── 等待 trigger_time 到期 ──                           │
       │                                                        │
  应用层 (collection-admin) ◀────────────────────────────────────┘
       │  调度订阅 → PLAN_STEP_DUE（步骤到期，非计划结束）  ← 并列入站（同 Webhook）
       ▼
  核心引擎
       │  SpiInvoker → ExecutionGuard → StepResolver
       │  → ChannelGateway.dispatch(StepCommand)
       ▼
  渠道编排 (collection-channel)
       │  策略决策 → 模板渲染 → Adapter.send()
       │
       ├─ 【同步】SMS / Push / Email
       │      StepResult → publish STEP_COMPLETED
       │                         │
       │                         └→ 核心引擎：AdvancementPolicy
       │                                      →（穷尽时）ExhaustionPolicy
       │
       └─ 【异步】AI_CALL（当前 Mock）
              保持 STEP_EXECUTING
              供应商完成 → Webhook → CHANNEL_CALLBACK
              → STEP_COMPLETED → AdvancementPolicy
```

`collection-common` 是跨模块共享的编译期契约（接口 + 数据结构），不属于主链路中的运行时层；Java 代码为真相源，模块边界保持可独立编译。

### 1.2 系统边界（北向入站）

北向入口的模块、入/出契约与边界如下；业务触发均收敛为领域事件后由核心引擎消费。

| 代码模块 | 入 | 出 | 边界 |
|---|---|---|---|
| `collection-ingestion` | 案件 PubSub：`caseEvent` / `repaymentEvent`；`dailyRoll` 由 admin 调度订阅触发 | `CASE_INGESTED` / `REPAYMENT_RECEIVED` / `CASE_BALANCE_UPDATED` / `STAGE_CHANGED` / `CASE_CEASED` | 不做业务决策、不直接调用渠道；作为 `t_ai_collection` 投影唯一写入者 |
| `collection-admin` | 供应商 Webhook / REST / 调度 PubSub 订阅 | `CHANNEL_CALLBACK` / `PLAN_STEP_DUE` / `CALLBACK_TIMEOUT` / HTTP 响应 | Webhook 与调度订阅仅发布事件；REST 不直接执行催收业务逻辑 |

#### 1.2.1 上游数据接入

```text
数仓 Publisher → 案件 Topic → collection-ingestion → t_ai_collection → 内部 EventBus
```

- `caseEvent` 是完整快照；首次有效快照发布 `CASE_INGESTED`。
- `repaymentEvent` 是运行态增量；整笔结清发布 `REPAYMENT_RECEIVED`，部分还款发布 `CASE_BALANCE_UPDATED`。
- `dailyRoll` 只读投影，独占发布 `STAGE_CHANGED` / `CASE_CEASED`；外部同名事件按 poison 处置。

数仓只发布消息，接入层是 `t_ai_collection` 唯一写入者。外部消息字段、发布时序、幂等、ACK/NACK 与日切规则以 [数仓 Pub/Sub 交付契约](./数仓_PubSub交付契约.md) 和 [数据接入规格](./MOCASA催收系统升级_Phase1_数据接入规格.md) 为准。

#### 1.2.2 应用入站

| 入站职责 | 说明 |
|---|---|
| Webhook 回调入口 | 统一接收外部供应商回调，鉴权后发布 `CHANNEL_CALLBACK` 事件到事件总线（异步回调闭环见 [§1.6.5](#165-外部交互安全)） |
| 调度订阅（Trigger-to-Event） | 消费外部 Scheduler 投递的调度消息 → 扫表 → 发布领域事件 → 毫秒级返回；**不执行业务逻辑**（线程隔离见 [§1.6.1](#161-事件编排与调度边界)） |

**调度机制**：运维用 **Cloud Scheduler** 定时向**调度专用 Pub/Sub 主题**发消息，应用侧用**专用订阅**消费并触发扫描。不依赖调度执行器、固定端口或入站网络，也不新增对外 HTTP 调度接口；鉴权由 GCP 服务账号与订阅权限承担。三个任务共用一个调度订阅，按消息属性 `job` 路由。

**调度任务与场景**（`register_job(...)` 底层即写 DB 的 `trigger_time` / `timeout_time`，到期由扫表拾取）：

| `job` 属性 | 执行组件 | cron（Asia/Manila） | 扫描条件 | 发布事件 | 典型场景 |
|---|---|---|---|---|---|
| `planStepDue` | `ScheduledJobRunner` → `PlanStepTriggerPublisher`（admin） | `* * * * *`（每分钟） | `trigger_time <= NOW()` 且步骤待触发、计划非终态 | `PLAN_STEP_DUE` | 计划首步/后续步到期触发触达；退避重试到期 |
| `callbackTimeout` | `ScheduledJobRunner` → `PlanStepTriggerPublisher`（admin） | `* * * * *`（每分钟） | `timeout_time <= NOW()` 且 step=`EXECUTING`、计划非终态 | `CALLBACK_TIMEOUT` | AI_CALL dispatch 后 Webhook 超时未到，步骤 FAILED 并推进 |
| `dailyRoll` | `ScheduledJobRunner` → `DpdStageRollHandler`（ingestion） | `35,40,45,50,55 3 * * *` + `*/5 4-5 * * *` | 只读 `t_ai_collection` 的 `dpd` / `stage` / `collection_status`，每次仅推进一页 keyset；目标是在当日 `caseEvent` 批次已消费完毕后开始，当前仍按固定窗口触发，批次完成信号与显式门控待接入 | `STAGE_CHANGED` / `CASE_CEASED` | 阶段变更或停催；03:35–05:55 PHT 运行，06:00 前完成 |

完整规格（含陈旧消息防抖、ACK 语义、单飞保护、运维交付清单）见 [基础设施 §5](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#5-定时调度cloud-scheduler--pubsub--应用订阅)。

### 1.3 核心引擎

核心引擎（`engine.lifecycle`）承载“何时做什么”：消费内部事件、驱动计划状态机并经 SPI 调用渠道。它不直接调用供应商 API，也不承载渠道业务规则。

| 代码模块 | 入 → 出 | 边界 |
|---|---|---|
| `engine.lifecycle` | EventBus 事件 → 派生事件 / SPI 调用 / ChannelGateway 调度 | 状态机与执行顺序；不直接调用供应商 API |

`EventConsumerDispatcher` 路由事件，`PlanLifecycleManager` 驱动计划状态，`StepExecutionOrchestrator` 执行步骤，`PreFlightChecker` 校验实时可触达性。计划状态机、七步管线与恢复机制以 [核心引擎规格 §2–§5](./MOCASA催收系统升级_Phase1_核心引擎规格.md#2-事件路由) 为准。

5 个 SPI 归 `collection-common` 所有、由 `collection-channel` 实现：`PlanFactory`、`ExecutionGuard`、`StepResolver`、`AdvancementPolicy`、`ExhaustionPolicy`。引擎只经 `SpiInvoker` 调用；接口签名、调用时机与超时策略见 [核心引擎规格 §6](./MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约)。

### 1.4 渠道编排层（南向出站）

渠道编排是触达的执行出口。当前 Phase 1 的策略实现与执行实现均位于 `collection-channel`：策略子层实现共享 SPI 契约并由核心引擎回调做业务决策，执行子层经 `ChannelGateway` 调用供应商 API。与 [§1.2 北向入站](#12-系统边界北向入站) 对称，构成「EventBus 入 → 引擎 → 渠道出」闭环。

引擎只经 `ChannelGateway` 调度执行子层，不直连 `ChannelAdapter`；策略子层实现 `collection-common` 的 5 个 SPI，读快照并由 `ExecutionGuard` 读取实时合规计数；执行子层将 `StepCommand` 经模板、熔断和 `ChannelAdapter` 转为 `StepResult`，不查业务库。SPI 定义见 [核心引擎规格 §6](./MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约)；Adapter、合规链、模板与供应商映射见 [渠道编排规格](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md)。

### 1.5 数据服务层

`collection-service` 承载新库 MySQL 持久化（MyBatis）与 `t_ai_collection` 只读访问，**纯数据存取，不含编排 / 触发**。BigQuery 为后台冷层与渠道参考查询，非本模块职责。

**契约分工**：接口全部定义在 `collection-common`，实现按存储归属分散在三个模块——

| 契约 | 用途 | 实现模块 |
|---|---|---|
| `common.repository`（6 个，[§1.5.1](#151-持久层-repository)） | 新库 MySQL 表读写：计划域 3 + 事件/回调审计 3 | `collection-service` |
| `common.service`（5 个，[§1.5.2](#152-领域服务-servicecommon-契约)） | 案件表只读、Redis 键、外部系统桥接 | `collection-service` / `collection-engine` / `collection-channel` |

表结构见 [领域模型](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md)；方法全集见接口 Javadoc 与 [基础设施 §6](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#6-持久层与跨存储一致性)。模板 / 合规规则 / offer 无 common 契约，见 [§1.4](#14-渠道编排层南向出站)。

#### 1.5.1 持久层 Repository

引擎与 admin 对 MySQL 的读写全部经 Repository 接口，**不直连 Mapper**。`Mapper` 指 `collection-service` 内的 MyBatis 映射接口（一个 Mapper 约对应一张表，SQL 写在方法注解或同名 XML），只允许 Repository 实现类调用；引擎不感知 Mapper，也不依赖 `collection-service`——换 ORM 或改案件表映射不触发引擎重编。

**落地路径**（三段固定命名，据此定位文件）：`common.repository.XxxRepository`（契约）→ `service.repository.XxxRepositoryImpl`（实现）→ `service.mapper.*Mapper`（SQL）。

| Repository | 覆盖表 | 调用方（类） | 关键方法 / 用途 |
|---|---|---|---|
| `ContactPlanRepository` | `t_contact_plan` + `t_contact_plan_step` | `engine.lifecycle`、`engine.reaper`；admin `PlanStepTriggerPublisher`（Cron 扫表）、`PlanQueryController`（只读） | `findPlanWithLock`（行锁）、`findDueSteps` / `findTimeoutSteps`（Cron 扫描）、`savePlan` |
| `TimelineRepository` | `t_contact_timeline` | `engine.lifecycle`（写触达结果、读近期历史）；admin `PlanQueryController`（只读） | `writeTimeline` / `getContactHistory` |
| `DecisionLogRepository` | `t_decision_log` | `StepExecutionOrchestrator`（只写） | 落 ④ 渠道/话术决策；fail-open 不阻断触达 |
| `EventOutboxRepository` | `t_event_outbox` | `engine.outbox`：`OutboxEventSink` 写、`OutboxPublisher` 重发 | 派生事件与状态迁移同事务落库、投递后销账（[引擎 §7.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#72-派生事件可靠投递)） |
| `EventDlqRepository` | `t_event_dlq` | `engine.bus`（重试耗尽入列）、admin `DlqController`（`/ops/dlq` 重放） | 死信持久化与受控重放 |
| `ChannelCallbackAuditRepository` | `t_channel_callback_audit` | admin `WebhookController`（只写） | 供应商原始回调留痕；不计入 timeline 触达次数 |

#### 1.5.2 领域服务 Service（common 契约）

契约在 `collection-common/service`，**共 5 个，下表即全集**。「载体」是该服务访问的存储或外部系统，**不是**业务写回地址——落库一律走 §1.5.1 的 Repository。本文只描述生产实现；本地测试替身见测试文档与 HANDOFF。

| Service | 调用方 | 职责 | 载体 / 读写 | 生产实现（模块） |
|---|---|---|---|---|
| `CaseService` | 引擎骨架②、`ingestion` 日切 | 案件存活与还款实时校验（并带出渲染用 `dpd` / 余额）；日切 keyset 扫描；**不为入案主链路回填快照** | `t_ai_collection`，只读 | `collection-service`：`AiCollectionCaseService`（`case-service=ai`、`@Primary`） |
| `ProfileService` | 不在主链路 | 画像聚合；联系方式由 `caseEvent` 的 `borrower` / `device` 带出 | 只读；来源待单独接入 | 未接入不得作生产兜底；演进见 [§3.1](#31-演进预留) |
| `IdempotencyService` | 引擎骨架①、渠道执行层 | 步骤与触达的重复执行拦截（[§1.6.3](#163-幂等并发与终态单调)） | Redis `collection:lock:plan:` 等键 + TTL，读写 | `collection-engine`：`RedisIdempotencyService`（`SET NX EX`，跨实例） |
| `ComplianceCounterService` | 渠道 `ExecutionGuard`（骨架③） | 单渠道与跨渠道日配额的原子占用 | Redis `collection:compliance:` 计数器，读写 | `collection-channel`：`RedisComplianceCounterService`（Lua 原子；Redis 异常上抛以 fail-close） |
| `PredictiveDialerService` | 引擎 `REPAYMENT_RECEIVED` 处理 | 结清后请求 AI Call 合作方移出排队名单（`filterRepaidCase`） | AI Call 合作方 HTTP，无本地存储 | `collection-channel`：AI Call 合作方名单过滤 API；失败仅告警，不阻断计划取消 |

> 生产就绪差集见 [HANDOFF D.1](../HANDOFF.md#d1-生产就绪差集登记)；本地测试替身只在测试文档与代码配置中维护。

### 1.6 关键架构机制

本节只定义跨模块不变量，不重复状态机、伪代码、超时值或指标名。引擎内部行为以 [核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md) 为 SSOT；接入、基础设施与渠道细节以各专项规格为准。

**机制目录**

| 机制 | 要解决的问题 | 主责层 |
|---|---|---|
| [1.6.1 事件编排与调度边界](#161-事件编排与调度边界) | 谁触发、谁执行业务 | admin、引擎 |
| [1.6.2 数据所有权与快照边界](#162-数据所有权与快照边界) | 数据由谁写、何时冻结 | 接入、引擎 |
| [1.6.3 幂等、并发与终态单调](#163-幂等并发与终态单调) | 重复和竞态如何收敛 | 接入、引擎、渠道 |
| [1.6.4 事务边界与可靠投递](#164-事务边界与可靠投递) | 状态提交后如何不丢事件 | 引擎、基础设施 |
| [1.6.5 外部交互安全](#165-外部交互安全) | 外部依赖失败如何受控 | 引擎、渠道、admin |
| [1.6.6 可观测性与人工处置](#166-可观测性与人工处置) | 异常如何发现与处置 | 全链路 |

#### 1.6.1 事件编排与调度边界

**不变量**：模块间只经 EventBus 异步协作；调度只扫描和发布事件，不执行业务或渠道 I/O。

- 调度线程与 Consumer 线程隔离；所有业务事件统一经 Redis Stream 流转。
- 事件路由以 [核心引擎规格 §2.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#21-事件路由表ssot) 为 SSOT；调度入口与 ACK 语义见 [基础设施 §5](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#5-定时调度cloud-scheduler--pubsub--应用订阅)。

#### 1.6.2 数据所有权与快照边界

**不变量**：接入层是案件投影唯一写入者；计划使用创建时冻结的 `context_snapshot`，不回查静态画像。

- 完整快照首次入案建计划；还款仅更新运行态或中断计划，阶段变化只由 `dailyRoll` 产生。
- 实时还款状态、合规计数和对外文案的日变字段是明确例外；字段口径见 [数据接入规格 §3](./MOCASA催收系统升级_Phase1_数据接入规格.md#3-案件消息处理主链路)，快照定义见 [领域模型 §4.4](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#44-contextsnapshot决策上下文快照)。

#### 1.6.3 幂等、并发与终态单调

**不变量**：至少一次投递下同一步至多触达一次；同一计划同时只允许一个 Consumer 修改，终态不可逆。

- 接入处理、内部事件、步骤和渠道各自去重；具体键、TTL 与消息乱序规则以 [数据接入规格 §3.2](./MOCASA催收系统升级_Phase1_数据接入规格.md#32-投影写入inbox-与幂等) 和 [基础设施 §4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#4-运行时状态redis-kv) 为准。
- 引擎以行锁、状态机幂等转移和“先写先赢”的终态单调收敛竞争；完整并发模型见 [核心引擎规格 §3.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#32-并发与一致性模型)。
- Pre-flight 通过到渠道发出之间的空窗（通常 < 500ms）Phase 1 接受，不另做紧急拦截；演进见 [§3.1](#31-演进预留)。

#### 1.6.4 事务边界与可靠投递

**不变量**：短事务只覆盖锁与状态前置；渠道 I/O 在事务外；状态迁移与派生事件必须可恢复地关联。

- 调用顺序为 `Dispatcher → Manager（事务）→ COMMIT → Orchestrator（I/O）`，避免慢渠道扩大锁窗口。
- 计划派生事件由 Outbox 兜底，接入事实由 Inbox 补发；恢复细节见 [核心引擎规格 §7.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#72-派生事件可靠投递) 与 [数据接入规格 §3.4](./MOCASA催收系统升级_Phase1_数据接入规格.md#34-内部事件发布与-pending-补发)。

#### 1.6.5 外部交互安全

**不变量**：SPI 调用必须有硬超时；异步语音回调必须能收敛，不让计划永久停滞。

- SPI 的超时与失败策略按调用影响域分级；具体超时值和结果处理以 [核心引擎规格 §6/§7](./MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约) 为准。
- 仅 `AI_CALL` 走回调闭环：Webhook 鉴权后发布 `CHANNEL_CALLBACK`，超时由 `CALLBACK_TIMEOUT` 收敛。供应商标识、签名和状态机见 [核心引擎规格 §4.3](./MOCASA催收系统升级_Phase1_核心引擎规格.md#43-步骤执行循环)。
- 结清可取消计划并 `filterRepaidCase` 移出排队，但不能中止已发起的呼叫；用户仍可能接到一通在途外呼。演进见 [§3.1](#31-演进预留)。

#### 1.6.6 可观测性与人工处置

**不变量**：静默跳过、降级、重试、DLQ 与停摆都必须可观测；系统不在依据不足时自动重发外部触达。

- 结构化日志以 `eventId`、`caseId`、`planId`、`stepId` 串联；指标与告警以 [基础设施 §7](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#7-配置管理与可观测性) 为准。
- Outbox 失败、DLQ 或停摆触发人工处置；`StuckPlanReaper` 只检测和告警，不自动重建或重发。

#### 附：基础设施实现索引

> 非机制不变量，仅登记上述机制依赖的基础设施规格去向。

`CollectionEventBus` 接口定义于 `collection-common`（生产 `RedisStreamEventBusImpl` / 本地 CI `InMemoryEventBus`）；完整规格见 [基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md)。

| 项 | 规格去向 |
|---|---|
| 事件总线可靠性（PEL 重投；轮询自愈，无需看门狗） | [基础设施 §3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#3-事件总线redis-stream) |
| 死信队列（三级恢复 + 合规时段校验） | [基础设施 §3.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#33-异常恢复与死信) · [§3.4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#34-重放前合规时段校验) · [核心引擎 §7.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#71-统一处置规则) |
| 背压与线程隔离（Consumer 池 + CallerRunsPolicy） | [基础设施 §2.2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#22-生产消费拓扑线程职责与背压) |

---

## 2. 技术栈决策

下表是 Phase 1 **当前已选栈**，不是待办清单。

| 维度 | 旧系统 | Phase 1 | 说明 |
|---|---|---|---|
| 运行时 | Java 8 | Java 8 | |
| 框架 | Spring Boot 2.1 | Spring Boot 2.7.18 | Boot 3.2+ / Java 17 演进见 [§3.1](#31-演进预留) |
| 构建/部署 | — | Maven 多模块单体；Pilot 单活跃实例 | Redis 使后续扩实例不改业务语义；模块划分见 [§1.1](#11-架构总览) |
| 配置 | 本地 properties | Nacos + 本地 YAML | 本地 `application-*.yml`；生产 / Pilot 由 admin 引入 Nacos |
| 安全 | Shiro | Session + 拦截器；Webhook HMAC | 管理后台 REST 用 `AdminAuthInterceptor`；Webhook 生产/Pilot 强制验签，引擎不依赖后台登录 |
| 持久化 | MySQL + MyBatis | MySQL 8 + MyBatis | |
| Redis | 缓存/限流 | Stream 事件总线 + KV | 生产依赖：事件、幂等、合规计数、日切游标；见 [基础设施规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md) |
| 外部消息 | GCP PubSub（同步） | 两条隔离 Pub/Sub | 案件 Topic → `collection-ingestion`；调度 Topic → `collection-admin` |
| 分析冷层 | — | BigQuery（只读，规划） | 后台趋势看板 / 渠道参考 SQL；**不是**案件入站源，也非 `collection-service` 职责 |
| 调度 | XXL-Job | Cloud Scheduler → 调度 Topic → 应用订阅 | Trigger-to-Event，见 [§1.6.1](#161-事件编排与调度边界)；不复用 XXL-Job |
| 流程编排 | 无 | 自建状态机 + SPI | 见 [§1.3](#13-核心引擎) |
| 可观测 | 无 | Actuator + Micrometer → Prometheus | Grafana / 告警路由待运维接通；原则见 [§1.6.6](#166-可观测性与人工处置) |

---

## 3. 扩展性与演进路径

### 3.1 演进预留

本文所有 Phase 2 项只在此登记。Phase 1 以替换实现或补接口为主，不改主链路边界。

| 项 | Phase 1 | Phase 2 |
|---|---|---|
| Viber / WhatsApp | `ChannelAdapter` 占位 | 签约后接入统一适配层 |
| AI_CALL 呼叫中止 | 仅 `filterRepaidCase` 移出排队 | 评估单次呼叫取消 API |
| 渠道对账扫描 | Webhook + `CALLBACK_TIMEOUT` | 按供应商补齐已发未落盘结果 |
| 独立画像兜底 | `ProfileService` 不在主链路 | 单独画像源后再接入 |
| PTP / 争议冻结 | 规格预留，不交付 | 到期事件与后台冻结操作 |
| 减免 offer | snapshot 字段占位（[PRD §4.2](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)） | 动态 offer 计算 |
| LLM 决策 / 计划 / 话术 / 续建 / 质检 | 现有 SPI 内部可替换点；`TranscriptService` 预留 | 配置切换或 A/B |
| Pre-flight 竞态空窗 | Phase 1 接受 | Redis 临界标记 + 紧急拦截 |
| 运行演进 | Nacos；Java 8 / Boot 2.7；模块可独立编译 | `t_system_property` 审计；Boot 3 / Java 17；按需拆 engine / channel；替换 `CollectionEventBus` 实现 |

---

## 附录 A：架构决策记录 (ADR)

> 系统与渠道处置。运行不变量见 [§1.6](#16-关键架构机制)；Phase 2 演进见 [§3.1](#31-演进预留)；产品层决策见 [PRD §9](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)。

| 系统/渠道 | 处置 | 决策说明 |
|---|---|---|
| Issabel 手动拨打 | **废弃** | 通话数据不回流，由 LTH 预测式外呼替代 |
| Saiduo AI 机器人 | **废弃** | 仅印度活跃，菲律宾未启用 |
| WizAI AI 机器人 | **废弃** | 代码已停用 |
| Microsip 自动拨打 | **废弃** | 功能合并到统一触达引擎 |
| 到期前通知（信贷主系统） | **接管** | 新系统接管发送，信贷系统仅推送案件数据 |
| LTH 平台 | **保留** | TTS / 人工外呼由 LTH 现网独立编排，与本系统无交互。机器轨 `AI_CALL` 经独立合作方对接（Phase 1 Mock），接口不得绑定 LTH |
| WSCRM WhatsApp | **Phase 2** | 见 [§3.1](#31-演进预留) |
| collection_rebuild | **升级** | 保留数据模型，重构架构 |

---

> MOCASA Collection System Upgrade — Phase 1 Architecture Design Document — 2026-08-18
