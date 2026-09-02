# MOCASA 催收系统升级 — Phase 1 架构设计文档

> **版本**: Phase 1 · 仅覆盖菲律宾市场  
> **日期**: 2026-09-01  
> **状态**: ✅ 已确定（系统层边界与机制要点）  
> **关联文档**: [产品需求文档 (PRD)](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)、[核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md)、[基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md)、[数据接入规格](./MOCASA催收系统升级_Phase1_数据接入规格.md)、[领域模型与数据定义](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md)、[管理后台设计文档](./MOCASA催收系统升级_Phase1_管理后台设计文档.md)

---

## 目录

- [1. 系统架构设计](#1-系统架构设计)
  - [1.1 架构总览](#11-架构总览)
  - [1.2 系统边界（北向入站）](#12-系统边界北向入站)
  - [1.3 核心引擎](#13-核心引擎)
  - [1.4 渠道编排层（南向出站）](#14-渠道编排层南向出站)
  - [1.5 数据服务层](#15-数据服务层)
    - [1.5.1 持久层 Repository](#151-持久层-repository)
    - [1.5.2 领域服务 Service（common 契约）](#152-领域服务-servicecommon-契约)
  - [1.6 应用层 collection-admin](#16-应用层-collection-admin)
- [2. 跨模块要点](#2-跨模块要点)
  - [2.1 事件编排与调度边界](#21-事件编排与调度边界)
  - [2.2 数据所有权与快照边界](#22-数据所有权与快照边界)
  - [2.3 幂等、并发与终态单调](#23-幂等并发与终态单调)
  - [2.4 事务边界与可靠投递](#24-事务边界与可靠投递)
  - [2.5 外部交互安全](#25-外部交互安全)
  - [2.6 可观测性与人工处置](#26-可观测性与人工处置)
- [3. 技术栈决策](#3-技术栈决策)
- [4. 扩展性与演进路径](#4-扩展性与演进路径)
  - [4.1 演进预留](#41-演进预留)
- [附录 A：架构决策记录 (ADR)](#附录-a架构决策记录-adr)

---



## 1. 系统架构设计

> 业务目标、功能需求、渠道选型理由、监管合规约束的产品层定义见 [产品需求文档 (PRD)](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md#1-业务背景与问题陈述)。本文档专注于系统分层边界、组件交互方式与关键架构决策。



### 1.1 架构总览

本节给出系统级视图：结构图与流程图呈现组件归属与主链路顺序。入站见 [§1.2](#12-系统边界北向入站)，引擎见 [§1.3](#13-核心引擎)，渠道见 [§1.4](#14-渠道编排层南向出站)，数据服务见 [§1.5](#15-数据服务层)，admin 见 [§1.6](#16-应用层-collection-admin)。跨模块要点见 [§2](#2-跨模块要点)。

#### 分层结构图

静态分层视图：模块边界、组件归属及主路径调用方向（接入 → 引擎 → 渠道）。

```
              ┌─────────────────────┐       ┌──────────────────────────────┐
              │ 数仓 Scheduler / Publisher │       │ Cloud Scheduler → 调度 PubSub │
              │ caseEvent / repaymentEvent │       │ dailyRoll（03:35–05:55 PHT）  │
              └──────────┬──────────┘       └────────────┬─────────────────┘
                         │ 案件 PubSub                     │ admin 订阅后转交
                         ▼                                 ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                          数据接入层 (collection-ingestion)                        │
│                                                                                  │
│  ┌─ 案件 PubSub ────────────────────────────────────────────────────────────┐   │
│  │  caseEvent      → 写投影 → CASE_INGESTED（仅首次入催；后续周期不发事件）   │   │
│  │  repaymentEvent → 写投影 → REPAYMENT_RECEIVED / CASE_BALANCE_UPDATED    │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│  ┌─ DPD 日切（只读 t_ai_collection）─────────────────────────────────────────┐   │
│  │  STAGE_CHANGED / CASE_CEASED                                              │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│  接入发布上述 5 类领域事件。context_snapshot 由引擎建计划时冻结。                  │
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
│  │  建计划 / 合规守卫 / 选步 / 推进 / 穷尽（5 SPI，由引擎回调）            │      │
│  └────────────────────────────────────────────────────────────────────────┘      │
│                              │ StepCommand                                        │
│  ┌─ 执行子层 (collection-channel, 哑管道) ────────────────────────────────┐      │
│  │  ChannelGateway → 模板渲染 → 幂等 / 熔断 → ChannelAdapter.send()        │      │
│  │  ┌─────┐ ┌────┐ ┌───────┐ ┌─────┐                                  │      │
│  │  │ SMS │ │Push│ │AI_Call│ │Email│  ← Phase 1 机器轨（4 渠道）        │      │
│  │  └─────┘ └────┘ └───────┘ └─────┘                                  │      │
│  │  Notification（SMS / Push）/ SendGrid（Email）/ Facade（AI_CALL）      │      │
│  └────────────────────────────────────────────────────────────────────────┘      │
│  注：TTS / 人工外呼由 LTH 现网独立编排。Viber / WhatsApp 等演进见 §4.1            │
│                                                                                  │
│  所有执行结果 → StepResult 回传核心引擎 + 写入 t_contact_timeline               │
└──────────────────────────────────────────────────────────────────────────────────┘
```

本图只画主路径三层。数据服务层（`collection-service` + `collection-common`）贯穿各层、不构成主链路一层，见 [§1.5](#15-数据服务层)。

#### 主链路流程图

动态流程视图：一次触达从入案到步骤推进的跨层事件顺序（happy path）。

```
  数仓 Cloud Scheduler → Publisher
       │  caseEvent / repaymentEvent（案件 PubSub）
       ▼
  接入层 (collection-ingestion)
       │  校验 / 写入投影 / 组装内部 payload
       │  首次入催 publish CASE_INGESTED
       ▼
  EventBus (Redis Stream)
       │
       ▼
  核心引擎 (engine.lifecycle)
       │  ① CASE_INGESTED：PlanFactory 建计划 (PENDING) + 注册首步
       │
       │  ── 等待 trigger_time 到期（引擎不阻塞；admin 扫表后发事件）──
       │     调度订阅 → PLAN_STEP_DUE（并列入站，同 Webhook）
       │
       │  ② PLAN_STEP_DUE：ExecutionGuard → StepResolver
       │     → ChannelGateway.dispatch(StepCommand)
       ▼
  渠道编排 (collection-channel)
       │  ChannelGateway → Adapter.send() → StepResult
       │
       ├─ 【同步】SMS / Push / Email（Notification / SendGrid）
       │      引擎 publish STEP_COMPLETED → AdvancementPolicy
       │
       └─ 【异步】AI_CALL（Facade）
              保持 STEP_EXECUTING → CHANNEL_CALLBACK / CALLBACK_TIMEOUT
              → 引擎 publish STEP_COMPLETED → AdvancementPolicy
```

`collection-admin` 在 EventBus 处汇入（调度订阅 / Webhook），与案件 PubSub 同为北向入站，见 [§1.2](#12-系统边界北向入站)、[§1.6](#16-应用层-collection-admin)。

### 1.2 系统边界（北向入站）

  

案件 Topic、调度 Topic 与 Webhook 进入订阅模块后，校验并写投影或扫表，提交后发布内部领域事件，由引擎消费。


| 管道       | 触发                                | 执行                     | 运行                                             | 内部事件                                                                                                        |
| -------- | --------------------------------- | ---------------------- | ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| 案件 Topic | 数仓 `caseEvent`                    | `collection-ingestion` | 写入 `t_ai_collection`（唯一写者）；仅首次入催发事件，后续周期只刷新投影  | `CASE_INGESTED`                                                                                             |
| 案件 Topic | 数仓 `repaymentEvent`               | `collection-ingestion` | 合并运行态到投影                                       | 结清 `REPAYMENT_RECEIVED`；部分还款 `CASE_BALANCE_UPDATED`                                                         |
| 调度 Topic | Cloud Scheduler `dailyRoll`       | admin 转交 ingestion     | 只读投影；外部同名消息 poison                             | `STAGE_CHANGED` / `CASE_CEASED`（日切）；升档亦发 `STAGE_CHANGED` |
| 调度 Topic | Cloud Scheduler `planStepDue`     | `collection-admin`     | 按 `register_job` 写入的 `trigger_time` 扫到期步骤并发布事件 | `PLAN_STEP_DUE`                                                                                             |
| 调度 Topic | Cloud Scheduler `callbackTimeout` | `collection-admin`     | 按 `timeout_time` 扫超时步骤并发布事件                    | `CALLBACK_TIMEOUT`                                                                                          |
| Webhook  | 供应商回调（Phase 1 仅 `AI_CALL`）        | `collection-admin`     | 鉴权后发布事件                                        | `CHANNEL_CALLBACK`                                                                                          |


案件字段与发布时序见 [数仓 Pub/Sub 交付契约](./数仓_PubSub交付契约.md)。调度窗口、ACK 与 Job 路由见 [基础设施 §5](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#5-定时调度cloud-scheduler--pubsub--应用订阅)。Webhook 闭环见 [§2.5](#25-外部交互安全)。

### 1.3 核心引擎

核心引擎（`engine.lifecycle`）消费内部领域事件，驱动计划状态机，并经 SPI 与 `ChannelGateway` 调度渠道触达。供应商 I/O 只经 [§1.4](#14-渠道编排层南向出站) 的 Gateway。

| 组件 | 入 → 出 | 职责 |
| --- | --- | --- |
| `EventConsumerDispatcher` | EventBus 消息 → 委托 `PlanLifecycleManager`；COMMIT 后投递派生事件 | 唯一入口：反序列化、按类型路由 |
| `PlanLifecycleManager` | 事件 → 计划 / 步骤状态前置写入 | 短事务内的状态机（创建、中断、穷尽、到期前置） |
| `StepExecutionOrchestrator` | 已提交且可执行的计划 → SPI 调用 / `ChannelGateway.dispatch` | 事务外的七步管线 |
| `PreFlightChecker` | `caseId` → 案件存活与还款校验 | 嵌在管线②；只读 `CaseService` |

协作顺序：Dispatcher 消费后进入短事务（行锁 + 状态前置）→ `COMMIT` → Orchestrator 在锁外执行七步管线。事务与投递见 [§2.4](#24-事务边界与可靠投递)。

七步管线（下文「管线①–⑦」，与 [§1.1](#11-架构总览) 主链路图中的事件序号不是同一套编号）：① 幂等 → ② PreFlight → ③ `ExecutionGuard` → ④ `StepResolver` → ⑤ dispatch（含 ⑤½ 复检）→ ⑥ 结果处理 → ⑦ 同步 / 异步分流。

5 个 SPI 定义在 `collection-common`、由 `collection-channel` 实现：`PlanFactory`、`ExecutionGuard`、`StepResolver`、`AdvancementPolicy`、`ExhaustionPolicy`。引擎经 `SpiInvoker` 调用。状态机、管线、SPI 与恢复见 [核心引擎规格 §2–§6](./MOCASA催收系统升级_Phase1_核心引擎规格.md#2-事件路由)。

### 1.4 渠道编排层（南向出站）

渠道编排是触达的执行出口。Phase 1 的策略实现与执行实现均在 `collection-channel`，与 [§1.2 北向入站](#12-系统边界北向入站) 对称，构成「EventBus 入 → 引擎 → 渠道出」闭环。

| 子层 | 入 → 出 | 职责 |
| --- | --- | --- |
| 策略（`collection-channel.strategy`） | 引擎经 `SpiInvoker` 回调 → 建计划 / 合规放行 / 选步 / 推进 / 穷尽 | 实现 `collection-common` 的 5 个 SPI；读 `context_snapshot`；`ExecutionGuard` 读实时合规计数 |
| 执行 | `StepCommand` → `StepResult` | `ChannelGateway` 经模板、熔断与 `ChannelAdapter` 调用供应商 API；以 `StepCommand` 为业务输入 |

引擎经 `ChannelGateway` 调度执行子层。模块细节：

| 关切 | 所在 |
| --- | --- |
| 策略范式与 Guard 映射 | [渠道编排规格 §2](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#2-总体定位与编排范式) / [§13](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#13-与核心引擎基础设施映射) |
| Gateway、Adapter 与完成方式 | [collection-channel 总规格 · 执行链路](./channel/MOCASA催收系统升级_Phase1_collection-channel总规格.md#执行链路) |
| 模板 ID 与 dispatch 语义 | [渠道模板清单 §2](./channel/MOCASA催收系统升级_Phase1_渠道模板清单与配置.md#2-全渠道-scriptslot-总表)；[引擎渠道执行契约](./contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md#已对齐) |

### 1.5 数据服务层

`collection-service` 为引擎、接入与 admin 提供新库 MySQL 持久化（MyBatis）与 `t_ai_collection` 只读访问。接口定义在 `collection-common`，实现按存储归属分散：

| 契约 | 用途 | 实现模块 |
| --- | --- | --- |
| `common.repository`（6 个，[§1.5.1](#151-持久层-repository)） | 新库 MySQL 表读写：计划域 3 + 事件 / 回调审计 3 | `collection-service` |
| `common.service`（5 个，[§1.5.2](#152-领域服务-servicecommon-契约)） | 案件表只读、Redis 键、外部系统桥接 | `collection-service` / `collection-engine` / `collection-channel` |

表结构见 [领域模型 §1.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#12-表级契约矩阵)；方法全集见 [基础设施 §6](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#6-持久层与跨存储一致性)。模板 / 合规规则 / offer 无 common 契约，见 [§1.4](#14-渠道编排层南向出站)。BigQuery 只读冷层属于后台分析，见 [§3](#3-技术栈决策)。

#### 1.5.1 持久层 Repository

引擎与 admin 对 MySQL 的读写经 Repository 接口完成。Mapper 仅供 `collection-service` 内 Repository 实现类调用；引擎只编译依赖 `collection-common`，更换 ORM 或案件表映射时引擎不必重编。

落地路径（三段固定命名，据此定位文件）：`common.repository.XxxRepository`（契约）→ `service.repository.XxxRepositoryImpl`（实现）→ `service.mapper.*Mapper`（SQL）。

| Repository | 覆盖表 | 引擎 | 用途 |
| --- | --- | --- | --- |
| `ContactPlanRepository` | `t_contact_plan` + `t_contact_plan_step` | 生命周期写锁、停摆检测 | 计划与步骤持久化与扫描；admin 到期 / 超时扫表、计划只读 |
| `TimelineRepository` | `t_contact_timeline` | 写触达结果、读近期历史 | 触达时间线；admin 只读 |
| `DecisionLogRepository` | `t_decision_log` | 管线④只写 | 渠道 / 话术决策留痕；写入失败时触达继续 |
| `EventOutboxRepository` | `t_event_outbox` | 同事务落库、投递后销账 | 派生事件可靠投递 |
| `EventDlqRepository` | `t_event_dlq` | 重试耗尽入列 | 死信持久化；admin 受控重放 |
| `ChannelCallbackAuditRepository` | `t_channel_callback_audit` | — | 供应商原始回调留痕，与 timeline 次数分开；admin Webhook 只写 |




#### 1.5.2 领域服务 Service（common 契约）

契约在 `collection-common/service`，共 5 个，下表即全集。「载体」是该服务访问的存储或外部系统；落库一律走 [§1.5.1](#151-持久层-repository) 的 Repository。调用方中的管线步号对应 [§1.3](#13-核心引擎) 七步。本地测试替身见测试文档与 HANDOFF。

| Service | 调用方 | 职责 | 载体 / 读写 | 实现模块 |
| --- | --- | --- | --- | --- |
| `CaseService` | 管线②、`ingestion` 日切 | 案件存活与还款实时校验（并带出渲染用 `dpd` / 余额）；日切 keyset 扫描。入案快照由接入层冻结 | `t_ai_collection`，只读 | `collection-service` |
| `ProfileService` | Phase 1 主链路未调用 | 画像聚合；联系方式由 `caseEvent` 的 `borrower` / `device` 带出。演进见 [§4.1](#41-演进预留) | 只读；来源待单独接入 | — |
| `IdempotencyService` | 管线①、渠道执行层 | 步骤与触达的重复执行拦截（[§2.3](#23-幂等并发与终态单调)） | Redis `collection:lock:plan:` 等键 + TTL，跨实例读写 | `collection-engine` |
| `ComplianceCounterService` | 渠道 `ExecutionGuard`（管线③） | 单渠道与跨渠道日配额的原子占用；Redis 不可用时 fail-close | Redis `collection:compliance:` 计数器，读写 | `collection-channel` |
| `PredictiveDialerService` | 引擎 `REPAYMENT_RECEIVED` 处理 | 结清后请求 AI Call 合作方移出排队（`filterRepaidCase`）；失败仅告警，计划仍取消 | AI Call 合作方 HTTP，无本地存储 | `collection-channel` |


> 生产就绪差集见 [HANDOFF D.1](../HANDOFF.md#d1-生产就绪差集登记)；本地测试替身只在测试文档与代码配置中维护。



### 1.6 应用层 collection-admin
<a id="17-应用层-collection-admin"></a>

`collection-admin` 是应用层：北向承接供应商 Webhook 与调度订阅并发布内部事件，南向提供运营 REST 与配置热更新。

| 方向 | 能力 | 边界 |
| --- | --- | --- |
| 北向入站 | Webhook → `CHANNEL_CALLBACK`；调度订阅 → `PLAN_STEP_DUE` / `CALLBACK_TIMEOUT`；`dailyRoll` tick 转交 ingestion | 扫描到期 / 超时步骤，鉴权后发布事件；案件投影由 ingestion 写入。见 [§1.2](#12-系统边界北向入站) |
| 南向 REST | 案件 / 计划只读查询；话术与计划模板热更新；Holdout；异常队列 ACK；合规冻结 | 计划状态迁移与渠道触达由引擎执行。写范围以管理后台设计文档为准 |
| 配置来源 | Phase 1：Nacos 承载策略 / 合规 / 渠道开关；后台可热更新 SMS/Push 话术与计划模板 | 完整「所有配置经后台、免发版」⏳ 待深入讨论 · Phase 1.5（默认先 Nacos/发版；见 [设计文档 §6 / §10](./MOCASA催收系统升级_Phase1_管理后台设计文档.md#6-配置管理与热更新)） |

信息架构、权限、页面闭环与配置热更新以 [管理后台设计文档 §4 / §6](./MOCASA催收系统升级_Phase1_管理后台设计文档.md#4-信息架构总览) 为模块 SSOT；启动与排障见 [管理后台操作手册 §2](./MOCASA催收系统升级_Phase1_管理后台操作手册.md#2-启动与登录) / [§7](./MOCASA催收系统升级_Phase1_管理后台操作手册.md#7-故障排查)。

---

## 2. 跨模块要点
<a id="16-关键架构机制"></a>

读完第 1 章的模块边界后，本章回答它们如何配合。各节开头的**要点**是必须守住的设计，不是操作步骤；后面的条目是展开。超时值、伪代码与指标名见各模块规格；渠道专项见 [§1.4](#14-渠道编排层南向出站)；总线可靠性、DLQ 与背压见 [基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md)。

**要点目录**

| 要点 | 要解决的问题 | 主责层 |
| --- | --- | --- |
| [2.1 事件编排与调度边界](#21-事件编排与调度边界) | 谁触发、谁执行业务 | admin、引擎 |
| [2.2 数据所有权与快照边界](#22-数据所有权与快照边界) | 数据由谁写、何时冻结 | 接入、引擎、渠道 |
| [2.3 幂等、并发与终态单调](#23-幂等并发与终态单调) | 重复和竞态如何收敛 | 接入、引擎、渠道 |
| [2.4 事务边界与可靠投递](#24-事务边界与可靠投递) | 状态提交后如何不丢事件 | 引擎、基础设施 |
| [2.5 外部交互安全](#25-外部交互安全) | 外部依赖失败如何受控 | 引擎、渠道、admin |
| [2.6 可观测性与人工处置](#26-可观测性与人工处置) | 异常如何发现与处置 | 全链路 |

### 2.1 事件编排与调度边界
<a id="161-事件编排与调度边界"></a>

**要点**：模块间经 EventBus 异步协作。生产调度只有 Cloud Scheduler → 调度 Topic → 应用订阅，不与 `@Scheduled` 双入口。调度扫表并发布内部事件，业务决策与渠道 I/O 在 Consumer 侧由引擎与渠道完成。

- 调度线程与 Consumer 线程隔离；所有业务事件经 Redis Stream 流转。`CollectionEventBus` 定义于 `collection-common`（生产 `RedisStreamEventBusImpl`，本地 CI `InMemoryEventBus`）；PEL 重投、DLQ 与 Consumer 背压见 [基础设施 §2.2 / §3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#22-生产消费拓扑线程职责与背压)。
- 事件路由以 [核心引擎规格 §2.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#21-事件路由表ssot) 为准。调度管道见 [§1.2](#12-系统边界北向入站)，订阅模块见 [§1.6](#16-应用层-collection-admin)。

### 2.2 数据所有权与快照边界
<a id="162-数据所有权与快照边界"></a>

**要点**：接入层是案件投影（`t_ai_collection`）唯一写入者；计划、步骤、触达时间线与派生事件由引擎写入；渠道只回 `StepResult`，不写业务库。计划的静态决策使用创建时冻结的 `context_snapshot`。

- 完整快照在首次入案建计划时冻结；还款更新运行态或中断计划，阶段变化由 `dailyRoll` 产生。
- 实时还款状态、合规计数和对外文案的日变字段在冻结后仍可刷新；字段口径见 [数据接入规格 §3](./MOCASA催收系统升级_Phase1_数据接入规格.md#3-案件消息处理主链路)，快照定义见 [领域模型 §4.4](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#44-contextsnapshot决策上下文快照)。
- admin 可扫表、写回调审计、热更新话术/计划模板、重放 DLQ，见 [§1.6](#16-应用层-collection-admin)；计划状态迁移与触达由引擎执行。

### 2.3 幂等、并发与终态单调
<a id="163-幂等并发与终态单调"></a>

**要点**：至少一次投递下同一步至多触达一次；同一计划同时只允许一个 Consumer 修改，终态不可逆。

- 接入处理、内部事件、步骤和渠道各自去重；Redis 键与 TTL 见 [基础设施 §4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#4-运行时状态redis-kv)。
- 引擎以行锁、状态机幂等转移和「先写先赢」的终态单调收敛竞争。Pre-flight 通过到渠道发出之间存在秒级空窗（[核心引擎规格 §3.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#32-并发与一致性模型)「有界越界」）：Phase 1 接受已发出触达无法撤回，由管线② / ⑤½ 复检减轻误推进。紧急拦截见 [§4.1](#41-演进预留)。

### 2.4 事务边界与可靠投递
<a id="164-事务边界与可靠投递"></a>

**要点**：短事务只覆盖锁与状态前置；渠道 I/O 在事务外；状态迁移与派生事件必须可恢复地关联。

- 调用顺序为 `Dispatcher → Manager（事务）→ COMMIT → Orchestrator（I/O）`，避免慢渠道扩大锁窗口。
- 计划派生事件由 Outbox 兜底，接入事实由 Inbox 补发；恢复细节见 [核心引擎规格 §7.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#72-派生事件可靠投递) 与 [数据接入规格 §3.4](./MOCASA催收系统升级_Phase1_数据接入规格.md#34-内部事件发布与-pending-补发)。

### 2.5 外部交互安全
<a id="165-外部交互安全"></a>

**要点**：SPI 调用必须有硬超时；异步语音经回调或超时收敛到可推进状态。

- SPI 的超时与失败策略按调用影响域分级，取值见 [§1.3](#13-核心引擎)。
- 仅 `AI_CALL` 走回调闭环：Webhook 鉴权后发布 `CHANNEL_CALLBACK`，超时由 `CALLBACK_TIMEOUT` 收敛。供应商标识、签名和步骤循环见 [核心引擎规格 §4.3](./MOCASA催收系统升级_Phase1_核心引擎规格.md#43-步骤执行循环)。
- 结清可取消计划并 `filterRepaidCase` 移出排队；已发起的呼叫仍可能打出。呼叫中止 API 见 [§4.1](#41-演进预留)。

### 2.6 可观测性与人工处置
<a id="166-可观测性与人工处置"></a>

**要点**：静默跳过、降级、重试、DLQ 与停摆都必须可观测；外部触达的补发由人工处置触发。

- 结构化日志以 `eventId`、`caseId`、`planId`、`stepId` 串联；指标与告警以 [基础设施 §7](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#7-配置管理与可观测性) 为准。
- Outbox 失败、DLQ 或停摆触发人工处置；`StuckPlanReaper` 检测并告警，由人工决定是否重建或重发。

---

## 3. 技术栈决策
<a id="2-技术栈决策"></a>

下表是 Phase 1 **当前已选栈**，不是待办清单。


| 维度    | 旧系统             | Phase 1                            | 说明                                                                                                                                  |
| ----- | --------------- | ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 运行时   | Java 8          | Java 8                             | 与现网一致，避免一次升级运行时                                                                                                                     |
| 框架    | Spring Boot 2.1 | Spring Boot 2.7.18                 | Boot 3.2+ / Java 17 演进见 [§4.1](#41-演进预留)                                                                                            |
| 构建/部署 | —               | Maven 多模块单体；Pilot 单活跃实例            | Redis 使后续扩实例不改业务语义；模块划分见 [§1.1](#11-架构总览)                                                                                           |
| 配置    | 本地 properties   | Nacos + 本地 YAML                    | 本地 `application-*.yml`；生产 / Pilot 由 admin 引入 Nacos                                                                                  |
| 安全    | Shiro           | Session + 拦截器；Webhook HMAC         | 管理后台 REST 用 `AdminAuthInterceptor`；Webhook 生产/Pilot 强制验签，引擎不依赖后台登录                                                                  |
| 持久化   | MySQL + MyBatis | MySQL 8 + MyBatis                  | 沿用现网 MyBatis 访问路径，降低迁移面                                                                                                             |
| Redis | 缓存/限流           | Stream 事件总线 + KV                   | 生产依赖：事件、幂等、合规计数、日切游标；见 [基础设施 §3 / §4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#3-事件总线redis-stream)                                                                 |
| 外部消息  | GCP PubSub（同步）  | 两条隔离 Pub/Sub                       | 案件 Topic → `collection-ingestion`；调度 Topic → `collection-admin`                                                                     |
| 分析冷层  | —               | BigQuery（只读，规划）                    | 后台趋势看板 / 渠道参考 SQL；**不是**案件入站源，也非 `collection-service` 职责                                                                            |
| 调度    | XXL-Job         | Cloud Scheduler → 调度 Topic → 应用订阅  | Trigger-to-Event，见 [§2.1](#21-事件编排与调度边界)；不复用 XXL-Job（常驻内网，避免执行器注册与入站端口）                                                          |
| 流程编排  | 无               | 自建状态机 + SPI                        | 见 [§1.3](#13-核心引擎)                                                                                                                  |
| 可观测   | 无               | Actuator + Micrometer → Prometheus | Grafana / 告警路由 ⏳ 待深入讨论（默认跳转独立 Grafana，嵌入成本高，与 [设计文档 Q4](./MOCASA催收系统升级_Phase1_管理后台设计文档.md#12-开放问题) 一致）；原则见 [§2.6](#26-可观测性与人工处置) |


---



## 4. 扩展性与演进路径
<a id="3-扩展性与演进路径"></a>



### 4.1 演进预留
<a id="31-演进预留"></a>

本文所有 Phase 2 项只在此登记。Phase 1 以替换实现或补接口为主，不改主链路边界。


| 项                          | Phase 1                                                                                          | Phase 2                                                                                 |
| -------------------------- | ------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------- |
| Viber / WhatsApp           | `ChannelAdapter` 占位                                                                              | 签约后接入统一适配层                                                                              |
| AI_CALL 呼叫中止               | 仅 `filterRepaidCase` 移出排队                                                                        | 评估单次呼叫取消 API                                                                            |
| 渠道对账扫描                     | Webhook + `CALLBACK_TIMEOUT`                                                                     | 按供应商补齐已发未落盘结果                                                                           |
| 独立画像兜底                     | `ProfileService` Phase 1 主链路未调用                                                                  | 单独画像源后再接入                                                                               |
| PTP / 争议冻结                 | 规格预留，不交付                                                                                         | 到期事件与后台冻结操作                                                                             |
| 减免 offer                   | snapshot 字段占位（[PRD §4.2](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md#42-phase-1-out-of-scopephase-2-实现)） | 动态 offer 计算                                                                             |
| LLM 决策 / 计划 / 话术 / 续建 / 质检 | 现有 SPI 内部可替换点；`TranscriptService` 预留                                                             | 配置切换或 A/B                                                                               |
| Pre-flight 竞态空窗            | Phase 1 接受秒级空窗（[§2.3](#23-幂等并发与终态单调)）                                                      | Redis 临界标记 + 紧急拦截                                                                       |
| 运行演进                       | Nacos；Java 8 / Boot 2.7；模块可独立编译                                                                  | `t_system_property` 审计；Boot 3 / Java 17；按需拆 engine / channel；替换 `CollectionEventBus` 实现 |


---



## 附录 A：架构决策记录 (ADR)

> 系统与渠道处置。运行要点见 [§2](#2-跨模块要点)；Phase 2 演进见 [§4.1](#41-演进预留)；产品层决策见 [PRD §9](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md#9-产品层已拍板决策)。


| 系统/渠道              | 处置          | 决策说明                                                                     |
| ------------------ | ----------- | ------------------------------------------------------------------------ |
| Issabel 手动拨打       | **废弃**      | 通话数据不回流，由 LTH 预测式外呼替代                                                    |
| Saiduo AI 机器人      | **废弃**      | 仅印度活跃，菲律宾未启用                                                             |
| WizAI AI 机器人       | **废弃**      | 代码已停用                                                                    |
| Microsip 自动拨打      | **废弃**      | 功能合并到统一触达引擎                                                              |
| 到期前通知（信贷主系统）       | **接管**      | 新系统接管发送，信贷系统仅推送案件数据                                                      |
| LTH 平台             | **保留**      | TTS / 人工外呼由 LTH 现网独立编排，与本系统无交互。机器轨 `AI_CALL` 经独立合作方 Facade 对接，接口不得绑定 LTH |
| WSCRM WhatsApp     | **Phase 2** | 见 [§4.1](#41-演进预留)                                                       |
| collection_rebuild | **升级**      | 保留数据模型，重构架构                                                              |


---

> MOCASA Collection System Upgrade — Phase 1 Architecture Design Document — 2026-09-01

