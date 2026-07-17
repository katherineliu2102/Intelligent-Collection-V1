# MOCASA 催收系统升级 — Phase 1 架构设计文档

> **版本**: Phase 1 · 仅覆盖菲律宾市场  
> **日期**: 2026-07-01  
> **关联文档**: [产品需求文档 (PRD)](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)、[核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md)、[基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md)、[数据接入规格](./MOCASA催收系统升级_Phase1_数据接入规格.md)、[领域模型与数据定义](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md)

---

## 目录

- [1. 系统架构设计](#1-系统架构设计)
  - [1.1 架构总览](#11-架构总览)
  - [1.2 系统边界（北向入站）](#12-系统边界北向入站)
    - [1.2.1 上游数据接入](#121-上游数据接入)
    - [1.2.2 应用入站](#122-应用入站)
  - [1.3 核心引擎](#13-核心引擎)
    - [1.3.1 引擎组件](#131-引擎组件)
    - [1.3.2 步骤执行骨架](#132-步骤执行骨架)
    - [1.3.3 SPI 接口概要](#133-spi-接口概要)
  - [1.4 渠道编排层（南向出站）](#14-渠道编排层南向出站)
  - [1.5 数据服务层](#15-数据服务层)
    - [1.5.1 持久层 Repository](#151-持久层-repository)
    - [1.5.2 领域服务 Service（common 契约）](#152-领域服务-servicecommon-契约)
  - [1.6 关键架构机制](#16-关键架构机制)
    - [1.6.1 事件驱动 + 定时触发](#161-事件驱动--定时触发)
    - [1.6.2 决策上下文快照化](#162-决策上下文快照化)
    - [1.6.3 幂等键契约](#163-幂等键契约)
    - [1.6.4 并发竞态控制与终态单调](#164-并发竞态控制与终态单调)
    - [1.6.5 事务边界：状态前置与渠道 I/O 隔离](#165-事务边界状态前置与渠道-io-隔离)
    - [1.6.6 SPI 硬超时与失败分级处置](#166-spi-硬超时与失败分级处置)
    - [1.6.7 异步回调对账](#167-异步回调对账)
    - [1.6.8 可观测性守卫](#168-可观测性守卫)
    - [附：基础设施实现索引](#附基础设施实现索引)
- [2. 技术栈决策](#2-技术栈决策)
- [3. 扩展性与演进路径](#3-扩展性与演进路径)
  - [3.1 容量扩展](#31-容量扩展)
  - [3.2 演进预留](#32-演进预留)
- [附录 A：架构决策记录 (ADR)](#附录-a架构决策记录-adr)

---

## 1. 系统架构设计

> 业务目标、功能需求、渠道选型理由、监管合规约束的产品层定义见 [产品需求文档 (PRD)](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)。本文档专注于系统分层边界、组件交互方式与关键架构决策。

### 1.1 架构总览

本节给出系统级视图：结构图与流程图呈现组件归属与主链路顺序。各模块的边界与入/出契约见后续章节。入站边界（PubSub / Webhook / XXL-Job）见 [§1.2](#12-系统边界北向入站)；引擎组件、七步骨架与 SPI 见 [§1.3](#13-核心引擎)。

#### 分层结构图

静态分层视图：模块边界、组件归属及主路径调用方向（接入 → 引擎 → 渠道）。接入细节见 [§1.2.1](#121-上游数据接入)。

```
              ┌─────────────────────┐       ┌──────────────────────────┐
              │    上游信贷系统       │       │ collection-admin / XXL-Job │
              │ case_push / repayment│       │ dailyRoll（每日 0:35 PHT） │
              └──────────┬──────────┘       └────────────┬─────────────┘
                         │ PubSub                          │ 定时触发
                         ▼                                 ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                          数据接入层 (collection-ingestion)                        │
│                                                                                  │
│  ┌─ PubSub 入案 / 还款 ─────────────────────────────────────────────────────┐   │
│  │ 消息路由 → 校验 / 组装 payload → CASE_INGESTED / REPAYMENT_RECEIVED      │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│  ┌─ DPD 日切 ───────────────────────────────────────────────────────────────┐   │
│  │ 只读扫描在催名单 / bill → 重算 Max DPD → STAGE_CHANGED / CASE_CEASED      │   │
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
│  StepExecutionOrchestrator→ 步骤执行骨架（§1.3.2）                                │
│  PreFlightChecker         → 系统级守卫（§1.3.2 ②）                              │
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
│  │  [Viber / WhatsApp — Phase 2 预留，仅 Adapter 接口占位]                 │      │
│  └────────────────────────────────────────────────────────────────────────┘      │
│  注：TTS / 人工外呼（坐席/预测式）由 LTH 现网独立编排，与本系统无交互              │
│                                                                                  │
│  所有执行结果 → StepResult 回传核心引擎 + 写入 t_contact_timeline               │
└──────────────────────────────────────────────────────────────────────────────────┘
```

**横切依赖**（数据服务层 `collection-service` + `collection-common` 契约）：各层按需经 **Repository**（引擎贴表读写）与 **Service**（跨模块聚合 / Redis / 外部桥接）访问 MySQL / Redis / BigQuery，为贯穿各层的持久化能力，与主链路并行调用。完整清单见 [§1.5](#15-数据服务层)。

**并列入站**（应用层 `collection-admin`）：Webhook（供应商回调）与 XXL-Job（步骤到期触发）与接入层 PubSub **同为北向入站**，均收敛为 EventBus 事件后由引擎消费。管理后台 REST 为南向只读查询面（见 [§1.2.2](#122-应用入站)）。

#### 主链路流程图

动态流程视图：一次触达从入案到步骤推进的跨层事件顺序（happy path）。引擎内部见 [§1.3.2–§1.3.3](#132-步骤执行骨架)。

```
  上游信贷系统
       │  case_push (PubSub)
       ▼
  接入层 (collection-ingestion)
       │  校验 / 组装 payload
       │  publish CASE_INGESTED
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
       │  XXL-Job → PLAN_STEP_DUE（步骤到期，非计划结束）  ← 并列入站（同 Webhook）
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
| `collection-ingestion` | PubSub：`case_push` / `repayment`；`dailyRoll` 调度 | `CASE_INGESTED` / `REPAYMENT_RECEIVED` / `STAGE_CHANGED` / `CASE_CEASED` | 不做业务决策、不直接调用渠道、不回写旧库 |
| `collection-admin` | 供应商 Webhook / REST / XXL-Job | `CHANNEL_CALLBACK` / `PLAN_STEP_DUE` / `CALLBACK_TIMEOUT` / HTTP 响应 | Webhook 与 XXL-Job 仅发布事件；REST 不直接执行催收业务逻辑 |

#### 1.2.1 上游数据接入

PubSub 字段映射、清洗与 DPD 日切算法见 [数据接入规格](./MOCASA催收系统升级_Phase1_数据接入规格.md)。

> `jpushToken` 随 `case_push` 携带；入案主链路零读库。缺失时可启用 `collection.ingestion.enrich-jpush-token=true`，降级读取 `t_user_device_token`。`CASE_INGESTED` payload 由引擎冻结为 `context_snapshot`；字段定义见 [领域模型 §6](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#6-eventpayload-字段定义)。

#### 1.2.2 应用入站

| 入站职责 | 说明 |
|---|---|
| Webhook 回调入口 | 统一接收外部供应商回调，鉴权后发布 `CHANNEL_CALLBACK` 事件到事件总线（异步回调闭环见 [§1.6.7](#167-异步回调对账)） |
| XXL-Job（Trigger-to-Event） | 定时扫表 → 发布领域事件 → 毫秒级返回；**不执行业务逻辑**（线程隔离见 [§1.6.1](#161-事件驱动--定时触发)） |

**XXL-Job Handler 与场景**（生产目标；`register_job(...)` 底层即写 DB 的 `trigger_time` / `timeout_time`，Cron 到期扫表拾取）：

| Handler | Cron | 扫描条件 | 发布事件 | 典型场景 |
|---|---|---|---|---|
| `planStepDueHandler` | 每分钟 | `trigger_time <= NOW()` 且步骤待触发、计划非终态 | `PLAN_STEP_DUE` | 计划首步/后续步到期触发触达；观察期结束重触发；退避重试到期 |
| `callbackTimeoutHandler` | 每分钟 | `timeout_time <= NOW()` 且 step=`EXECUTING`、计划非终态 | `CALLBACK_TIMEOUT` | AI_CALL dispatch 后 Webhook 超时未到，步骤 FAILED 并推进 |
| `dailyRoll` | 每日 0:35 PHT | 旧库在催名单 + bill DPD | `STAGE_CHANGED` / `CASE_CEASED` | DPD 日切导致阶段变更或停催 |

完整规格见 [基础设施 §4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#4-定时调度xxl-job)。

### 1.3 核心引擎

核心引擎（`engine.lifecycle`）承载"何时做什么"：事件消费、状态机驱动、步骤执行骨架与并发控制，通过 SPI 与渠道编排解耦——**不直接调用供应商 API、不包含业务规则**。SPI 接口契约位于共享层 `collection-common/common.spi`，引擎以 `SpiInvoker` 统一执行硬超时调用。内部技术规格（状态机、七步管线、SPI 完整定义）见 [核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md)。

| 代码模块 | 入 → 出 | 边界 |
|---|---|---|
| `engine.lifecycle` | EventBus 事件 → 派生事件 / SPI 调用 / ChannelGateway 调度 | 不直接调用供应商 API，不包含业务规则 |

#### 1.3.1 引擎组件

| 组件 | 职责 |
|---|---|
| `EventConsumerDispatcher` | 消费 Redis Stream 事件、路由到生命周期处理器、提交后发布派生事件 |
| `PlanLifecycleManager` | 状态机（6 状态 / 2 终态），短事务内获取行锁、终态拦截与状态前置 |
| `StepExecutionOrchestrator` | 步骤执行骨架（见 [§1.3.2](#132-步骤执行骨架)） |
| `PreFlightChecker` | 系统级守卫（七步骨架第②步）：案件是否存在且未还款 |

#### 1.3.2 步骤执行骨架

`StepExecutionOrchestrator` 定义步骤从触发到完成的**固定执行骨架**（①–⑦ + ⑤½ 取消复检），只串联顺序、不含业务逻辑；其中业务守卫与步骤解析两个决策点 SPI 化（见 [§1.3.3](#133-spi-接口概要)），渠道 I/O 经 `ChannelGateway` 在事务外执行（见 [§1.6.5](#165-事务边界状态前置与渠道-io-隔离)）。步骤完成后由 `PlanLifecycleManager` 消费 `STEP_COMPLETED` 并调用 `AdvancementPolicy` 决策推进。逐步伪代码与各步崩溃恢复见 [核心引擎规格 §5](./MOCASA催收系统升级_Phase1_核心引擎规格.md#5-步骤执行管线)。

| 步骤 | 职责 | 失败/退出 |
|---|---|---|
| ① 幂等锁 | `idempotency_key` SETNX 去重 | 重复 → 静默退出 |
| ② 系统级守卫 | `PreFlightChecker` 实时查案件存活（不存在 / 已还款） | 不可触达 → 静默退出；读取失败 → NACK 重投 |
| ③ 业务级守卫 | SPI `ExecutionGuard` 合规校验（时段 / 空地址 / 频率） | 静默时段 → 延后执行；其他拦截、异常或超时 → SKIPPED + 推进 |
| ④ 步骤解析 | SPI `StepResolver` 读 `context_snapshot` 生成 `StepCommand` | 异常或超时 → FAILED + 推进；返回 `null` → SKIPPED + 推进 |
| ⑤ 渠道调度 | `ChannelGateway.dispatch()` → `StepResult` | 可重试失败 → 退避重试；其余失败交 ⑥ |
| ⑤½ 取消复检 | 渠道 I/O 后重读 plan 状态 | 已取消 → 写 timeline，不推进 |
| ⑥ 故障降级 | 处理不可重试或重试耗尽的 dispatch 失败 | FAILED + 推进 |
| ⑦ 渠道分流 | 消息类完成或进入观察期；AI_CALL 保持 `STEP_EXECUTING` 等回调 | AI_CALL 注册 `CALLBACK_TIMEOUT` 哨兵 |

**渠道类型对状态的影响**：

| 渠道类别 | 调用后状态 | 完成方式 |
|---|---|---|
| 消息类 (SMS/Push/Email)；Viber/WhatsApp Phase 2 预留 | STEP_WAITING 或直接推进 | 同步返回成功 |
| 电话类 (AI_CALL) | 保持 STEP_EXECUTING | 等待 `CHANNEL_CALLBACK`（AI Call 供应商 Webhook 回调） |

> 计划状态机见 [核心引擎规格 §4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#4-计划生命周期与状态机)；并发竞态控制见 [§1.6.4](#164-并发竞态控制与终态单调) 与 [核心引擎规格 §3.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#32-并发与一致性模型)。

#### 1.3.3 SPI 接口概要

5 个 SPI 的**接口契约归共享层 `collection-common/common.spi` 所有**，由渠道编排提供实现；核心引擎以 `SpiInvoker` 调用。`EventConsumerDispatcher` 只做事件路由与事务边界控制，**不直接调用 SPI**；调用者与时机如下（签名与 DTO 见 [核心引擎规格 §6](./MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约)，Phase 1 实现见 [渠道编排规格](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md)）：

| SPI | 调用组件 | 调用时机 |
|---|---|---|
| `PlanFactory` | `PlanLifecycleManager` | 案件入库 / 阶段变更 / 续建 |
| `ExecutionGuard` | `StepExecutionOrchestrator` | 步骤执行前（骨架③） |
| `StepResolver` | `StepExecutionOrchestrator` | Guard 通过后（骨架④） |
| `AdvancementPolicy` | `PlanLifecycleManager` | `STEP_COMPLETED` 消费后 |
| `ExhaustionPolicy` | `PlanLifecycleManager` | `PLAN_EXHAUSTED` 事件 |

**系统级 vs 业务级守卫**：`PreFlightChecker`（引擎内置，骨架②）校验案件是否仍具备触达资格（存在且未还款）；SPI `ExecutionGuard`（骨架③）校验每次触达是否符合时段、地址和频率等合规规则。

> 完整的 SPI 接口定义（方法签名、DTO 字段、实现约束）见 [核心引擎规格 §6 SPI](./MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约)；异常恢复见 [§7](./MOCASA催收系统升级_Phase1_核心引擎规格.md#7-容错与异常恢复)。

### 1.4 渠道编排层（南向出站）

渠道编排是触达的执行出口。当前 Phase 1 的策略实现与执行实现均位于 `collection-channel`：策略子层实现共享 SPI 契约并由核心引擎回调做业务决策，执行子层经 `ChannelGateway` 调用供应商 API。与 [§1.2 北向入站](#12-系统边界北向入站) 对称，构成「EventBus 入 → 引擎 → 渠道出」闭环。

引擎只经 `ChannelGateway` 调度执行子层，不直连 `ChannelAdapter`；策略子层实现 [§1.3.3](#133-spi-接口概要) 的 5 个 SPI，读快照并由 `ExecutionGuard` 读取实时合规计数；执行子层将 `StepCommand` 经模板、熔断和 `ChannelAdapter` 转为 `StepResult`，不查业务库。SPI 定义见 §1.3.3；Adapter、合规链、模板与供应商映射见 [渠道编排规格](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md)。

### 1.5 数据服务层

`collection-service` 承载 MySQL 持久化（MyBatis）。**纯数据存取，不含编排/触发**。Redis 幂等契约在 `collection-common`、实现在 `collection-engine`（生产 Redis SETNX / 本地内存，见 [§3.1](#31-容量扩展)）；BigQuery 为后台冷层/渠道参考查询，非本模块职责。

| 代码模块 | 入 → 出 | 边界 |
|---|---|---|
| `collection-service` | Repository / Service 调用 → MySQL 读写（MyBatis） | 纯数据存取，不含编排/触发 |

**Repository vs Service**：计划域读写 → Repository（[§1.5.1](#151-持久层-repository)）；跨表 / Redis / 外部桥接 → Service（[§1.5.2](#152-领域服务-servicecommon-契约)）。表结构见 [领域模型](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md)；Repository 方法见 [基础设施 §5](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#5-持久层repository)。模板 / 合规 / offer 无 common 契约，见 [§1.4](#14-渠道编排层南向出站)。

#### 1.5.1 持久层 Repository

`engine.lifecycle` 对计划 / 步骤 / 时间线 / 决策日志的读写全部经 Repository 接口，**不直连 Mapper**。Mapper 是 `collection-service` 内部的 MyBatis SQL 映射层，仅由 Repository 实现类调用；引擎不感知 Mapper。

| Repository | 覆盖表 | 主调用方 | 独有职责 |
|---|---|---|---|
| `ContactPlanRepository` | `t_contact_plan` + `t_contact_plan_step` | `engine.lifecycle` | `findPlanWithLock`（行锁）、`findDueSteps` / `findTimeoutSteps`（Cron 扫描） |
| `TimelineRepository` | `t_contact_timeline` | `engine.lifecycle` | `writeTimeline` / `getContactHistory` |
| `DecisionLogRepository` | `t_decision_log` | `engine.lifecycle`（只写） | 决策日志落 MySQL（可作为后续数仓输入；ETL 不属 Phase 1） |

MyBatis 实现位于 `collection-service`；契约接口位于 `collection-common/repository`。

#### 1.5.2 领域服务 Service（common 契约）

契约在 `collection-common/service`；实现模块与 Phase 1 类见下表。

| Service | 主要功能（主调用方） | 覆盖数据 | Phase 1 实现 |
|---|---|---|---|
| `CaseService` | 提供案件存在/还款状态实时校验及可选快照兜底（引擎） | `t_collection` + 快照反序列化 | `collection-service`：`MockCaseService`（默认）/ `RealCaseService`（`case-service=real`） |
| `ProfileService` | 画像兜底读库（引擎经 `CaseService` 降级调用；主链路靠 payload，不依赖本服务） | 旧库 `t_user_*` 画像 | `collection-service`：联调 `MockProfileService`；上线前须替真（HANDOFF C2） |
| `IdempotencyService` | 为步骤与渠道提供重复执行拦截（引擎骨架① / 渠道执行层） | 幂等键 + TTL（[§1.6.3](#163-幂等键契约)） | `collection-engine`：生产 Redis SETNX / 本地 `InMemoryIdempotencyService`（见 [§3.1](#31-容量扩展)） |
| `PredictiveDialerService` | 还款后请求 LTH 将用户移出 AI Call 排队名单（引擎） | AI Call 供应商桥接（`filterRepaidUser`） | `collection-channel`：`MockPredictiveDialerService`（失败仅告警） |

> 边界：`engine.lifecycle` 经 Repository（§1.5.1）读写计划域表、经 Service（§1.5.2）做案件/画像/幂等/AI Call 供应商桥接，**一律不直连 Mapper**。MyBatis 映射与旧库对接由服务同事维护。

### 1.6 关键架构机制

本节定义引擎核心与跨层的架构不变量（§1.6.1–§1.6.8），支撑 [§1.1](#11-架构总览) 事件驱动主链路可靠运行。机制按四组归类：**事件流转、执行正确性、故障自愈、可观测**。

**机制目录**

| 组 | 机制 | 主责层 |
|---|---|---|
| 事件流转 | [1.6.1 事件驱动 + 定时触发](#161-事件驱动--定时触发) | 引擎 |
| 执行正确性 | [1.6.2 决策上下文快照化](#162-决策上下文快照化) | 接入、引擎 |
| 执行正确性 | [1.6.3 幂等键契约](#163-幂等键契约) | 引擎、渠道 |
| 执行正确性 | [1.6.4 并发竞态控制与终态单调](#164-并发竞态控制与终态单调) | 引擎 |
| 故障自愈 | [1.6.5 事务边界](#165-事务边界状态前置与渠道-io-隔离) | 引擎 |
| 故障自愈 | [1.6.6 SPI 硬超时与失败分级处置](#166-spi-硬超时与失败分级处置) | 引擎、渠道 |
| 故障自愈 | [1.6.7 异步回调对账](#167-异步回调对账) | 引擎、应用 |
| 可观测 | [1.6.8 可观测性守卫](#168-可观测性守卫) | 引擎、基础设施 |

**事件流转**

#### 1.6.1 事件驱动 + 定时触发

**不变量**：模块间经 EventBus 异步协作；定时调度仅发布事件，业务逻辑在 Consumer 线程池执行。

**约束**：
- 调度线程与 Consumer 线程严格隔离
- 核心业务事件 + `CALLBACK_TIMEOUT` 哨兵 + `CASE_CEASED` 停催经 Redis Stream 流转（Phase 1 有效事件清单以 [核心引擎规格 §2.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#21-事件路由表ssot) 为 SSOT；`PTP_EXPIRED` 为 Phase 2 预留，Phase 1 不生产、不流转）
- XXL-Job 采用 Trigger-to-Event：毫秒级返回，不执行业务

> 规格：[核心引擎规格 §3.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#31-线程隔离trigger-to-event) · [核心引擎规格 §2.1 EventType 路由](./MOCASA催收系统升级_Phase1_核心引擎规格.md#21-事件路由表ssot) · [领域模型 §6 EventPayload](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#6-eventpayload-字段定义) · [基础设施 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream)

**执行正确性**

#### 1.6.2 决策上下文快照化

**不变量**：SPI 的静态决策输入（案件 / 画像等）读不可变 `context_snapshot`，不实时回查旧库；单个计划内所有步骤共享同一份决策上下文。（实时存活状态与合规频次计数为明确例外，见下。）

**约束**：
- 接入层组装 `CASE_INGESTED` payload（PubSub 字段 + 按需 enrichment）；引擎建计划时将 payload 映射为不可变 `context_snapshot` 写入 plan 行（主链路不读旧库；`CaseService`/`ProfileService` 仅作可选对账兜底，见 [数据接入规格 §3.1](./MOCASA催收系统升级_Phase1_数据接入规格.md#34-与-caseservice--profileservice-的调用边界)）
- `STAGE_CHANGED` 取消旧阶段计划并重建，新计划 carry-forward 旧快照并刷新 `stage`（[核心引擎规格 §4.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#44-中断处理)）
- 实时读取例外：还款状态由 `PreFlightChecker`（骨架②）实时校验；合规频次 / 时段计数由 `ExecutionGuard`（骨架③）读实时计数器——二者均不走快照。争议冻结及其后台操作为 Phase 2 能力。

> 规格：[领域模型 §4.4 ContextSnapshot](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#44-contextsnapshot决策上下文快照) · [数据接入规格 §3.1](./MOCASA催收系统升级_Phase1_数据接入规格.md#34-与-caseservice--profileservice-的调用边界) · [核心引擎规格 §4.2 计划创建](./MOCASA催收系统升级_Phase1_核心引擎规格.md#42-计划创建) · [§6.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#62-共享-dto-定义)

#### 1.6.3 幂等键契约

**不变量**：At-least-once 投递下，同一步骤对同一用户经幂等键去重至多触达一次；不写死为绝对不重复。

**约束**：
- 三层去重（Redis）：消费层事件去重（`processed:`）→ 步骤级分布式锁（`lock:plan:`）→ 渠道 SETNX 二次去重（`idempotency:channel:`）
- 每步生成唯一 `idempotency_key`；key 前缀与 TTL 见基础设施 §3
- 残余边界：「外部渠道已发出但本地落记录失败」——`AI_CALL` 由 [§1.6.7](#167-异步回调对账) 回调/哨兵兜底；同步渠道（SMS/PUSH/EMAIL）接受极低概率残余，靠幂等键 + 日志可观测，Phase 1 不做回调对账
- 本地 CI 用 `InMemoryIdempotencyService` 替身，语义等价但不跨实例/不持久（见 [§3.1](#31-容量扩展)）

> 规格：[核心引擎规格 §5 步骤①](./MOCASA催收系统升级_Phase1_核心引擎规格.md#5-步骤执行管线) · [基础设施 §3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#3-运行时状态redis-kv)

#### 1.6.4 并发竞态控制与终态单调

**不变量**：同一计划同一时刻仅一个 Consumer 线程修改状态；终态写入不可逆。

**约束**：
- 消费任何事件后，短事务内 `SELECT FOR UPDATE` 获锁 → 状态前置写入 → COMMIT（锁窗口毫秒级；渠道 I/O 在事务外，见 [§1.6.5](#165-事务边界状态前置与渠道-io-隔离)）
- 持锁期间计划已终态则静默退出
- 中断源优先级：`REPAID` > `CEASED` > `STAGE_UPGRADE` > 非终态（与 [核心引擎规格 §3.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#32-并发与一致性模型) SSOT 对齐）
- 计划内多事件无全局有序保证，靠终态单调 + 中断优先级 + 状态机幂等转移收敛（乱序覆盖详见核心引擎 §3.2）
- 与 [§1.6.3](#163-幂等键契约) 互补：幂等防重复消费，竞态防并发写同一计划

> 规格：[核心引擎规格 §3.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#32-并发与一致性模型)

**故障自愈**

#### 1.6.5 事务边界：状态前置与渠道 I/O 隔离

**不变量**：行锁事务仅覆盖获锁与状态前置写入；渠道 I/O 在事务外执行。

**约束**：
- 调用链：`Dispatcher → Manager`（短事务）→ COMMIT → `Orchestrator`（七步管线，含全部渠道 I/O）
- 渠道变慢仅占用 Consumer 线程，不膨胀锁窗口

> 规格：[核心引擎规格 §1.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#11-核心组件与职责)

#### 1.6.6 SPI 硬超时与失败分级处置

**不变量**：全部 5 个 SPI 调用强制硬超时截断，防止 Consumer 线程被卡死 I/O 占满。超时按失败影响域分级处置（详见 [核心引擎规格 §6.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#61-接口总览)）。

**约束**：
- 步骤级 `ExecutionGuard` 超时 → fail-close，标记 SKIPPED 并推进
- 步骤级 `StepResolver` 超时 → 标记 FAILED（前推）
- 计划级 SPI（`PlanFactory` / `AdvancementPolicy` / `ExhaustionPolicy`）超时 → NACK 延迟重消费；重投递超上限转 DLQ（[基础设施 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream)），重复消费由 [§1.6.3](#163-幂等键契约) 幂等兜底
- 合规可触达时段判定归 `ExecutionGuard`（骨架③）；DLQ 重放复用同一口径（[基础设施 §2.2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#22-重放前合规时段校验)）

> 规格：[核心引擎规格 §6.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#61-接口总览)

#### 1.6.7 异步回调对账

**不变量**：仅异步语音渠道（`AI_CALL`）有回调——dispatch 成功后保持 `STEP_EXECUTING`，供应商通话结束后回调引擎带回结果；回调丢失时计划可自愈退出，不永久卡死。（SMS/PUSH/EMAIL 为同步发送，无回调对账。）

**约束**：
- Phase 1：供应商 Webhook → `STEP_COMPLETED`；超时未回调则引擎哨兵 `CALLBACK_TIMEOUT` 自愈
- Phase 2：渠道对账扫描（查供应商补发）作运维兜底，Phase 1 不实现
- Webhook 入站见 [§1.2.2 应用入站](#122-应用入站)

> 规格：[核心引擎规格 §4.3.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#434-callback_timeout) · [§7.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#74-跨存储一致性修复)

**可观测**

#### 1.6.8 可观测性守卫

**不变量**：静默退出 / fail-close / SKIPPED / 哨兵兜底路径须可观测。**Phase 1 最低要求为结构化日志 + MDC**（跨线程串联决策路径）；Micrometer 指标与告警规则为生产埋点设计（**待实现**）。

**约束**：
- Phase 1 落地：上述静默/降级分支打结构化日志，MDC 透传 `caseId`/`planId`/`eventId` 便于串联排障
- 生产埋点（待实现）：引擎在决策点输出 Micrometer 指标（跳过率、SPI 超时率、对账补发量、Stream 延迟、线程利用率），统一接入 Prometheus；新增静默分支须同步加指标

> 规格：[基础设施 §6.2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#62-可观测性接入约束) · [§1 消费线程模型](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#1-消费线程模型) · 告警规则与 Grafana（**规划中**）

#### 附：基础设施实现索引

> 非机制不变量，仅登记上述机制依赖的基础设施规格去向。

`CollectionEventBus` 接口定义于 `collection-common`（生产 `RedisStreamEventBusImpl` / 本地 CI `InMemoryEventBus`）；完整规格见 [基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md)。

| 项 | 规格去向 |
|---|---|
| 事件总线可靠性（PEL 重投 + 看门狗重建） | [基础设施 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream) |
| 死信队列（三级恢复 + 合规时段校验） | [基础设施 §2.1](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#21-dlq-重放redrive) · [§2.2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#22-重放前合规时段校验) · [核心引擎 §7.3](./MOCASA催收系统升级_Phase1_核心引擎规格.md#73-l1-基础设施异常) |
| 背压与线程隔离（Consumer 池 + CallerRunsPolicy） | [基础设施 §1](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#1-消费线程模型) |

---

## 2. 技术栈决策

| 维度 | 旧系统 | Phase 1 目标 | 备注 |
|---|---|---|---|
| 运行时 | Java 8 | Java 8 | |
| 框架 | Spring Boot 2.1 | Spring Boot 2.7.18 | Boot 3.2+ / Java 17 演进见 [§3.2](#32-演进预留) |
| 构建/部署 | — | Maven 多模块单体，单实例 | 模块划分见 [§1.1](#11-架构总览) |
| 配置 | 本地 properties | Nacos | 本地 `application-*.yml`；生产 Nacos（admin 已引 starter） |
| 安全 | Shiro | Shiro（管理后台 REST） | 催收引擎模块不依赖；Webhook 鉴权待接入 |
| 持久化 | MySQL + MyBatis | MySQL 8 + MyBatis | |
| Redis | 缓存/限流 | Stream 事件总线 + KV（幂等、合规计数） | **Phase 1 生产依赖**；内存版仅本地/CI，Redis 实现待接入（HANDOFF D1/D2，见 [§3.1](#31-容量扩展)） |
| 外部消息 | GCP PubSub（同步） | GCP PubSub 异步消费 | `collection-ingestion` |
| 数仓 | — | BigQuery（只读，冷层） | 后台看板冷热分离 / 渠道参考 SQL；**非** `collection-service` 职责（见 [管理后台设计](./MOCASA催收系统升级_Phase1_管理后台设计文档.md)） |
| 调度 | XXL-Job | XXL-Job Trigger-to-Event | 见 [§1.6.1](#161-事件驱动--定时触发)；开发期 `@Scheduled` 占位 |
| 流程编排 | 无 | 自建状态机 + SPI | 见 [§1.3](#13-核心引擎) |
| 可观测 | 无 | Actuator + Micrometer → Prometheus/Grafana | Phase 1 最低：结构化日志 + MDC；Micrometer/告警待实现（见 [§1.6.8](#168-可观测性守卫)） |

---

## 3. 扩展性与演进路径

### 3.1 容量扩展

**当前容量评估**：日均案件量 1w-2w，按每案件 3-5 步骤计算，日均步骤执行 3w-10w 次。集中在 8 小时业务窗口，峰值 QPS 约 1-3。单实例 + 8 线程 Consumer 池 + Redis + MySQL 的组合有 10 倍以上余量，吞吐层面 Phase 1 不需分布式拆分。

**Redis 为 Phase 1 生产依赖**（旧催收系统已使用 Redis）：事件总线、幂等、合规频控的**生产实现均基于 Redis**，`collection-common` 已抽象接口，切换不改业务代码。

| 能力 | 生产实现（Phase 1） | 本地 / CI 替身 | 实现状态 |
|---|---|---|---|
| 事件总线 | `RedisStreamEventBusImpl`（Consumer Group + PEL/看门狗/DLQ） | `InMemoryEventBus`（`collection.eventbus=memory`） | Redis 版待接入（HANDOFF D1） |
| 幂等 | Redis SETNX + TTL | `InMemoryIdempotencyService`（`collection.idempotency=memory`） | Redis 版待接入（HANDOFF D2） |
| 合规频控 | Redis 原子计数（单渠道日上限 + 跨渠道日总上限） | `ConfigurableExecutionGuard` 内存计数 | Redis 版待接入 |

> 内存版仅用于本地开发与 CI 链路验证，**不用于生产**（跨实例不共享、重启清零）。生产以 Redis 保证幂等、频控与事件可靠投递。上线前须完成上述 Redis 实现接入（HANDOFF 模块 D）。

### 3.2 演进预留

SPI 架构下，Phase 2 演进只需新增实现类或替换注入配置：

| 演进方向 | Phase 1 SPI 接口 | Phase 2 新增实现 |
|---|---|---|
| LLM 决策引擎 | `DecisionEngine` (被 SPI 内部调用) | `LLMAgentDecisionEngine`，配置切换或 A/B |
| LLM 动态计划 | `PlanFactory` + `AdvancementPolicy` | LLM 生成计划 + 实时调整步骤 |
| LLM 话术生成 | `StepResolver`（内部调 `ScriptLibrary` / 未来 `TemplateRepository`） | LLM 推荐最优话术 / 生成个性化内容 |
| 智能续建 | `ExhaustionPolicy` | LLM 判断是否值得继续 + 生成新策略 |
| 通话质检 | `TranscriptService` 接口（预留） | LLM 分析转写文本，自动评分 |
| 事件总线替换 | `CollectionEventBus` 抽象接口 | 替换实现类，业务代码零改动 |
| 微服务拆分 | 模块边界清晰，可独立编译 | 按需将 engine / channel 独立部署 |
| Viber / WhatsApp 接入 | `ChannelAdapter` 接口占位 | Phase 1 预留接口，Phase 2 供应商签约后接入 |
| 减免策略规则引擎 | `StepResolver` offer 变量占位（snapshot 字段已预留） | Phase 1 不交付（见 [PRD §4.2](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)）；Phase 2 实现动态 offer 计算 |

---

## 附录 A：架构决策记录 (ADR)

> 系统与渠道处置决策。产品层决策见 [PRD §9](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)。

**系统处置决策**：

| 系统/渠道 | 处置 | 决策说明 |
|---|---|---|
| Issabel 手动拨打 | **废弃** | 通话数据不回流，由 LTH 预测式外呼替代 |
| Saiduo AI 机器人 | **废弃** | 仅印度活跃，菲律宾未启用 |
| WizAI AI 机器人 | **废弃** | 代码已停用 |
| Microsip 自动拨打 | **废弃** | 功能合并到统一触达引擎 |
| 到期前通知（信贷主系统） | **接管** | 新系统接管发送，信贷系统仅推送案件数据 |
| LTH 平台 | **保留** | TTS / 人工外呼由 LTH 现网独立编排，与本系统无交互。机器轨 `AI_CALL` 经 AI Call 供应商对接（Phase 1 Mock，复用 LTH 线路） |
| WSCRM WhatsApp | **Phase 2** | WhatsApp Phase 1 不做；Phase 1 仅 ChannelAdapter 接口预留；Phase 2 接入统一渠道适配层 |
| collection_rebuild | **升级** | 保留数据模型，重构架构 |

**架构风险决策**：

| 风险 | 决策 | 理由 |
|---|---|---|
| Pre-flight 竞态空窗 | **Phase 1 接受** | 概率极低（空窗 < 500ms），后置补偿可覆盖。Phase 2 方向：Redis 临界标记 + 紧急拦截 Stream |
| Lettuce 连接假死 | **Phase 1 加固** | 看门狗机制成本极低且防灾难性停摆（详见 §1.6 附：基础设施实现索引） |
| Webhook 回调丢失 | **Phase 1 加固** | Phase 1 靠 Webhook + `CALLBACK_TIMEOUT` 哨兵自愈；渠道对账扫描属 Phase 2（详见 §1.6.7） |
| DLQ 合规时段碰撞 | **Phase 1 加固** | 成本极低，避免触达计划空跑（详见 §1.6 附：基础设施实现索引） |
| AI_CALL 在途呼叫不可中止 | **Phase 1 接受** | 还款取消计划时无法终止已发起的 AI 外呼（供应商暂不提供单次呼叫取消 API）；用户还款后仍可能接到一通催收电话。`PredictiveDialerService.filterRepaidUser()` 用于通知 AI Call 供应商移出已还号码。Phase 2 方向：评估呼叫中止接口 |
| 跨计划取消原子性间隙 | **Phase 1 接受** | REPAYMENT_RECEIVED 逐个取消用户多个活跃计划，cancel(plan-A) 与 lock(plan-B) 之间存在窗口，plan-B 的步骤可能在此窗口内执行一次触达。概率低（用户通常单计划），后置补偿（timeline 记录 + 对账）可覆盖 |

---

> MOCASA Collection System Upgrade — Phase 1 Architecture Design Document — 2026-06-23
