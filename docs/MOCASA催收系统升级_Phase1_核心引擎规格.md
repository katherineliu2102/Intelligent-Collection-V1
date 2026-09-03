# MOCASA 催收系统升级 — Phase 1 核心引擎规格

> **版本**: Phase 1 · 仅覆盖菲律宾市场  
> **日期**: 2026-09-03  
> **状态**: ✅ 已确定（事件路由、状态机、七步管线、SPI SSOT）  
> **关联文档**: [产品需求文档 (PRD)](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)、[架构设计文档](./MOCASA催收系统升级_Phase1_架构设计文档.md)、[基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md)、[领域模型 §2.6 / §6](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#6-eventpayload-字段定义)、[渠道总规格 §3.3](./channel/MOCASA催收系统升级_Phase1_collection-channel总规格.md#33-channel_callback-事件-payload)

---

## 目录

- [1. 引擎结构与边界](#1-引擎结构与边界)
  - [1.1 核心组件与职责](#11-核心组件与职责)
  - [1.2 模块边界与调用全景](#12-模块边界与调用全景)
- [2. 事件路由](#2-事件路由)
  - [2.1 事件路由表（SSOT）](#21-事件路由表ssot)
  - [2.2 生命周期派生总览](#22-生命周期派生总览)
- [3. 运行时执行模型](#3-运行时执行模型)
  - [3.1 线程隔离（Trigger-to-Event）](#31-线程隔离trigger-to-event)
  - [3.2 并发与一致性模型](#32-并发与一致性模型)
- [4. 计划生命周期与状态机](#4-计划生命周期与状态机)
  - [4.1 状态定义](#41-状态定义)
  - [4.2 计划创建](#42-计划创建)
  - [4.3 步骤执行循环](#43-步骤执行循环)
  - [4.4 中断处理](#44-中断处理)
  - [4.5 穷尽续建](#45-穷尽续建)
  - [4.6 部分还款余额更新](#46-部分还款余额更新)
  - [4.7 PTP 到期处理（Phase 2 预留）](#47-ptp-到期处理)
  - [4.8 状态转换](#48-状态转换)
- [5. 步骤执行管线](#5-步骤执行管线)
  - [5.1 execute_step 执行骨架](#51-execute_step-执行骨架)
- [6. SPI 接口契约](#6-spi-接口契约)
  - [6.1 接口职责与调用位置](#61-接口职责与调用位置)
  - [6.2 返回值与实现约束](#62-返回值与实现约束)
  - [6.3 共享 DTO 定义](#63-共享-dto-定义)
- [7. 容错与异常恢复](#7-容错与异常恢复)
  - [7.1 统一处置规则](#71-统一处置规则)
  - [7.2 派生事件可靠投递](#72-派生事件可靠投递)
  - [7.3 渠道调用后的部分成功](#73-渠道调用后的部分成功)
  - [7.4 停摆计划检测](#74-停摆计划检测)

---



## 1. 引擎结构与边界

本文是引擎内部行为的 SSOT：事件路由、并发模型、状态机、执行管线、SPI 契约与恢复机制均以本文为准。架构文档只说明跨模块边界与机制要点，不重复本文的伪代码、状态表、超时值或指标名。

本节交代引擎由哪些组件构成（§1.1）、与外部模块的边界与调用全景（§1.2），为后续事件路由（[§2](#2-事件路由)）、运行时执行模型（[§3](#3-运行时执行模型)）与计划生命周期（[§4](#4-计划生命周期与状态机)）提供结构地图。

### 1.1 核心组件与职责

`EventConsumerDispatcher` 是核心引擎的**唯一入口**——从 Redis Stream 消费事件，反序列化后按类型路由；其余三个核心类均为内部协作组件，不对外暴露调用。


| 类                           | 职责边界              | 拥有的逻辑                                                              |
| --------------------------- | ----------------- | ------------------------------------------------------------------ |
| `EventConsumerDispatcher`   | 事件消费 + 路由 + 提交后投递 | 反序列化、按类型路由、委托 `PlanLifecycleManager`（行锁与终态拦截在其短事务内）、COMMIT 后发布派生事件 |
| `PlanLifecycleManager`      | 计划级生命周期决策         | §4 全部伪代码（创建/中断/穷尽），事务内状态前置写入                                       |
| `StepExecutionOrchestrator` | 步骤级执行管线           | §5 全部伪代码（七步骨架），在非事务上下文中运行                                          |
| `PreFlightChecker`          | 系统级实时守卫           | 实时查 DB 确认案件存活；由 Orchestrator 调用，**不直接消费事件**                        |


**调用链路**：`Dispatcher` → `Manager`（事务内）→ COMMIT → `Orchestrator`（事务外）；`PreFlightChecker` 嵌在 Orchestrator 的 `execute_step` 第②步。

### 1.2 模块边界与调用全景

`StepExecutionOrchestrator` 按 [§5](#5-步骤执行管线) 七步顺序执行；③④⑤ 经 SPI / `ChannelGateway` 进入渠道编排，①②⑥⑦ 在引擎内完成（契约见 [§6](#6-spi-接口契约)）：

```mermaid
flowchart LR
    subgraph engine["核心引擎 · StepExecutionOrchestrator"]
        S1["① 幂等"]
        S2["② PreFlight"]
        S6["⑥ 降级"]
        S7["⑦ 分流"]
    end

    subgraph strategy["渠道编排 · 策略子层 (engine.strategy)"]
        S3["③ 合规<br/>ExecutionGuard"]
        S4["④ 解析<br/>StepResolver"]
    end

    subgraph exec["渠道编排 · 执行子层 (collection-channel)"]
        S5["⑤ 渠道<br/>ChannelGateway"]
    end

    S1 --> S2 --> S3 --> S4 --> S5 --> S6 --> S7
```



> 七步顺序与 [§5](#5-步骤执行管线) 一致；⑤ 与 ⑥ 之间另有 ⑤½ 取消复检（引擎内，本图从略）。

---



## 2. 事件路由

本节是事件的**声明性目录**：[§2.1](#21-事件路由表ssot) 路由表是 Dispatcher 消费的全部外部事件的唯一权威清单（SSOT），[§2.2](#22-生命周期派生总览) 给出由路由表派生的生命周期全景视图。事件如何被多线程承载与并发约束见 [§3](#3-运行时执行模型)。

### 2.1 事件路由表（SSOT）

下表是 Dispatcher 消费并路由的**事件唯一权威清单**（Phase 1 共 11 行）：处理动作与详见均以本表为准；「生命周期域」列与 [§2.2](#22-生命周期派生总览) 四块对齐（①创建 / ②运行中 / ③收尾 / ④中断）。


| 事件                     | 生命周期域       | 引擎侧处理动作                                     | 详见                                 |
| ---------------------- | ----------- | ------------------------------------------- | ---------------------------------- |
| `CASE_INGESTED`        | ① 创建        | 仅 owner 对账（或水位已是当日的迟到补建）后到达；`owner_date` 须为当日且非结清/停催 → 匹配模板创建计划 | [§4.2](#42-计划创建)                   |
| `CASE_OWNER_RECONCILED`| ④ 中断        | `ownerAction=LEAVE`：取消该案件活跃计划，`cancel_reason=ROUTED_TO_LEGACY`，不再续建 | [§4.4](#44-中断处理)                   |
| `STAGE_CHANGED`        | ① 创建 + ④ 中断 | 须当日 owner 水位已成功且该案 `owner_date=当日`；取消旧阶段活跃计划 → 为新阶段创建计划 | [§4.2](#42-计划创建)、[§4.4](#44-中断处理)  |
| `REPAYMENT_RECEIVED`   | ④ 中断        | **整笔 loan 全额结清**：取消该案件活跃计划 + 清理已注册 Job      | [§4.4](#44-中断处理)                   |
| `CASE_BALANCE_UPDATED` | ② 运行中更新     | **部分还款**：刷新该案件活跃计划快照的运行态金额与下一期提醒字段；不改 stage | [§4.6](#46-部分还款余额更新)               |
| `PLAN_STEP_DUE`        | ② 步骤循环      | 水位不是当日或 `owner_date≠当日` → **跳过、不取消计划**；否则按状态分流执行 | [§4.3](#43-步骤执行循环)、[§5](#5-步骤执行管线) |
| `CHANNEL_CALLBACK`     | ② 步骤循环      | 更新步骤结果 → 发布 `STEP_COMPLETED`                | [§4.3.3](#433-channel_callback)    |
| `CALLBACK_TIMEOUT`     | ② 步骤循环      | 水位不是当日或 `owner_date≠当日` → **跳过**；否则回调超时 → 标 `FAILED` → 发布 `STEP_COMPLETED` | [§4.3.4](#434-callback_timeout)    |
| `STEP_COMPLETED`       | ② 步骤循环      | 推进决策：注册下一步 / 计划完成 / 发布穷尽                    | [§4.3.2](#432-step_completed)      |
| `PLAN_EXHAUSTED`       | ③ 收尾        | 须当日 owner 水位已成功且该案 `owner_date=当日`；否则跳过不续建 | [§4.5](#45-穷尽续建)                   |
| `CASE_CEASED`          | ④ 中断        | D+91 完全停催：取消该案件活跃计划，**不再续建**（停催终态）          | [§4.4](#44-中断处理)                   |


所有事件经 Dispatcher 消费后遵循**统一的并发前置流程**（行锁 → 终态拦截 → 事务边界），该契约见 [§3.2](#32-并发与一致性模型)，本节不重复。事件的产生来源（外部上游 / 引擎链式 / 定时 Job）见 [领域模型 §6.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#62-逐事件-payload-字段)（发布者列）；链式发布的触发条件以 [§4.3](#43-步骤执行循环) / [§4.5](#45-穷尽续建) / [§5](#5-步骤执行管线) 伪代码为 SSOT。

### 2.2 生命周期派生总览

下图把路由表 10 行按其**生命周期域**压缩为四类协作关系，并用边表达正常推进顺序与横切关系，仅作全景导览（引擎侧视角，不涉及上游产生方式）；事件归属与处理动作以路由表为准，此处不展开单个事件语义。

```mermaid
flowchart TB
    D["EventConsumerDispatcher · 唯一入口<br/>锁 → 检终态 → 事务内前置写"]

    subgraph plan["PlanLifecycleManager · 计划级 §4"]
        P1["① 创建：CASE_INGESTED / STAGE_CHANGED"]
        P3["② 运行中：PLAN_STEP_DUE / CHANNEL_CALLBACK / CALLBACK_TIMEOUT / STEP_COMPLETED / CASE_BALANCE_UPDATED"]
        P4["③ 收尾：PLAN_EXHAUSTED"]
        P2["④ 中断：REPAYMENT_RECEIVED / STAGE_CHANGED / CASE_CEASED / CASE_OWNER_RECONCILED"]
    end

    subgraph step["StepExecutionOrchestrator · 步骤级 §5（事务外）"]
        O["execute_step → PreFlightChecker"]
    end

    D --> P1
    P1 -->|"建计划"| P3
    P3 -->|"穷尽"| P4
    P3 -->|"PLAN_STEP_DUE 提交后"| O
    D -. "中断事件" .-> P2
    P2 -. "取消任意非终态计划" .-> P3
```



> 读图：**实线**＝正常生命周期推进（入口 → ①创建 → ②循环 → ③收尾）与进入步骤管线；**虚线**＝横切中断（④可作用于任意非终态计划）。完整状态流转与竞态见 [§4.8](#48-状态转换)，本图不重复。

---



## 3. 运行时执行模型

本节定义事件的**操作性执行模型**：[§3.1](#31-线程隔离trigger-to-event) 调度线程与 Consumer 线程如何隔离，[§3.2](#32-并发与一致性模型) 并行消费下如何保证一致性。两者构成同一因果链——Consumer 线程池的并行正是并发控制的前提。

### 3.1 线程隔离（Trigger-to-Event）

[§2.1](#21-事件路由表ssot) 路由表中的 `PLAN_STEP_DUE` 由调度线程**生产**，由 Consumer 线程池**消费**；其余业务事件仅由 Consumer 消费。两线程池严格隔离，杜绝调度与 I/O 密集操作耦合：

```
Cron 扫表 ──XADD──→ Redis Stream ──XREADGROUP──→ Consumer 执行业务
```


| 维度   | **Cron 调度线程**                       | **Consumer 业务线程**       |
| ---- | ----------------------------------- | ----------------------- |
| 线程池  | 调度订阅消费线程（Cloud Scheduler → Pub/Sub） | Redis Stream 消费线程池      |
| 职责   | 扫表发现到期步骤 → `XADD` 发事件               | `XREADGROUP` 消费 → 引擎全链路 |
| 耗时约束 | 毫秒级返回；**禁止**渠道 I/O 等阻塞              | 允许阻塞；供应商变慢仅占用本池         |


**事件分工**（横切维度，与上表正交）：


| 事件                        | Cron                     | Consumer                            |
| ------------------------- | ------------------------ | ----------------------------------- |
| `PLAN_STEP_DUE`           | **生产**（扫 `trigger_time`） | **消费**（分流 → `execute_step`）         |
| `CALLBACK_TIMEOUT`        | **生产**（扫 `timeout_time`） | **消费**（标 FAILED → `STEP_COMPLETED`） |
| 其余 8 种（`CASE_INGESTED` 等） | 不参与                      | 消费 + 执行                             |


> 两池不共享线程；生产线程池、背压与 PEL 的实现参数以 [基础设施 §2.2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#22-生产消费拓扑线程职责与背压) 为准。Consumer 并行消费正是 [§3.2](#32-并发与一致性模型) 的前提。



### 3.2 并发与一致性模型

Consumer 并行消费时，同一计划可能同时收到「步骤到期触达」与「还款取消」等事件。典型事故是**还款已取消计划却仍发出触达**。下表归纳五类风险；**本节详述 ① 及其代价、②**；③ 见 [§4.4](#44-中断处理)，④ 见 [§5](#5-步骤执行管线) ②⑤½，⑤ 见 [§7.2](#72-派生事件可靠投递)。

#### 五类一致性风险（总览）


| #   | 风险         | 应对                   | 详述                              |
| --- | ---------- | -------------------- | ------------------------------- |
| ①   | **并发写坏**   | 行锁串行 + 锁内禁 I/O       | **本节 ↓**                        |
| ②   | **重复执行**   | `XACK` / DLQ + 步骤幂等键 | **本节 ↓**；步骤级见 [§5](#5-步骤执行管线) ① |
| ③   | **乱序覆盖**   | 终态先写先赢               | [§4.4](#44-中断处理)                |
| ④   | **迟到真相**   | I/O 前后复检             | [§5](#5-步骤执行管线) ②⑤½             |
| ⑤   | **派生事件丢失** | 事件与状态迁移同事务落发件箱       | [§7.2](#72-派生事件可靠投递)            |


> **③ 要点**：`REPAID > CEASED > STAGE_UPGRADE > 非终态` 为语义/审计参考，非运行时覆盖规则。投诉/争议冻结为 Phase 2 能力，不属于本阶段状态机。



#### ① 防并发写坏：串行锁 + 锁内轻量

**串行锁**：同一 `plan_id` 的并发事件在 `SELECT FOR UPDATE` 处排队，一次只让一个 Consumer 改计划。
**锁内轻量**：持锁期间只做状态校验 + 前置写（如 → `STEP_EXECUTING` / `PLAN_CANCELLED`），COMMIT 后立即释放；渠道 I/O、远程调用等慢操作一律放到锁外。

所有事件经 Dispatcher 消费后，第一步即进入上述短事务——同一计划的并发事件被串行化，锁窗口保持毫秒级，不会因渠道超时导致锁堆积。

**① 的代价（有界越界）**：锁外 I/O 期间计划可能被取消，**已发出的触达无法撤回**（秒级窗口）。Phase 1 接受此取舍：计划仍取消、后续不再执行；写入补偿 timeline / 告警。④ 多级复检（[§5](#5-步骤执行管线) ②⑤½）可减轻误推进，但不能消除已发出触达。

#### ② 防重复执行：消费层幂等

Redis Stream 的消费语义保证：


| 场景             | 行为                                     | 典型原因                                                                                  |
| -------------- | -------------------------------------- | ------------------------------------------------------------------------------------- |
| 处理成功           | `XACK`，消息不再投递                          | —                                                                                     |
| 处理失败（**可重试**）  | 不 ACK → pending list → 自动重投递           | DB 短暂不可用、锁等待超时、计划级 SPI 超时、进程崩溃于 ACK 前                                                 |
| 处理失败（**不可重试**） | 跳过 ACK → 直接 DLQ + 告警                   | payload 反序列化失败（畸形消息，重投必败）                                                             |
| 可重试但达投递上限      | DLQ + 告警（毒消息）                          | 代码缺陷或数据异常导致持续失败（默认上限 5 次，见 [基础设施附录 A.2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a2-引擎与事件总线)） |
| 重复投递到达         | 步骤 `idempotency_key` SETNX 吸收；终态计划直接退出 | Stream 重投或并发重复消费                                                                      |


消费 ACK 语义属于路由/线程层；`idempotency_key` 的具体实现见 [§5](#5-步骤执行管线) ①，DLQ 配置见 [基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md)。

#### 时序例证（①②④ 交织）

`PLAN_STEP_DUE`（执行）与 `REPAYMENT_RECEIVED`（中断）争锁的典型场景：A 先获锁并进入锁外 I/O，B 在 I/O 窗口内取消计划，A 返回后 ④ 复检丢弃结果；触达可能已发出（有界越界）。若 B 先获锁，A 见终态静默退出（② 终态吸收）。

```
Consumer-A (PLAN_STEP_DUE)           Consumer-B (REPAYMENT_RECEIVED)
    │                                      │
    │── SELECT FOR UPDATE plan ──→         │── SELECT FOR UPDATE plan (阻塞)
    │   → status = STEP_EXECUTING          │
    │── COMMIT ──→                          │── 获取锁 → PLAN_CANCELLED → COMMIT
    │   渠道 I/O 进行中...                  │
    │   返回后重读发现 PLAN_CANCELLED        │
    │   → 静默丢弃，写补偿日志               │
```

更多竞态分支（中断乱序、重复投递、升档 vs 还款等）见 [§4.4](#44-中断处理) / [§5](#5-步骤执行管线)；完整覆盖由集成测试保证。

---



## 4. 计划生命周期与状态机

本节按计划生命周期展开：先定义状态词汇表，再按时间顺序列出从创建到终态的流转，最后以状态转换总表和转换图固化规则。步骤级横向协作见 [§5](#5-步骤执行管线)，即 §4.3「执行步骤」的内部展开；部分还款的余额快照更新不构成状态迁移，见 [§4.6](#46-部分还款余额更新)。

> **边界**：上游消息到领域事件的归属、`caseId=loanId`、结清/部分还款判定、DPD/停催准入与 payload 校验，以 [数据接入规格](./MOCASA催收系统升级_Phase1_数据接入规格.md) 为 SSOT；本节只定义引擎收到事件后的计划状态机行为，并保留必要的防御性校验。



### 4.1 状态定义

计划级状态机共 **6 态**（4 非终态 + 2 终态），已覆盖引擎管辖的完整生命周期；步骤级状态（`SCHEDULED` / `EXECUTING` / `COMPLETED` 等）见 [领域模型](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md)，不在此表重复。


| 状态               | 类型     | 语义                                                                                              |
| ---------------- | ------ | ----------------------------------------------------------------------------------------------- |
| `PENDING`        | 非终态    | 计划刚创建，尚未执行任何步骤，等待首步 `trigger_time`                                                              |
| `STEP_SCHEDULED` | 非终态    | 上一步已结束，下一步 Job 已注册，等待到期                                                                         |
| `STEP_EXECUTING` | 非终态    | 当前步骤执行中（渠道发送 / 等待异步回调）                                                                          |
| `STEP_WAITING`   | 非终态    | 消息类渠道已发出，观察期内等待用户响应                                                                             |
| `PLAN_COMPLETED` | **终态** | 本计划收口。仍在催、未结清、未停催时，步骤走完须经 [§4.5](#45-穷尽续建)，不得在此直接停催；仅穷尽 `COMPLETE`、或引擎判定已不在催时由推进路径落入本态。还款/停催取消走 `PLAN_CANCELLED`（[§4.4](#44-中断处理)）。 |
| `PLAN_CANCELLED` | **终态** | 被中断取消；`cancel_reason` 枚举见 [领域模型 §2.7](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#27-cancelreason计划取消原因) |


> Phase 1 引擎经事件总线写入的 `cancel_reason` 仅 `REPAID` / `STAGE_UPGRADE` / `CEASED` / `ROUTED_TO_LEGACY`（见 [§4.4](#44-中断处理)）。`COMPLAINT` / `MANUAL` 为 Phase 2 预留，不经事件总线。



### 4.2 计划创建

**状态影响**：创建新计划为 `PENDING`；首步已预排或回退写入 `trigger_time` 后由扫描器进入 `STEP_SCHEDULED`。
**触发事件**：`CASE_INGESTED` / `STAGE_CHANGED`（链 [§2.1](#21-事件路由表ssot)）。
**关联 SPI**：`PlanFactory`（链 [§6.1](#61-接口职责与调用位置)）。

`CASE_INGESTED` 的准入、DPD/停催口径与 payload 组装见 [数据接入 §3](./MOCASA催收系统升级_Phase1_数据接入规格.md#3-案件消息处理主链路)；本事件只在 owner 对账 ENTER（或水位已是当日的迟到补建）时发布，`caseEvent` 到达不建计划。`STAGE_CHANGED` 的来源与目标 Stage 口径见 [数据接入 §4](./MOCASA催收系统升级_Phase1_数据接入规格.md#4-dpd-日切)。二者均复用下方创建逻辑。`collectionStatus` 由接入派生；引擎仍防御性拒绝 `CEASED` / `SETTLED` 快照的建计划请求。`owner_date` 不是当日则静默返回，不建计划。

```python
def on_case_ingested(event):
    # 快照由 payload 组装，不读 t_ai_collection（决策 B，见领域 §4.4）
    case_info = build_case_info_from_payload(event)
    snapshot  = build_snapshot_from_payload(event)

    plan = PlanFactory.create(case_info, event.stage, snapshot)   # → SPI §6.1
    if plan is None:
        return                        # 该案件不需要创建计划

    plan.status = PENDING
    first_step = plan.steps[0]
    # DayBlock 模板已写 PHT 绝对槽位则保留；扁平 delayMinutes 模板才回退计算。
    if first_step.trigger_time is None:
        first_step.trigger_time = now(PHT) + max(0, first_step.delay_minutes)
    with transaction():               # 计划+步骤+首步 trigger_time 原子落盘
        save(plan)                    # 持久化计划 + 步骤序列
```

**幂等约束**：同一 `case_id + stage` 不得重复建计划，且任一时刻仅一个非终态计划（升档 [§4.4](#44-中断处理) / 续建 [§4.5](#45-穷尽续建) 时先终态旧计划）。

**失败处理**：`PlanFactory` 抛错、超时或返回非法 `null` 时 NACK；`PlanFactory=null` 表示正常不建计划。`save(plan)` 失败时事务回滚并 NACK，事件重投后重新创建。

### 4.3 步骤执行循环

**状态影响**：`PENDING` / `STEP_SCHEDULED`（重试时 `STEP_EXECUTING`）→ `STEP_EXECUTING` → 消息类同步完成或 AI_CALL 挂起；`STEP_WAITING`（Phase 2）结转 `STEP_COMPLETED`（不触达）。推进下一步为 `STEP_SCHEDULED`；回调/超时经 `STEP_COMPLETED` 再分流。
**触发事件**：`PLAN_STEP_DUE` / `CHANNEL_CALLBACK` / `STEP_COMPLETED`（及 Cron 产生的 `CALLBACK_TIMEOUT` 哨兵；事件名均非计划状态，链 [§2.1](#21-事件路由表ssot)）。
**关联 SPI**：`ExecutionGuard` / `StepResolver` / `ChannelGateway` / `AdvancementPolicy`（链 [§6.1](#61-接口职责与调用位置)）。


| 小节                              | 触发事件               | 职责                                     |
| ------------------------------- | ------------------ | -------------------------------------- |
| [§4.3.1](#431-plan_step_due)    | `PLAN_STEP_DUE`    | 锁内按计划态分流（场景 A/B）                       |
| [§4.3.2](#432-step_completed)   | `STEP_COMPLETED`   | 推进下一步 / 计划完成 / 发布穷尽                    |
| [§4.3.3](#433-channel_callback) | `CHANNEL_CALLBACK` | 供应商 Webhook → 写结果 → 发 `STEP_COMPLETED` |
| [§4.3.4](#434-callback_timeout) | `CALLBACK_TIMEOUT` | 回调超时 → 标 `FAILED` → 发 `STEP_COMPLETED` |


下方流程图按「入口 → 执行/回环 → 汇聚 → 推进」分层：多数路径经 `STEP_COMPLETED` 进入 §4.3.2；Guard defer 与渠道退避重试不经汇聚，直接重排 `trigger_time` 后回到 Cron。线程隔离见 [§3.1](#31-线程隔离trigger-to-event)，单步分支详见 [§5](#5-步骤执行管线)。

```mermaid
flowchart TB
    subgraph in["入口（生产）"]
        C1["Cron · 扫 trigger_time"]
        C2["Cron · 扫 timeout_time"]
        WH["admin Webhook"]
    end

    subgraph s431["§4.3.1 PLAN_STEP_DUE"]
        S1["锁内分流 · 终态/重复步吸收"]
        EX["execute_step（§5）"]
        S1 -->|"A · PENDING / SCHEDULED / EXECUTING（重试）"| EX
        S1 -->|"B · STEP_WAITING 观察期满"| HUB
    end

    subgraph s5out["§5 出口（节选）"]
        LOOP["Guard defer / 渠道退避重试<br/>重排 trigger_time → SCHEDULED 或保持 EXECUTING"]
        SYNC["同步完成 · SKIPPED · FAILED"]
    end

    CB["§4.3.3 CHANNEL_CALLBACK"]
    TO["§4.3.4 CALLBACK_TIMEOUT"]
    HUB["publish STEP_COMPLETED"]
    ADV["§4.3.2 STEP_COMPLETED"]

    C1 --> S1
    C2 --> TO
    WH --> CB

    EX --> LOOP
    EX --> SYNC
    EX -.->|"AI_CALL 挂起"| ASYNC["等回调 / 超时"]
    LOOP -.->|"到期再扫"| C1
    SYNC --> HUB
    ASYNC --> CB
    ASYNC --> TO
    CB --> HUB
    TO --> HUB

    HUB --> ADV
    ADV -->|"ADVANCE_NEXT · 有下一步"| NEXT["STEP_SCHEDULED<br/>保留预排 trigger_time<br/>否则 now(PHT)+delayMin"]
    ADV -->|"ADVANCE_NEXT · 无下一步"| EXH["§4.5 PLAN_EXHAUSTED"]
    ADV -->|"PLAN_COMPLETED · 仍在催"| EXH
    ADV -->|"PLAN_COMPLETED · 已停催/结清"| DONE["终态 ✓"]
    ADV -->|PLAN_EXHAUSTED| EXH
    NEXT -.->|"到期"| C1
```



> **读图**：实线＝步骤终态汇入 `STEP_COMPLETED`；虚线＝不经汇聚的回环（defer/重试重排 `trigger_time`，或 AI_CALL 异步回调/超时）。场景 B（`STEP_WAITING`）为 Phase 2 预留，Phase 1 SMS/PUSH/EMAIL 不进 WAITING。横切中断（还款/升档/停催）见 [§4.4](#44-中断处理)，未在本图展开。



#### 4.3.1 PLAN_STEP_DUE

锁内按计划态分流（场景 A/B）：**A 到期执行**（`PENDING` / `STEP_SCHEDULED` / 退避重试时的 `STEP_EXECUTING` → 置或保持 `STEP_EXECUTING`，COMMIT 后锁外 `execute_step`）/ **B 观察期结转**（`STEP_WAITING` → 标记步骤完成；事务提交后由 Dispatcher 投递 `STEP_COMPLETED`，不触达）。

**Guard defer**（§5 ③）：若落在 PHT 静默窗（默认 21:00–08:00，`TIME_WINDOW`），将当前步 `trigger_time` 重排至 `deferUntil`（通常次日 08:00），计划回 `STEP_SCHEDULED` 后直接返回——不触达、不写 timeline、不经 `STEP_COMPLETED`；到期 Cron 重入场景 A。区别于 Guard block（跳过并推进）与渠道退避重试（保持 `EXECUTING`），见 [§5 ③](#5-步骤执行管线)。

```python
def on_plan_step_due(event):
    # ── 事务 1（毫秒级：锁 → 校验 → 状态前置 → 释放） ──
    events = []
    with transaction():
        plan = get_plan_with_lock(event.plan_id)  # SELECT FOR UPDATE
        if plan is None or plan.status in (PLAN_COMPLETED, PLAN_CANCELLED):
            return                                 # 终态拦截

        step = plan.get_current_step()

        if plan.status in (PENDING, STEP_SCHEDULED, STEP_EXECUTING):
            plan.status = STEP_EXECUTING           # 含退避重试再触发；COMMIT 后释放行锁
        elif plan.status == STEP_WAITING:
            step.status = COMPLETED
            if step.result is None:
                step.result = SENT_NO_RESPONSE
            events.append(STEP_COMPLETED)          # 事务内落发件箱，不在事务内 XADD；提交后投递（§7.2）
            return events
    # ── 事务已提交，行锁已释放 ──

    # ── 非事务上下文：渠道 I/O（允许耗时数百毫秒~数秒） ──
    execute_step(plan, step)                       # 展开见 §5
    # Guard defer（§5 ③）：step.trigger_time ← deferUntil，plan ← STEP_SCHEDULED，直接 return
    # 不经 STEP_COMPLETED；Cron 到期后再投递 PLAN_STEP_DUE → 重入本函数场景 A
```



#### 4.3.2 STEP_COMPLETED

**失败处理**：`AdvancementPolicy` 抛错、超时或返回非法 `null` 时 NACK；步骤推进状态未提交，事件重投后重新决策。

**闸门（计划存在性）**：`AdvancementPolicy` 的 `PLAN_COMPLETED` 只表示「策略认为本计划可收口」，不是整案停催令。引擎在末步仍处于催收（快照 `collectionStatus` 非 `CEASED` / `SETTLED`、非结清）时**改写为** `PLAN_EXHAUSTED`，由 [§4.5](#45-穷尽续建) 决定 REBUILD / ESCALATE / COMPLETE。`ANSWERED` 只触发同计划内 CONNECT_AND_STOP（跳过同日未执行 `AI_CALL`），不得把整案停在当前阶段。此闸门在引擎，不依赖渠道默认策略实现。

不变量：仍在催 ∧ 未结清 ∧ 未停催 ∧ 投影有 stage ⇒ 必须有活跃计划，或穷尽已 `COMPLETE`（已达 S4 / 无法再升）。日切跨天补洞见 [数据接入 §4](./MOCASA催收系统升级_Phase1_数据接入规格.md#4-dpd-日切)。

```python
def on_step_completed(plan, completed_step):
    decision = AdvancementPolicy.decide(context, step_result)   # → SPI §6.1

    if decision == ADVANCE_NEXT:
        next_step = get_next_step(plan, completed_step)
        if next_step is None:
            publish(PLAN_EXHAUSTED)                # 本计划已无后续步骤 → §4.5
            return
        # DayBlock 已预排绝对 trigger_time 时保留；否则按 delayMinutes 回退。
        if next_step.trigger_time is None:
            next_step.trigger_time = now(PHT) + max(0, next_step.delay_minutes)
        # 均由 Cron 在 trigger_time 到期后发 PLAN_STEP_DUE（非同步递归 execute_step）
        plan.status = STEP_SCHEDULED

    elif decision == PLAN_COMPLETED:
        if still_in_collection(plan):              # 非 CEASED / SETTLED / 结清
            publish(PLAN_EXHAUSTED)                # 引擎改写，不采纳策略停催
            return
        plan.status = PLAN_COMPLETED               # 已不在催，本计划收口

    elif decision == PLAN_EXHAUSTED:
        publish(PLAN_EXHAUSTED)                    # → §4.5
```



#### 4.3.3 CHANNEL_CALLBACK

Webhook 经 `collection-admin` 鉴权后发布为本事件。Phase 1 **仅 AI_CALL** 在 `STEP_EXECUTING` 等 disposition；**SMS/PUSH/EMAIL** `dispatch` 成功即同步完成，**不进** `STEP_WAITING`、不用本事件结转（与 [架构 §2.5](./MOCASA催收系统升级_Phase1_架构设计文档.md#165-外部交互安全)、[渠道编排 §3.5](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md) 一致）。

> **Timeline 落库**：admin Webhook 仅鉴权、规范化并发布事件；引擎在本事务中更新 step，并经 `TimelineRepository` 写回调结果 timeline 后发布 `STEP_COMPLETED`。channel 不直接写 timeline。

```python
def on_channel_callback(event):
    events = []
    with transaction():
        plan = get_plan_with_lock(event.plan_id)       # SELECT FOR UPDATE 串行化重复回调
        if plan.status not in (STEP_EXECUTING, STEP_WAITING):
            return events                              # 非执行/等待态（已处理或已取消），静默吸收

        step = plan.get_current_step()
        step.result = map_callback_to_result(event)    # AI_CALL disposition；WAITING 为 Phase 2 预留
        step.status = COMPLETED
        write_timeline(step.result, event.providerMsgId)
        events.append(STEP_COMPLETED)
    return events                                      # 已随本事务入发件箱；提交后由 Dispatcher 投递（§7.2）
```



#### 4.3.4 CALLBACK_TIMEOUT

AI_CALL 停在 `STEP_EXECUTING` 等 Webhook 时，若回调不到达会卡死。Phase 1 **仅靠引擎超时哨兵**：进入异步执行时注册超时 Job（[§5 ⑦](#5-步骤执行管线)）；Cron 扫到 `timeout_time` 仍无回调 → 标 `FAILED` → 发布 `STEP_COMPLETED`。

> **Timeline**：超时由引擎经 `TimelineRepository` 写入 FAILED 记录；admin/Cron 只发布 `CALLBACK_TIMEOUT`。

```python
def on_callback_timeout(event):
    events = []
    with transaction():
        plan = get_plan_with_lock(event.plan_id)
        if plan.status != STEP_EXECUTING:
            return events                          # 回调已正常处理，忽略

        step = plan.get_current_step()
        step.result = FAILED
        step.status = FAILED
        write_timeline(FAILED, error_code="CALLBACK_TIMEOUT")
        events.append(STEP_COMPLETED)
    return events                                  # 已随本事务入发件箱；提交后投递，由 AdvancementPolicy 决定下一步
```

**默认 10 分钟**（`engine.step.callback_timeout_minutes`，见 [基础设施附录 A](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-a生产配置键索引)）。等待不占 Consumer——Cron 扫表触发本事件；防重复拾取靠计划/步骤状态。

> **Phase 2 对账（AI_CALL）**：仅当独立 AI Call 合作方提供按其任务标识（`call_task_id` / `request_id`）查询终态的 API 时，才能以查询结果纠正 `CALLBACK_TIMEOUT` 造成的假 `FAILED` 并补齐 timeline。故 dispatch 成功必须把该标识落入 `t_contact_timeline.provider_msg_id`，回调原始证据落 `t_channel_callback_audit.provider_msg_id`。没有查询 API 时，只能保留「超时置失败 + 回调审计 + 告警」，不得自行重拨或推测改写结果；合作方底层线路（LTH / SIP / 其他）对该契约不可见。



### 4.4 中断处理

**状态影响**：`REPAYMENT_RECEIVED` / `CASE_CEASED` / `CASE_OWNER_RECONCILED` 将该案件活跃计划置 `PLAN_CANCELLED`；`STAGE_CHANGED` 取消旧计划后，为目标 Stage 新建 `PENDING` 计划。
**触发事件**：`REPAYMENT_RECEIVED` / `STAGE_CHANGED` / `CASE_CEASED` / `CASE_OWNER_RECONCILED`（链 [§2.1](#21-事件路由表ssot)）。`COMPLAINT` / `MANUAL` 带外取消为 **Phase 2**，见 [§4.1](#41-状态定义)。
**关联 SPI**：—（纯引擎状态机；还款路径另调 `PredictiveDialerService`）。

`REPAYMENT_RECEIVED` / `CASE_BALANCE_UPDATED` 的结清判定（`isFullCleared`）及发布来源，以 [数据接入 §3.3](./MOCASA催收系统升级_Phase1_数据接入规格.md#33-按消息类型的处理矩阵) 为 SSOT；本节前者取消计划，后者仅走 §4.6 更新余额。`CASE_CEASED` 的 DPD≥91 产出边界与 owner 对账迁出见 [数据接入 §4](./MOCASA催收系统升级_Phase1_数据接入规格.md#4-dpd-日切)。`STAGE_CHANGED` / `PLAN_EXHAUSTED` 在 owner 水位不是当日或该案 `owner_date≠当日` 时跳过。并发：`plan_id` 升序加锁 + 终态单调（[§3.2](#32-并发与一致性模型)）。中断流程见下方伪代码 + [§4.8 状态图](#48-状态转换)。

**失败处理**：计划读取或写入失败时 NACK，取消事务回滚；`PredictiveDialerService.filter_repaid_case` 失败时记录告警并继续，计划已处于 `PLAN_CANCELLED`。

```python
def on_repayment_received(case_id, user_id):
    plans = find_active_plans_by_case(case_id)     # status NOT IN 终态
    for plan in sorted(plans, key=lambda p: p.id): # 按 plan_id 升序加锁，防止死锁
        lock(plan)                                 # SELECT FOR UPDATE
        if plan.status in (PLAN_COMPLETED, PLAN_CANCELLED):
            continue                               # 终态不可逆：取锁后复检（§3.2），不覆盖
        plan.status = PLAN_CANCELLED
        plan.cancel_reason = REPAID
        cancel_scheduled_jobs(plan)
    PredictiveDialerService.filter_repaid_case(user_id, case_id)  # 失败 → 告警 + 继续

def on_stage_changed(case_id, new_stage):
    old_plans = find_active_plans_by_case(case_id)
    for plan in sorted(old_plans, key=lambda p: p.id):
        if plan.stage != new_stage:
            lock(plan)
            if plan.status in (PLAN_COMPLETED, PLAN_CANCELLED):
                continue                           # 终态不可逆：取锁后复检（§3.2），不覆盖
            plan.status = PLAN_CANCELLED
            plan.cancel_reason = STAGE_UPGRADE
            cancel_scheduled_jobs(plan)
    create_plan_for_stage(case_id, new_stage)       # 复用 §4.2 创建流程

def on_case_ceased(case_id):                        # D+91 完全停催：取消活跃计划，不续建
    old_plans = find_active_plans_by_case(case_id)
    for plan in sorted(old_plans, key=lambda p: p.id):  # 按 plan_id 升序加锁，防止死锁
        lock(plan)                                  # SELECT FOR UPDATE
        if plan.status in (PLAN_COMPLETED, PLAN_CANCELLED):
            continue                                # 终态不可逆：取锁后复检（§3.2），不覆盖
        plan.status = PLAN_CANCELLED
        plan.cancel_reason = CEASED
        cancel_scheduled_jobs(plan)
    # 不调用 create_plan_for_stage —— 停催后主动催收终止（区别于 STAGE_CHANGED 的取消+重建）

def on_case_owner_reconciled(case_id):              # 当日 NEW 缺席：迁出，不续建
    old_plans = find_active_plans_by_case(case_id)
    for plan in sorted(old_plans, key=lambda p: p.id):
        lock(plan)
        if plan.status in (PLAN_COMPLETED, PLAN_CANCELLED):
            continue
        plan.status = PLAN_CANCELLED
        plan.cancel_reason = ROUTED_TO_LEGACY
        cancel_scheduled_jobs(plan)
```



### 4.5 穷尽续建

**状态影响**：当前非终态计划恒转 `PLAN_COMPLETED`；`REBUILD` / `ESCALATE` 另建 `PENDING` 新计划（首步靠 Cron，非 case Pub/Sub）。`REBUILD` 新计划首步 `trigger_time` 不得早于**次日 08:00 PHT**（Factory 已排更晚则保留）；避免末步接通后同日再触达（CONNECT_AND_STOP 只管同一张计划）。`ESCALATE` / 日切新建不在此钳制，沿用 Factory 预排。
**触发事件**：`PLAN_EXHAUSTED`（所有步骤执行完毕但用户未还款；链 [§2.1](#21-事件路由表ssot)）。
**关联 SPI**：`ExhaustionPolicy` / `PlanFactory`（链 [§6.1](#61-接口职责与调用位置)）。

穷尽**不等于结束**。当一个计划的所有步骤都已执行完毕但用户仍未还款时，`ExhaustionPolicy`（渠道编排 SPI）返回三值之一，引擎按 [§4.5 伪代码](#45-穷尽续建) 落地。末步如何进入本事件见 [§4.3.2](#432-step_completed)。

**失败处理**：`ExhaustionPolicy` 或 `REBUILD` 内的 `PlanFactory` 抛错、超时或返回非法 `null` 时 NACK；当前事务回滚，`PLAN_EXHAUSTED` 重投后重新处理。


| 返回值        | 场景                                            | 引擎动作                                          | Phase 1 策略实现                                                 |
| ---------- | --------------------------------------------- | --------------------------------------------- | ------------------------------------------------------------ |
| `REBUILD`  | **同阶段续建**：本轮步骤已跑完仍未还款，同 Stage 内再建一轮计划（换模板/策略） | `PlanFactory.create()` 同阶段新建 + 注册首步 Job       | ✅ `DefaultExhaustionPolicy`：续建次数 < `max_rebuild_count`（默认 2） |
| `ESCALATE` | **策略升档**：同 Stage 续建次数已用尽，提升催收强度               | 发布 `STAGE_CHANGED` → §4.4 取消旧计划 + §4.2 建新阶段计划 | ✅ 续建超限且 Stage 可升（S1→S2→…→S4）                                 |
| `COMPLETE` | **停止主动触达**：续建与升档均无可行路径                        | 标记 `PLAN_COMPLETED`                           | ✅ 已达 S4 或无法升档；Mock 恒返回 COMPLETE                              |


> `REBUILD` 有续建次数上限（`engine.plan.max_rebuild_count`），达到上限后策略层应返回 `ESCALATE` 或 `COMPLETE`。

```python
def on_plan_exhausted(event):
    plan = get_plan_with_lock(event.plan_id)
    if plan.status in (PLAN_COMPLETED, PLAN_CANCELLED):
        return

    # 续建复用旧计划快照，不回读 t_ai_collection；后续步骤发送前仍会按 §5②½
    # 覆盖内存中的 dpd / totalOutstanding，保证用户可见文案取发送时刻值。
    snapshot = deserialize(plan.context_snapshot)
    case_info = case_info_from_snapshot(snapshot)

    result = ExhaustionPolicy.handle(plan, case_info, snapshot)   # → SPI §6.1

    if result.action == REBUILD:
        with transaction():                        # 原子：renewal_pending + 新 plan + 旧 plan 终态
            plan.renewal_pending = True              # 旧 plan 暂退出活跃唯一约束与 Cron 扫描
            create_plan_for_stage(plan.case_id, plan.stage, case_info, snapshot)  # 同 stage 新建
            clamp_first_step_to_next_pht_morning()   # 不得早于次日 08:00；已更晚则保留
            plan.status = PLAN_COMPLETED             # 新 plan 落库后再终态化旧 plan
    elif result.action == ESCALATE:
        with transaction():                        # 原子：旧 plan 终态 + 后继入发件箱
            plan.status = PLAN_COMPLETED
            enqueue_outbox(STAGE_CHANGED, new_stage=result.target_stage)  # 提交后投递；消费时走 §4.4 + §4.2
    elif result.action == COMPLETE:
        plan.status = PLAN_COMPLETED               # 终态，不再主动触达
```

> **崩溃安全**：`REBUILD` / `ESCALATE` 同事务原子提交；未提交则回滚，靠 `PLAN_EXHAUSTED` 重投重跑（下游幂等）。`ESCALATE` 的 `STAGE_CHANGED` 经发件箱投递（§7.2）。正常 `COMPLETE` 与半成品库态不可区分，不做 §7.4 扫描补救。



### 4.6 部分还款余额更新

**触发事件**：`CASE_BALANCE_UPDATED`（发布判定与 payload 口径见 [数据接入 §3.3](./MOCASA催收系统升级_Phase1_数据接入规格.md#33-按消息类型的处理矩阵)；链 [§2.1](#21-事件路由表ssot)）。
**状态影响**：无。该事件只更新活跃计划的快照金额，不属于计划状态迁移。

```python
def on_case_balance_updated(event):
    if event.case_id is None or event.total_outstanding is None or event.total_outstanding < 0:
        return                                      # 脏事件静默忽略

    plans = find_active_plans_by_case(event.case_id)
    for plan in sorted(plans, key=lambda p: p.id):
        lock(plan)                                  # SELECT FOR UPDATE
        if plan.status in (PLAN_COMPLETED, PLAN_CANCELLED):
            continue                                # 取锁后复检终态
        ctx = plan.context_snapshot.caseContext
        ctx.totalOutstanding = event.total_outstanding      # 必填，已校验非负
        for f in (overdueAmount, dpd, penaltyAmount,        # 可选：仅在事件携带该键时覆盖，
                  upcomingAmount, nextDueDate,              # 缺省保持旧值，不写 null
                  collectionStatus):
            if event.has(f):
                ctx[f] = event[f]
        save_context_snapshot(plan)
```

**可写字段全集**（`CaseContext` 内，实现见 `PlanLifecycleManager.onCaseBalanceUpdated`）：


| 字段                                                   | 必填性 | 说明                                       |
| ---------------------------------------------------- | --- | ---------------------------------------- |
| `totalOutstanding`                                   | 必填  | 缺失或为负则整个事件忽略                             |
| `overdueAmount` / `penaltyAmount` / `upcomingAmount` | 可选  | 运行态金额                                    |
| `nextDueDate`                                        | 可选  | 下一期提醒日期                                  |
| `dpd`                                                | 可选  | 数仓重算值，接入不自算；仅刷新快照列，**不推导 stage**         |
| `collectionStatus`                                   | 可选  | 由接入派生（`SETTLED` / `IN_COLLECTION`），引擎只透传 |


**边界**：`stage` **不在可写集内**——阶段变化只能由 `STAGE_CHANGED` 驱动（[§4.4](#44-中断处理)）。不修改计划/步骤状态、模板、渠道决策字段或已注册 Job，**不调用** `PlanFactory.create()`、`ExhaustionPolicy` 或 `create_plan_for_stage()`；后续步骤沿用原计划与话术，仅在渲染时读取更新后的金额。可选字段采用「事件携带才覆盖」语义，避免部分字段的还款消息把快照里的其他金额清成 null。

### 4.7 PTP 到期处理（Phase 2 预留）
<a id="47-ptp-到期处理"></a>

**⏳ Phase 2 预留**：Phase 1 不做 PTP，不生产/不消费 `PTP_EXPIRED`（枚举仅前向兼容，见 [领域模型 §2.7](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#27-cancelreason计划取消原因)）。实时还款仍走 [§5 ②](#5-步骤执行管线) / [§4.4](#44-中断处理)。需要产品确认到期信号源与 keep/break 规则后再实现。

### 4.8 状态转换

本节为计划状态机**唯一完整 SSOT**；穷尽续建细节见 [§4.5](#45-穷尽续建)。

```mermaid
flowchart TB
    subgraph create ["创建 §4.2"]
        IN["CASE_INGESTED / STAGE_CHANGED"] --> PENDING
    end

    subgraph main ["主循环 §4.3"]
        PENDING -->|PLAN_STEP_DUE · A| EXEC["STEP_EXECUTING"]
        SCHED["STEP_SCHEDULED"] -->|PLAN_STEP_DUE · A| EXEC
        EXEC -->|观察期 Phase 2| WAIT["STEP_WAITING"]
        EXEC -->|同步完成 / 回调| SC["STEP_COMPLETED"]
        WAIT -->|PLAN_STEP_DUE · B / CALLBACK| SC
        SC --> AP["§4.3.2 AdvancementPolicy"]
        AP -->|ADVANCE| SCHED
        AP -->|PLAN_COMPLETED · 仍在催| EXH["§4.5"]
        AP -->|PLAN_COMPLETED · 已停催/结清| DONE["PLAN_COMPLETED ✓"]
        AP -->|PLAN_EXHAUSTED| EXH
    end

    subgraph exhaust ["穷尽 §4.5"]
        EXH -->|REBUILD| NP["新 plan · PENDING"]
        EXH -->|REBUILD / ESCALATE / COMPLETE| DONE
        DONE -.->|ESCALATE · STAGE_CHANGED| IN
    end

    subgraph intr ["中断 §4.4（任意非终态可切入）"]
        INTR["任意非终态"] -.->|REPAYMENT_RECEIVED| CR["PLAN_CANCELLED · REPAID"]
        INTR -.->|STAGE_CHANGED 日切| CS["PLAN_CANCELLED · STAGE_UPGRADE"]
        CS --> IN
        INTR -.->|CASE_CEASED| CC["PLAN_CANCELLED · CEASED"]
        INTR -.->|CASE_OWNER_RECONCILED| CL["PLAN_CANCELLED · ROUTED_TO_LEGACY"]
    end
```



> 读图：**实线**＝主循环与创建；**虚线**＝中断横切。`STEP_EXECUTING` 异步完成经 `CHANNEL_CALLBACK` / `CALLBACK_TIMEOUT` → `STEP_COMPLETED`（见 [§4.3](#43-步骤执行循环)），未单独画边。Phase 1 消息类渠道不进 `STEP_WAITING`。
>
> **穷尽 vs 日切**：`REBUILD` / `ESCALATE` / `COMPLETE` 均将**旧 plan** 置 `PLAN_COMPLETED`；`REBUILD` 另建同 stage **新 plan**（`renewal_pending` 过渡，首步钳到次日 08:00 PHT）。`ESCALATE` 经发件箱投递 `STAGE_CHANGED` 触发 §4.2 建新 stage plan（旧 plan 已是终态，不经 `STAGE_UPGRADE` 取消）。日切 `STAGE_CHANGED` 走 §4.4：有活跃计划则 `CANCELLED · STAGE_UPGRADE` 再建；无活跃且投影高于最近完成计划时补建（[数据接入 §4](./MOCASA催收系统升级_Phase1_数据接入规格.md#4-dpd-日切)）。
>
> **阶段权威与单调前进**：`STAGE_CHANGED` 有两个合法发布方——日切（投影 DPD 推导）与本节 `ESCALATE`（催收强度策略）。引擎**从不回写** `t_ai_collection`，所以 `ESCALATE` 之后「活跃计划 stage > 投影 stage」是正常稳态。因此日切只在投影 stage **严重度更高**时发布 `STAGE_CHANGED`，**不得**发布回退事件（[数据接入 §4](./MOCASA催收系统升级_Phase1_数据接入规格.md#4-dpd-日切)）；否则升档计划会被打回低阶段、穷尽后再次升档，形成降档 ping-pong。计划 stage 在案件催收生命周期内单调不减，降级只能经 `CASE_CEASED` 或还款取消收敛。

---



## 5. 步骤执行管线

**场景 A（到期执行）**：`PLAN_STEP_DUE` 在短事务内确认计划仍可执行，并将计划前置为 `STEP_EXECUTING`；提交、释放行锁后，才调用本节完成当前步骤的守卫、解析与渠道 I/O。架构文档仅保留跨模块边界，本节定义执行骨架。

### 5.1 execute_step 执行骨架（①–⑦，含 ⑤½）
<a id="51-execute_step-执行骨架"></a>

**入口**：[§4.3.1](#431-plan_step_due) 事务外调用（行锁已释放，plan=`STEP_EXECUTING`）。
**出口**：同步路径 → `STEP_COMPLETED`（经发件箱 §7.2）；AI_CALL → 保持 `STEP_EXECUTING` 等 [§4.3.3/§4.3.4](#433-channel_callback)。
**关联 SPI**：③ `ExecutionGuard` / ④ `StepResolver` / ⑤ `ChannelGateway`（[§6.1](#61-接口职责与调用位置)）。

```mermaid
flowchart TD
    entry(["§4.3.1 场景 A<br/>释放行锁后"]) --> idempotency

    idempotency["① 获取步骤幂等锁<br/>Redis collection:lock:plan:{idempotencyKey}"]
    idempotency -->|重复| duplicate_exit["退出 · 无写库"]
    idempotency --> preflight

    preflight["② 系统守卫 PreFlightChecker<br/>只读 CaseService / t_ai_collection"]
    preflight -->|owner 门控未过| gated_exit["退出 · 不取消计划、不触达"]
    preflight -->|案件不存在 / 已还款| cancel_plan["t_contact_plan → PLAN_CANCELLED"]
    cancel_plan --> preflight_exit["退出 · 不写 timeline"]
    preflight --> mark_executing["t_contact_plan_step · executed_at<br/>markStepExecuting"]
    mark_executing --> refresh["②½ 用 ② 的 CaseInfo 刷新<br/>快照副本 dpd / 余额（仅内存）"]
    refresh --> guard

    guard["③ 合规守卫 ExecutionGuard · SPI"]
    guard -->|deferUntil| reschedule_defer["t_contact_plan_step · trigger_time/status<br/>t_contact_plan → STEP_SCHEDULED"]
    reschedule_defer --> rescan_exit["退出 · 不经 STEP_COMPLETED<br/>Cron 到期重入 §4.3.1"]
    guard -->|拦截| record_block["t_contact_plan(_step) · SKIPPED<br/>t_contact_timeline · COMPLIANCE_BLOCKED<br/>t_event_outbox · STEP_COMPLETED"]
    record_block --> advance["→ §4.3.2"]
    guard --> resolve

    resolve["④ 步骤解析 StepResolver · SPI"]
    resolve -->|null 策略跳过| record_strategy_skip["t_contact_plan(_step) · SKIPPED<br/>t_event_outbox · STEP_COMPLETED<br/>（不写 timeline）"]
    record_strategy_skip --> advance
    resolve -->|异常| record_resolver_failure["t_contact_plan(_step) · FAILED<br/>t_contact_timeline + outbox · STEP_COMPLETED"]
    record_resolver_failure --> advance
    resolve --> decision_log["t_decision_log · CHANNEL_SELECT<br/>（fail-open，失败仅告警）"]
    decision_log --> dispatch

    dispatch["⑤ 渠道调度 ChannelGateway.dispatch<br/>（无引擎写库；供应商 I/O）"]
    dispatch --> reload_status

    reload_status["⑤½ 回写前复检计划终态"]
    reload_status -->|已取消| record_cancelled_dispatch["t_contact_timeline · 补偿记录<br/>（不推进状态机）"]
    record_cancelled_dispatch --> cancelled_exit["退出"]
    reload_status --> dispatch_result

    dispatch_result{"⑥ 处理 dispatch 结果"}
    dispatch_result -->|可重试失败| schedule_retry["t_contact_plan_step · retry_count/trigger_time<br/>plan 保持 STEP_EXECUTING"]
    schedule_retry --> rescan_exit
    dispatch_result -->|不可重试失败| record_channel_failure["t_contact_plan(_step) · FAILED<br/>t_contact_timeline + outbox"]
    record_channel_failure --> advance
    dispatch_result -->|成功| mark_dispatched["t_contact_plan_step · dispatched_at"]
    mark_dispatched --> channel_split

    channel_split{"⑦ 按渠道完成方式分流"}
    channel_split -->|SMS/PUSH/EMAIL| record_sync_result["t_contact_timeline · 触达结果<br/>t_contact_plan(_step) · COMPLETED<br/>outbox · STEP_COMPLETED"]
    record_sync_result --> advance
    channel_split -->|AI_CALL| await_callback["t_contact_timeline · 受理结果 / provider_msg_id<br/>t_contact_plan_step · timeout_time<br/>plan 保持 STEP_EXECUTING"]
    await_callback --> async_exit["等 §4.3.3 回调 / §4.3.4 超时"]
```



**失败处理**：


| 位置   | 条件                                                | 处置                                                                       |
| ---- | ------------------------------------------------- | ------------------------------------------------------------------------ |
| ①–②  | 执行锁 Redis 或 PreFlight MySQL 不可用                   | 释放已获取的锁后 NACK；事件重投                                                       |
| ③    | `ExecutionGuard` 抛错、超时、非法 `null` 或合规计数器 Redis 不可用 | `SKIPPED` + `GUARD_ERROR` 告警 + `STEP_COMPLETED`；不触达                      |
| ④    | `StepResolver` 抛错或超时                              | `FAILED` + `STEP_COMPLETED`                                              |
| ④    | `StepResolver=null`                               | 正常策略跳过：`SKIPPED` + `STEP_COMPLETED`，不写 timeline                          |
| ④ 后  | `decision_log` 写入失败                               | 仅告警；继续渠道调用                                                               |
| ⑤    | `retryable=true` 且未达重试上限                          | 退避重排当前步骤，保持 `STEP_EXECUTING`                                             |
| ⑤    | `retryable=false`、重试耗尽或 `dispatch` 抛异常            | `FAILED` + `STEP_COMPLETED`；`dispatch` 异常记 `CHANNEL_OUTCOME_UNKNOWN`，不重试 |
| ⑤½–⑦ | 已调用渠道后读取计划、写状态或登记 Job 失败                          | 不重试，按 [§7.3](#73-渠道调用后的部分成功) 记录和检测                                       |


⑤ `dispatch` 前的可重试异常释放执行锁后 NACK；调用开始后保留锁，避免重投重复触达。`STEP_COMPLETED` 与步骤状态迁移同事务入发件箱，提交后投递（[§7.2](#72-派生事件可靠投递)）。

```python
def execute_step(plan, step):
    # ── ① 执行锁 ──
    # effective_idempotency_ttl = max(engine.step.idempotency_ttl_minutes, callback_timeout_minutes)
    if not IdempotencyService.acquire(execution_lock_key, ttl_minutes=effective_idempotency_ttl):
        return
    # ⑤ 前异常：release(execution_lock_key) 后上抛 → NACK 重投；
    # ⑤ 已调用：不得释放，交由渠道幂等与 §7.3 收敛。

    # ── ② 系统级守卫（实时查 DB；owner 门控 / 案件存在 / 还款） ──
    preflight = PreFlightChecker.inspect(plan.case_id)   # 带出本次读到的 CaseInfo
    if preflight.gated:                   # 水位不是当日或 owner_date ≠ 当日
        return                            # 不取消计划、不写 timeline
    if not preflight.passed:
        plan.status = PLAN_CANCELLED
        return                                    # 不写 timeline、不投递 STEP_COMPLETED

    # ── ②½ 渲染前刷新日变字段（复用 ② 的实时读，零新增 I/O） ──
    context = ContextAssembler.assemble(plan, step)
    context.snapshot.case_context.dpd = preflight.case_info.dpd
    context.snapshot.case_context.total_outstanding = preflight.case_info.total_outstanding
    # 仅内存覆盖：不回写 context_snapshot 列，不覆盖 stage（阶段决定模板与话术，须与计划一致）。
    # 理由：快照 dpd 冻结于建计划时刻，单阶段最长跨 60 天（S4 = DPD 31–90），
    # 不刷新会连续数十天向用户播报错误逾期天数；余额同理，CASE_BALANCE_UPDATED 只覆盖还款场景。

    # ── ③ 业务级守卫（Phase 1 内存计数器：每日渠道频率 / 时段 / 地址可用性） ──
    verdict = ExecutionGuard.evaluate(context)     # → SPI §6.1
    if not verdict.allowed:
        if verdict.defer_until:
            step.trigger_time = verdict.defer_until
            step.status = PENDING
            plan.status = STEP_SCHEDULED
            release(execution_lock_key)            # 同一 retryCount 未来可再次执行
            return
        step.status = SKIPPED
        write_timeline(COMPLIANCE_BLOCKED, violation=verdict.blocked_reason)
        publish(STEP_COMPLETED)
        return

    # ── ④ 步骤解析（SPI 零 DB I/O，读 context_snapshot） ──
    command = StepResolver.resolve(context)        # → SPI §6.1
    if command is None:
        step.status = SKIPPED
        publish(STEP_COMPLETED)
        return
    write_decision_log(context, command)           # → t_decision_log（fail-open，领域 §3.3）

    # ── ⑤ 渠道调度（渠道层内部熔断/fallback 对引擎透明） ──
    step.status = STEP_EXECUTING                    # 标记执行中：移出 planStepDueHandler「待触发」扫描，
                                                    # 防渠道 I/O / 异步等待期被 Cron 重拾二次发送（异步期由 timeout_time 守护，非 trigger_time）
    # dispatch 抛异常 → 结果未知，记 FAILED 后推进；仅渠道明确未受理时才 retryable
    result = ChannelGateway.dispatch(command)

    # ── ⑤½ 回写前取消检测（应对渠道 I/O 期间计划被取消的场景） ──
    if reload_plan_status(plan.id) in (PLAN_COMPLETED, PLAN_CANCELLED):
        write_timeline(result, note="plan_cancelled_during_dispatch")
        return                                    # 记录已发出的触达，但不推进状态机

    # ── ⑥ 故障降级 ──
    if not result.success:
        if result.retryable and step.retry_count < MAX_RETRY:
            step.retry_count += 1
            delay = min(RETRY_BASE * (RETRY_FACTOR ** step.retry_count), RETRY_MAX)
            register_job(PLAN_STEP_DUE, delay_seconds=delay)  # 非阻塞：注册短延迟 Job
            return                                             # plan 保持 STEP_EXECUTING
        step.status = FAILED
        write_timeline(CHANNEL_ERROR, error_code=result.error_code)
        publish(STEP_COMPLETED)                   # 失败也推进，不卡死
        return

    # ── ⑦ 渠道分流 ──
    if command.channel_type in (SMS, PUSH, EMAIL):
        write_timeline(result)
        publish(STEP_COMPLETED)                   # Phase 1 同步完成，不进 WAITING
    elif command.channel_type in (VIBER, WHATSAPP) and step.observation_minutes > 0:
        write_timeline(result)
        register_job(PLAN_STEP_DUE, step.observation_minutes)  # Phase 2 预留
        plan.status = STEP_WAITING
    elif command.channel_type in (VIBER, WHATSAPP):
        write_timeline(result)
        publish(STEP_COMPLETED)
    else:  # AI_CALL（HUMAN_CALL Phase 2 预留）
        write_timeline(result, provider_msg_id=result.provider_msg_id)  # 先持久化合作方任务标识
        register_job(CALLBACK_TIMEOUT, callback_timeout_minutes)  # 超时哨兵 → §4.3.4
        plan.status = STEP_EXECUTING              # 保持执行态，释放线程，等待异步回调
```

---



## 6. SPI 接口契约

5 个策略 SPI（`common.spi`，渠道编排实现）+ 技术管道 `ChannelGateway`（`common.channel`）；均发布于 `collection-common`。模块边界见 [§1.2](#12-模块边界与调用全景)；DTO 字段 SSOT 见 [领域模型 §5](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#5-spi-契约-dto)。

### 6.1 接口职责与调用位置

接口源码（`collection-common`）为签名权威；本节只界定职责与调用位置。返回值与超时约束见 [§6.2](#62-返回值与实现约束)；调用失败处置见 [§4.2](#42-计划创建)、[§4.3.2](#432-step_completed)、[§4.5](#45-穷尽续建) 与 [§5](#5-步骤执行管线)。


| 接口                  | 调用组件 / 时机                                     | 产出                    |
| ------------------- | --------------------------------------------- | --------------------- |
| `PlanFactory`       | `PlanLifecycleManager`：§4.2 创建、§4.5 `REBUILD` | `ContactPlan`         |
| `ExecutionGuard`    | `StepExecutionOrchestrator`：§5 ③              | `GuardVerdict`        |
| `StepResolver`      | `StepExecutionOrchestrator`：§5 ④              | `StepCommand`         |
| `ChannelGateway`    | `StepExecutionOrchestrator`：§5 ⑤              | `StepResult`          |
| `AdvancementPolicy` | `PlanLifecycleManager`：§4.3.2                 | `AdvancementDecision` |
| `ExhaustionPolicy`  | `PlanLifecycleManager`：§4.5                   | `ExhaustionResult`    |


下图按调用时序展示主链；回环表示后续事件再次进入同一处理链。

```mermaid
flowchart LR
    A(["CASE_INGESTED / STAGE_CHANGED"]) --> PF["PlanFactory.create()"]
    PF --> DUE(["PLAN_STEP_DUE"])
    DUE --> EG["ExecutionGuard.evaluate()"]
    EG --> SR["StepResolver.resolve()"]
    SR --> CG["ChannelGateway.dispatch()"]
    CG --> DONE(["STEP_COMPLETED"])
    DONE --> AP["AdvancementPolicy.decide()"]
    AP -->|ADVANCE_NEXT| DUE
    AP -->|PLAN_EXHAUSTED / 仍在催的 PLAN_COMPLETED| EP["ExhaustionPolicy.handle()"]
    EP -->|REBUILD| PF
    AP -->|PLAN_COMPLETED · 已停催/结清| END((终态))
    EP -->|ESCALATE / COMPLETE| END
```





#### 接口签名速查

```java
ContactPlan          PlanFactory.create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot);
GuardVerdict         ExecutionGuard.evaluate(ExecutionContext context);
StepCommand          StepResolver.resolve(ExecutionContext context);
AdvancementDecision  AdvancementPolicy.decide(ExecutionContext context, StepResult stepResult);
ExhaustionResult     ExhaustionPolicy.handle(ContactPlan plan, CaseInfo caseInfo, ContextSnapshot snapshot);
StepResult           ChannelGateway.dispatch(StepCommand command);
```



### 6.2 返回值与实现约束



#### 正常特殊值


| 接口 / 返回值                         | 引擎语义                                                                             |
| -------------------------------- | -------------------------------------------------------------------------------- |
| `PlanFactory = null`             | 正常不建计划。仅适用于新入案或阶段变更；`REBUILD` 路径不能生成后继则回滚并重投 `PLAN_EXHAUSTED`。                   |
| `StepResolver = null`            | 正常主动跳过：步骤记 `SKIPPED`，再投递 `STEP_COMPLETED`。                                       |
| `ChannelGateway.success = false` | 渠道失败：按 `retryable` 退避重试或记 `FAILED` 后推进。                                          |
| 其他 SPI 返回 `null`                 | 非法结果：`ExecutionGuard` 记 `SKIPPED`；`AdvancementPolicy` / `ExhaustionPolicy` NACK。 |




#### SPI 实现约束

编排方实现 5 个 SPI 时须遵守（引擎调用方式决定，非业务策略）：


| 维度           | 约束                                                                                                                                                                                                                                    |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **无副作用**     | **须** 只读计算 · **禁** 写 DB / 发事件 / 调外部服务 · **例外** `ExecutionGuard` 可读 Redis 合规计数器；`ESCALATE` 的 `STAGE_CHANGED` 由引擎 [§4.5](#45-穷尽续建) 发布                                                                                                   |
| **null 返回值** | `PlanFactory`：null → 不建计划（正常） · `StepResolver`：null → 主动跳过 → `SKIPPED` · **其余 SPI**：不可 null，按其调用位置执行失败处理                                                                                                                              |
| **硬超时**      | `SpiInvoker` 统一 `Future.get(timeout_ms)`；超时或池满 → `SpiTimeoutException`；处置见调用位置，配置键与默认值见 [基础设施附录 A](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-a生产配置键索引)                                                                                 |
| **I/O**      | 含 I/O 的 SPI（Guard）：**client 命令超时 < 执行器阈值**（client 第一道防线，执行器仅兜底线程池）                                                                                                                                                                    |
| **锁内 SPI**   | `AdvancementPolicy` / `ExhaustionPolicy` 在行锁事务内调用：**纯内存、≤10ms**                                                                                                                                                                       |
| **快照与历史**    | 策略字段在计划存活期保持不变；`CASE_BALANCE_UPDATED` 可更新持久化的 `totalOutstanding`，步骤②½ 再在内存覆盖 `dpd` / `totalOutstanding`。还款/存在性走 [§5②](#5-步骤执行管线)。Guard/Resolver 可读最多 50 条 `recentTimeline`（行为上下文，非精确计数）；AdvancementPolicy 锁内轻量上下文，`recentTimeline` 为空 |


> ⏳ 待深入讨论：Phase 1 超时阈值为联调前工程默认，由主架构在联调后按 SPI p99 回采，再改 [基础设施附录 A](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-a生产配置键索引)。



### 6.3 共享 DTO 定义

字段定义 SSOT 在 [领域模型 §5](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#5-spi-契约-dto)。本节只对照「哪个 SPI 用哪个 DTO」；运行时 `StepResult` 语义见 [执行契约](./contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md)。

| DTO | 关联接口 | 字段定义 |
|---|---|---|
| `CaseInfo` | PlanFactory / ExhaustionPolicy / PreFlightChecker | [领域模型 §5.1](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#51-caseinfo案件基本信息--spi-入参) |
| `ExecutionContext` | ExecutionGuard / StepResolver / AdvancementPolicy | [领域模型 §5.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#52-executioncontext执行上下文) |
| `GuardVerdict` | ExecutionGuard | [领域模型 §5.3](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#53-guardverdict守卫裁定) |
| `StepCommand` | StepResolver / ChannelGateway | [领域模型 §5.4](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#54-stepcommand步骤命令) |
| `StepResult` | ChannelGateway / AdvancementPolicy | [领域模型 §5.5](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#55-stepresult步骤结果) |
| `AdvancementDecision` | AdvancementPolicy | [领域模型 §5.6](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#56-advancementdecision推进决策) |
| `ExhaustionResult` | ExhaustionPolicy | [领域模型 §5.7](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#57-exhaustionresult穷尽结果) |

---



## 7. 容错与异常恢复

局部失败规则位于对应调用章节：计划创建、推进与穷尽见 [§4](#4-计划生命周期与状态机)，步骤执行见 [§5](#5-步骤执行管线)，SPI 返回值与超时见 [§6.2](#62-返回值与实现约束)。本节定义全局处置结果、派生事件投递和不可自动恢复的边界。

### 7.1 统一处置规则




| 条件                                                       | 处置                                                  |
| -------------------------------------------------------- | --------------------------------------------------- |
| Redis Stream 读取失败                                        | 下一轮轮询重连，不启动独立看门狗                                    |
| 计划创建、推进、穷尽或取消事务失败                                        | NACK；事件重投后重新执行                                      |
| 渠道调用前的 Redis / MySQL 失败                                  | NACK；释放已获取的执行锁                                      |
| 合规判定失败                                                   | `SKIPPED` + `GUARD_ERROR` 告警 + `STEP_COMPLETED`；不触达 |
| 步骤解析失败                                                   | `FAILED` + `STEP_COMPLETED`                         |
| 渠道明确未受理，且 `retryable=true`                               | 退避重排当前步骤                                            |
| 渠道结果未知、渠道已受理或渠道调用后持久化失败                                  | 不重试；记录可见状态并等待人工处理                                   |
| 旁路写入失败（决策日志、预测外呼过滤）                                      | 记录告警；主链继续                                           |
| payload 畸形或可重试事件达到 `collection.redis.max-delivery-count` | DLQ + 告警                                            |


`NACK` 表示消息保留在 PEL 中等待重投；`SKIPPED` 表示合规限制或守卫失败导致不触达；`FAILED` 表示本步骤终止并推进计划。

### 7.2 派生事件可靠投递



状态迁移提交而派生事件未投递时，原事件重投会因状态已终结而 no-op，无法再次生成后继事件。以下位置必须将状态迁移和派生事件写入同一事务：


| 位置                                 | 状态迁移                                      | 派生事件             |
| ---------------------------------- | ----------------------------------------- | ---------------- |
| [§4.3.1](#431-plan_step_due) 观察期结转 | step → `COMPLETED`                        | `STEP_COMPLETED` |
| [§4.3.2](#432-step_completed) 末步推进 | 无下一步                                      | `PLAN_EXHAUSTED` |
| [§4.3.3](#433-channel_callback) 回调 | step → `COMPLETED`                        | `STEP_COMPLETED` |
| [§4.3.4](#434-callback_timeout) 超时 | step → `FAILED`                           | `STEP_COMPLETED` |
| [§4.5](#45-穷尽续建) `ESCALATE`        | 旧 plan → `PLAN_COMPLETED`                 | `STAGE_CHANGED`  |
| [§5](#5-步骤执行管线) 同步完成、Guard 拦截、策略跳过 | step → `COMPLETED` / `SKIPPED` / `FAILED` | `STEP_COMPLETED` |


投递协议：

1. 状态迁移事务内插入 `t_event_outbox` 的 `PENDING` 记录；入箱仓储使用 `Propagation.MANDATORY`，事务外调用立即失败。`next_retry_at = now + grace-seconds`。
2. 事务提交后，Dispatcher 或 Orchestrator 立即发布；成功后将同一 `eventId` 的记录置 `PUBLISHED`。
3. `OutboxPublisher` 每 `poll-interval-ms` 扫描已到期的 `PENDING` 记录和租约到期的 `PROCESSING` 记录，原子认领为 `PROCESSING` 并写入 `lease-seconds` 租约。认领实例崩溃后，租约到期才允许其他实例重发。
4. 重发失败回写 `PENDING`，`next_retry_at = now + min(grace-seconds × backoff-factor^retry-count, max-backoff-seconds)`；达到 `max-retry-count` 后置 `FAILED`，递增 `collection.outbox.failed` 并转人工。

`eventId` 使用确定性业务键：`STEP_COMPLETED:{planId}:{stepOrder}:{retryCount}`、`PLAN_EXHAUSTED:{planId}`、`STAGE_CHANGED:{planId}:{targetStage}`。入箱记录与即时发布必须使用同一 `eventId`；payload 带 `caseId`、`planId`，步骤事件另带 `stepId`。默认值与部署参数见[基础设施附录 A.2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a2-引擎与事件总线)。

### 7.3 渠道调用后的部分成功



⑤ `dispatch` 调用开始后，渠道可能已接收触达，而 ⑥⑦ 的状态或 timeline 事务尚未提交。事务回滚后没有记录可证明触达已发生，因此不得自动重试。


| 保护措施     | 边界                                                                                             |
| -------- | ---------------------------------------------------------------------------------------------- |
| 执行锁      | 调用渠道后的路径不释放锁；TTL 取 `max(idempotency-ttl-minutes, callback-timeout-minutes)`，默认 15 分钟。锁内重投直接退出。 |
| 供应商去重    | 锁失效后的重投依赖供应商去重。`providerIdempotencyKey` 已预留，供应商去重能力待编排接入确认，不作为正确性保证。                           |
| timeline | 若 `t_contact_timeline` 已提交，触达结果可查询；状态机仍可能未推进。                                                  |


消息渠道 step 在 `prepare_step_due` 后会清空 `trigger_time`，且没有 `timeout_time`；⑥⑦ 失败后可能滞留 `STEP_EXECUTING`。`AI_CALL` 由 [§4.3.4](#434-callback_timeout) 超时哨兵收敛。Phase 2 增加渠道对账，补齐 `t_contact_timeline`。⏳ 对账扫描与供应商回补不在 Phase 1 交付。

### 7.4 停摆计划检测



`StuckPlanReaper` 每 `interval-ms` 扫描一次；同时满足以下条件的计划判定为停摆：

- 非终态且 `renewal_pending = 0`
- `updated_at` 静默超过 `idle-minutes`（默认 75 分钟，必须大于 Outbox 默认约 65 分钟的自愈窗口）
- 没有步骤可被 `selectDueSteps` 或 `selectTimeoutSteps` 扫描
- 没有 `PENDING` 或 `PROCESSING` 的 Outbox 记录

命中后递增 `collection.plan.stuck` 并记录 ERROR 日志；不重建步骤，不重发触达。触达是否已发出无法确定时，自动外部动作可能产生重复外呼和合规投诉。

Reaper 不读取 Redis PEL。告警与 PEL 积压同时出现时，先按[基础设施 §3.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#33-异常恢复与死信)确认事件是否仍在重投。`REBUILD` / `ESCALATE` 的半成品与正常 `PLAN_COMPLETED` 不可区分，不纳入扫描，依赖事务回滚与 `PLAN_EXHAUSTED` 重投收敛。