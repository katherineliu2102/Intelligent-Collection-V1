# MOCASA 催收系统升级 — Phase 1 数据接入规格

> **版本**: Phase 1 · 仅覆盖菲律宾市场  
> **日期**: 2026-08-12（Phase 1 精简契约：两类事实事件，接入层为投影唯一写入者）  
> **关联文档**: [架构设计文档 §1.2.1](./MOCASA催收系统升级_Phase1_架构设计文档.md#121-上游数据接入)、[数仓 Pub/Sub 交付契约](./数仓_PubSub交付契约.md)、[Pub/Sub 契约索引](./contracts/README_t_ai_collection_PubSub契约.md)、[领域模型 §6 EventPayload](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#6-eventpayload-字段定义)、[基础设施交互规范 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream)、[核心引擎规格 §4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#4-计划生命周期与状态机)、[HANDOFF 模块 B](../HANDOFF.md)

---

## 目录

- [1. 定位与边界](#1-定位与边界)
  - [1.1 定位与边界](#11-定位与边界)
  - [1.2 关键边界索引](#12-关键边界索引)
- [2. PubSub 消费与消息路由](#2-pubsub-消费与消息路由)
  - [2.1 订阅与并发消费](#21-订阅与并发消费)
  - [2.2 消息路由与契约](#22-消息路由与契约)
  - [2.3 消费可靠性](#23-消费可靠性)
- [3. 入案处理：主链路、校验、幂等与边界](#3-入案处理主链路校验幂等与边界)
  - [3.1 入案快照主链路与边界](#31-入案快照主链路与边界)
    - [投影写入与单写者约束](#投影写入与单写者约束)
  - [3.2 上游字段校验与防御](#32-上游字段校验与防御)
  - [3.3 接入幂等键](#33-接入幂等键)
- [4. 阶段变更与 DPD 日切](#4-阶段变更与-dpd-日切)
  - [4.1 边界与职责](#41-边界与职责)
  - [4.2 读库与扫描](#42-读库与扫描)
  - [4.3 日切流程](#43-日切流程)
  - [4.4 产出事件](#44-产出事件)
  - [4.5 幂等与重跑](#45-幂等与重跑)
- [5. 领域事件发布](#5-领域事件发布)
- [6. 迁移与双写](#6-迁移与双写)
  - [6.1 联调隔离](#61-联调隔离)
  - [6.2 生产迁移：D-3 ~ D0 通知接管](#62-生产迁移d-3--d0-通知接管)
  - [6.3 历史数据与存量 replay](#63-历史数据与存量-replay)
- [附录](#附录)
  - [附录 B：可观测与对账](#附录-b可观测与对账)
  - [附录 C：联调与实现跟踪台账](#附录-c联调与实现跟踪台账)

---

## 1. 定位与边界

### 1.1 定位与边界

`collection-ingestion` 为北向入站边界（与 `collection-admin` 并列，见 [架构 §1.2](./MOCASA催收系统升级_Phase1_架构设计文档.md#12-系统边界北向入站)）：消费数仓发布的案件 PubSub（`caseEvent` / `repaymentEvent`），由 admin 调度订阅触发 DPD 日切；**校验、写案件投影、publish 领域事件**，并承担到期前通知迁移。**不做业务决策、不直接触达渠道**；接入层是 `t_ai_collection` 的**唯一写入者**，数仓不再直连业务库写表。消息契约 SSOT → [数仓契约 §3](./数仓_PubSub交付契约.md#3-数仓要发什么)。

```
上游 PubSub (caseEvent / repaymentEvent)
  → 校验 → 事务内写 t_ai_collection_inbox + 按 caseVersion upsert t_ai_collection
  → 提交后 publish EventBus（CASE_INGESTED | REPAYMENT_RECEIVED | CASE_BALANCE_UPDATED）
  → 引擎建计划 / 状态机 / 触达（非本文）

数仓每日 caseEvent 全量批次
  → 同一投影管道；已有活跃周期的案件只刷新 t_ai_collection，不产生领域事件

dailyRoll（admin 调度 → DpdStageRollHandler，03:35 PHT 起，见 §4）
  → 只读 t_ai_collection → STAGE_CHANGED | CASE_CEASED | CASE_INGESTED（复活）
```

### 1.2 关键边界索引

架构级模块入/出见 [架构 §1.2.1](./MOCASA催收系统升级_Phase1_架构设计文档.md#121-上游数据接入)；接入侧细节 SSOT 如下（原「前置决策」表已下沉至正文，避免重复）：


| 链路        | 决策要点                                                                   | SSOT                                                                                                 |
| --------- | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| PubSub 入案 | `caseEvent` / `repaymentEvent` 携带完整快照；幂等键 `eventId`、乱序防护 `caseVersion` | [数仓契约 §3](./数仓_PubSub交付契约.md#3-数仓要发什么)、[§2.2](#22-消息路由与契约)             |
| 案件投影      | 接入层为 `t_ai_collection` 唯一写入者；先提交投影再发领域事件                               | [§3.1 投影写入](#投影写入与单写者约束)                                                                             |
| 每日投影刷新    | 数仓每日逐案发布完整 `caseEvent`；已入催案只刷新投影                                       | [§3.1 投影写入](#投影写入与单写者约束)、[数仓契约 §4.5](./数仓_PubSub交付契约.md#45-每日刷新) |
| 还款 / 幂等   | 结清 → `REPAYMENT_RECEIVED`；部分还款 → `CASE_BALANCE_UPDATED`；周期内去重          | [§2.2](#22-消息路由与契约)、[§3.3](#33-接入幂等键)                                                                |
| DPD 日切    | 只读 `t_ai_collection`；阶段事件由日切**独占**产出                                   | [§4](#4-阶段变更与-dpd-日切)、[架构 dailyRoll 行](./MOCASA催收系统升级_Phase1_架构设计文档.md#122-应用入站)                     |
| 迁移切量      | 联调 `owner=NEW` + 白名单；生产 `LEGACY → MIGRATING → NEW`                     | [§6](#6-迁移与双写)、[基础设施 A.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a3-接入与-pubsub)                           |


---

## 2. PubSub 消费与消息路由



### 2.1 订阅与并发消费

**本节是案件 PubSub 消费参数的 SSOT**：键名、Phase 1 默认值、语义与调优边界在此定稿。[基础设施 A.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a3-接入与-pubsub) 仅索引 Nacos 热更属性与环境变量注入方式，不重复行为描述。

#### GCP 凭证与订阅


| 配置项                                 | Phase 1 默认值                                                          | 语义                                |
| ----------------------------------- | -------------------------------------------------------------------- | --------------------------------- |
| `GCP_PUBSUB_PROJECT`                | `fintech-all`                                                        | GCP 项目 ID                         |
| `GCP_PUBSUB_SUBSCRIPTION`           | 生产 `collection-ai-events-v1-sub`；联调 `collection-ai-events-test1-sub` | 案件 Consumer 专用订阅；须**独占消费**（无他机争抢） |
| `GOOGLE_APPLICATION_CREDENTIALS`    | 服务账号 JSON 路径                                                         | Consumer SA 凭证；**不入仓**            |
| `collection.ingestion.subscription` | 映射 `GCP_PUBSUB_SUBSCRIPTION`                                         | Spring 侧订阅名                       |


#### 消费参数


| 配置项                                         | Phase 1 默认值                    | 语义                                                                                                                |
| ------------------------------------------- | ------------------------------ | ----------------------------------------------------------------------------------------------------------------- |
| `collection.ingestion.enabled`              | 本地 / CI `false`；联调 / 生产 `true` | 是否启动 `PubSubCaseConsumer`                                                                                         |
| `collection.ingestion.ack-deadline-seconds` | **60**                         | **规格值**，须与 GCP Subscription 的 `--ack-deadline` 一致；应 ≥ 单条消息 p99 处理时长（投影事务 + 领域事件 publish）。应用侧不另设 deadline，仅文档化对齐目标 |
| `collection.ingestion.max-concurrency`      | **4**                          | 客户端 `FlowControlSettings` 拉取并发；过大易打满 DB 连接池或放大写冲突                                                                 |


> **调优**：Pilot 观测 p99 处理时长；若经常逼近 60s，同步调大 Subscription `--ack-deadline` 与 Nacos；生产配置与压测待办见 [HANDOFF](../HANDOFF.md#3-模块未闭合待办)。

#### Topic 拓扑


| 项             | 约定                                                                                                                                                                                                     |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **主链路（数仓直发）** | 数仓 Cloud Scheduler 触发 Publisher → 新系统专用 Topic → 新系统专用 Subscription；与旧 `collection-cases` **分离**；仅投递 `caseEvent` / `repaymentEvent`（[数仓契约 §1.2](./数仓_PubSub交付契约.md#12-gcp-资源)） |
| **生产**        | Project `fintech-all`；Topic `collection-ai-events-v1`；Subscription `collection-ai-events-v1-sub`                                                                                                       |
| **联调 / 测试**   | 同上 Project；Topic `collection-ai-events-test1`；Subscription `collection-ai-events-test1-sub`                                                                                                            |
| 配置映射          | `GCP_PUBSUB_SUBSCRIPTION` / `collection.ingestion.subscription`；Project → `GCP_PUBSUB_PROJECT`                                                                                                         |
| 订阅模式          | 数仓发布与消费者 ack 互不影响；须确认 Subscription **独占消费**（无他机争抢）                                                                                                                                                     |
| 发布节奏          | `caseEvent` 每日批处理；`repaymentEvent` 每 15 分钟扫描，且账务结清状态落库后至少等待 360 秒                                                                                                                                      |
| 保留与重放         | Topic 须开启消息保留，支持按时间点重放；重放复用原 `eventId` / `caseVersion`，由接入层幂等吸收                                                                                                                                        |
| 未知 `dataType` | ack 跳过并记指标；生产环境持续出现须告警                                                                                                                                                                                 |
| 历史 backlog    | 订阅创建前消息不补收；存量 §6.3 replay                                                                                                                                                                              |


> **旧 Topic（已废弃）**：`collection-cases` / `collection-cases-test1` 不再使用；禁止向旧 Topic 发布或新建订阅。

联调 `loan-id-whitelist`、日切扫描等其余 `collection.ingestion.*` 键 → [基础设施 A.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a3-接入与-pubsub)（部署索引；行为分别见 §4、[§6.1](#61-联调隔离)）。



### 2.2 消息路由与契约

契约 SSOT：[数仓契约 §3](./数仓_PubSub交付契约.md#3-数仓要发什么)（开发索引 → [contracts README](./contracts/README_t_ai_collection_PubSub契约.md)）。接入只做：**校验 → 写投影 → publish 内部领域事件**；不写 plan、不做业务决策。

#### 路由


| PubSub `dataType` | body `eventType`                     | 接入动作                       | 内部领域事件                                                |
| ----------------- | ------------------------------------ | -------------------------- | ----------------------------------------------------- |
| `caseEvent`       | `CASE_INGESTED`                      | 映射完整快照 → 写投影 → publish     | `CASE_INGESTED`                                       |
| `caseEvent`       | `CASE_STAGE_CHANGED` / `CASE_CEASED` | **违反契约** → poison ack + 告警 | 无（由 §4 日切产出）                                          |
| `repaymentEvent`  | `REPAYMENT`                          | 写投影后按 `isFullCleared` 分流   | 结清 → `REPAYMENT_RECEIVED`；部分 → `CASE_BALANCE_UPDATED` |
| 其他                | —                                    | ack 跳过                     | —                                                     |


> **来源分工**：数仓仅 publish `caseEvent/CASE_INGESTED` 与 `repaymentEvent/REPAYMENT`（还款延迟 360s，见 [数仓契约 §4.3](./数仓_PubSub交付契约.md#43-还款延迟)）。每日完整快照仍使用 `caseEvent`：首次/复活案件入催，已有周期的案件仅刷新投影。`STAGE_CHANGED` / `CASE_CEASED` / 复活 `CASE_INGESTED` 由 [§4 日切](#4-阶段变更与-dpd-日切)读投影后**独占**产出；两个来源同时发会导致计划被反复取消重建。

#### 公共信封（全部消息必填）


| 字段            | 规则                                                         |
| ------------- | ---------------------------------------------------------- |
| `eventId`     | 数仓 publish 前生成的 UUID；**业务幂等键**（Pub/Sub 原生 `messageId` 仅日志） |
| `caseId`      | 规范数字 loan 标识，可安全转 `Long`                                   |
| `caseVersion` | 同案单调递增；≤ 已入库版本 → ack 跳过（§3.3）                              |
| `occurredAt`  | ISO-8601，带 `+08:00`；写入投影 `updated_at`，缺失即 poison           |


Attribute：`dataType=caseEvent|repaymentEvent`（见 [数仓契约 §3.1](./数仓_PubSub交付契约.md#31-两类事件)）。



#### 2.2.1 `caseEvent`


| 项               | 约定                                                                                                                                                                                               |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 字段              | camelCase 新契约；完整快照嵌套 `borrower` / `device`（`pushToken`）；样例 → [数仓契约 §3.3](./数仓_PubSub交付契约.md#33-caseevent)、[领域 §6.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#62-逐事件-payload-字段) |
| 映射              | `CasePayloadMapper.mapAiSnapshot` → 扁平化 payload key（`borrower.name`→`name`，`device.pushToken`→`jpushToken`）                                                                                      |
| 校验              | §3.2；外部只受理 `eventType=CASE_INGESTED`，其余 → poison ack + 告警                                                                                                                                        |
| 写库              | 事务内写收件箱 + 按 `caseVersion` upsert `t_ai_collection`                                                                                                                                               |
| `CASE_INGESTED` | 校验通过 → 写投影；首次入催才 publish 并标记收件箱已发布 + `markIngested`（§3.3）                                                                                                                                        |
| 每日已有周期快照        | 更高 `caseVersion` 只刷新投影并标记收件箱 `SKIPPED`；不重复建计划，阶段变更靠 §4 日切                                                                                                                                        |




#### 2.2.2 `repaymentEvent`


| 项    | 约定                                                                                                                                                            |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 字段   | `eventType=REPAYMENT`；须带与入案同结构的完整快照 + `repayTime` / `paidAmount` / `isFullCleared` → [数仓契约 §3.4](./数仓_PubSub交付契约.md#64-repaymentevent) |
| 整笔结清 | `isFullCleared=true` → publish `REPAYMENT_RECEIVED` + `clearIngested`                                                                                         |
| 部分还款 | `isFullCleared=false` 且 `totalOutstanding` 有效（≥0）→ publish `CASE_BALANCE_UPDATED`                                                                             |
| 无效金额 | 缺 `totalOutstanding` 或金额为负 → ack + poison/DLQ                                                                                                                 |
| 写库   | 事务内写收件箱 + 按 `caseVersion` upsert `t_ai_collection`                                                                                                            |


幂等 / 乱序 → §3.3

### 2.3 消费可靠性

上游为 At-least-once 投递，接入层须保证幂等与不丢不毒：

- **ACK 语义**：投影事务提交 + 领域事件 publish 成功后才 ack；可重试失败 nack 触发重投。
- **处理顺序**：单 `caseId` 不要求全局有序；乱序由 `caseVersion` 收敛（§3.3）。
- **消息级去重**：见 [§3.3](#33-接入幂等键)（接入层，与引擎 `processed:` / `lock:plan:` 分层）。
- **毒丸消息**：必填缺失、格式错误等不可修复消息 → ack + poison 记录 / 告警（不重投）；可重试失败连续 N 次（建议 N=5）才转 DLQ 并告警。重放机制见 [基础设施 §3.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#33-异常恢复与死信)；**不得静默丢弃**。

---

## 3. 入案处理：主链路、校验、幂等与边界



### 3.1 入案快照主链路与边界

`caseEvent/CASE_INGESTED` 携带完整快照；数仓已算完，接入**不读库回填**。处置原则 → [§2.3](#23-消费可靠性)；字段样例 → [数仓契约 §3.3](./数仓_PubSub交付契约.md#33-caseevent)。

**主链路**


| 步骤     | 动作                                                         |
| ------ | ---------------------------------------------------------- |
| 1 · 接入 | 消费 → 校验（§3.2）→ `mapAiSnapshot`                             |
| 2 · 接入 | 事务内写收件箱 + 按 `caseVersion` upsert 投影（见 [投影写入](#投影写入与单写者约束)） |
| 3 · 接入 | 提交后 publish `CASE_INGESTED`；成功才标记收件箱已发布                    |
| 4 · 引擎 | `buildSnapshotFromEvent` → 冻结 `context_snapshot` 写 plan    |


`caseId` 全链路同值：PubSub `caseId` = `t_ai_collection.case_id` = `t_contact_plan.case_id`（规范数字 loan_id）。payload 字段 SSOT → [领域 §6.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#62-逐事件-payload-字段)；`mapAiSnapshot` 将嵌套 `borrower` / `device` 扁平化（如 `device.pushToken` → `jpushToken`），**无 field-map**。

**边界**


| 项               | 约定                                          |
| --------------- | ------------------------------------------- |
| 数据来源            | 入案主链路只读 PubSub 消息体；不写 plan、不调 `PlanFactory` |
| `pushToken` 缺失  | 仍 publish；渠道 Push→SMS fallback              |
| `repaymentUrl`  | 引擎按模板生成，非 PubSub 字段                         |
| `CaseService`   | 仅日切 / `PreFlightChecker` 读投影；不为入案补字段        |
| `dpd ≥ 91` 候选入催 | 接入不丢弃；引擎拒建；正常停催靠 §4 日切 `CASE_CEASED`        |






**投影写入（单写者）**

`t_ai_collection` 仅由 `AiCaseIngestionProcessor` 写入；数仓不得直连业务库，否则 `case_version` 可能倒退。


| 环节   | 约定                                                                        |
| ---- | ------------------------------------------------------------------------- |
| 事务   | 同事务写 `t_ai_collection_inbox`（`eventId` 唯一）+ 条件 upsert 投影；**提交后**才 publish |
| 版本   | 行锁比较：无行插入；更高版本覆盖；相同或更低 → 跳过（§3.3）                                         |
| 重投   | `PENDING` 收件箱 → 不重复写投影，只补发领域事件                                            |
| 每日刷新 | 已有 `ingested:{caseId}` 的 `caseEvent` → 只更新投影，收件箱 `SKIPPED`                |
| 一致   | 写投影与发事件共用同一份 `snapshotFields`                                             |


日切 / 守卫依赖投影新鲜度：当日 `caseEvent` 批次须已消费完毕（[数仓契约 §4.5](./数仓_PubSub交付契约.md#33-每日-caseevent-投影刷新)）。

### 3.2 上游字段校验与防御

处置口径 SSOT → [§2.3](#23-消费可靠性)。以下为字段级清单（引擎终态兜底 → [核心引擎 §3.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#32-并发与一致性模型)）。


| 类别                    | 处置                                            |
| --------------------- | --------------------------------------------- |
| 必填 / 格式不可修复           | ack + poison/DLQ + 告警（不 nack）                 |
| 非关键字段缺失               | payload 记缺省；下游 null 防御                        |
| 部分还款金额无效              | 缺 `totalOutstanding` 或金额为负 → ack + poison/DLQ |
| 外部阶段 / 停催事件           | ack + poison + 告警（仅 §4 日切产出）                  |
| `caseVersion` 未递增     | ack 跳过，投影不回退（§3.3）                            |
| `repaymentEvent` 先于入催 | 仍 publish；无活跃 plan 时引擎 noop                   |
| 瞬态失败                  | nack；超 N 次 → poison（§2.3）                     |


### 3.3 接入幂等键

At-least-once 下须在 publish 前去重。**DB 收件箱为最终判据**；Redis 为快筛（Pilot / 生产 `collection.ingestion.redis-dedup-enabled=true`）。与引擎 `processed:` / `lock:plan:` **分层，禁止混用**。


| 检查                      | Redis key                                                      | TTL | 命中处置                  |
| ----------------------- | -------------------------------------------------------------- | --- | --------------------- |
| 同 `eventId` 重投          | `collection:ingestion:dedup:msg:{eventId}`                     | 7d  | ack 跳过                |
| 同案乱序（`caseVersion` 未递增） | `collection:ingestion:case-version:{caseId}`                   | 90d | ack 跳过                |
| 周期内重复入催                 | `collection:ingestion:ingested:{caseId}`                       | 90d | ack 跳过；整笔结清 DEL       |
| 整笔结清                    | （清除）`ingested:{caseId}` 等                                      | —   | 允许下周期再入催              |
| 日切阶段变更                  | `collection:ingestion:dedup:stage:{caseId}:{stage}:{yyyyMMdd}` | 2d  | 同日同 stage 不重复 publish |
| 日切停催                    | `collection:ingestion:dedup:ceased:{caseId}`                   | 90d | 不重复 `CASE_CEASED`     |


键前缀须与旧催收 Redis 隔离（`collection:*`）。索引 → [基础设施 A.5](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a5-接入层-redis-键)；对账 → [附录 B](#附录-b可观测与对账)。

---

## 4. 阶段变更与 DPD 日切

`DpdStageRollHandler` 每日 **03:35 PHT** 起只读 `t_ai_collection`（数仓 `caseEvent` 批次约 **03:00 PHT** 完成，见 [数仓契约 §6](./数仓_PubSub交付契约.md#8-日切与数据可用时间)），比对阶段 / DPD 变化并 publish 内部领域事件。触发与分页扫描机制 → [基础设施 §5](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#5-定时调度cloud-scheduler--pubsub--应用订阅)。

> **B2 日切**：只读 `t_ai_collection.dpd` / `stage` / `collection_status`（**不重算** DPD）；产出 [§4.4](#44-产出事件) 事件；[§4.5](#45-幂等与重跑) 可重跑；**06:00 PHT** 前须完成（未完成告警）。

### 4.1 边界与职责


| 项              | 规格                                                                                                                      |
| -------------- | ----------------------------------------------------------------------------------------------------------------------- |
| **阶段变更来源**     | Phase 1 **唯一**来源为本 Job 读 `t_ai_collection`；外部 Topic 投递的阶段/停催事件按 poison 处置                                               |
| **前置条件**       | 当日 `caseEvent` 批次已消费完毕，投影基线完整；批次迟到应推迟日切并告警                                                                              |
| **模块职责**       | 只读案件表 → 组装完整快照 payload → publish `STAGE_CHANGED` / `CASE_CEASED` / 复活 `CASE_INGESTED`；**不写** `t_ai_collection` / plan 表 |
| **与 §3.1**     | 入案快照来自 PubSub；日切快照来自 `CaseService.getContextSnapshot(caseId)` 读 `t_ai_collection`                                       |
| **Phase 2 预留** | 外部分案信号若经 PubSub 接入，须补契约及与日切去重                                                                                           |


### 4.2 读库与扫描

**只读 `t_ai_collection`**（SSOT：[数仓契约 §2.4](./数仓_PubSub交付契约.md#4-t_ai_collection-当前案件表)）。DPD / 金额由数仓预计算，接入**不重算**。


| 模式           | 扫描范围                                                | 说明                                                         |
| ------------ | --------------------------------------------------- | ---------------------------------------------------------- |
| **联调 / 白名单** | `collection.ingestion.loan-id-whitelist` 内 `caseId` | L4b 默认；名单为空且未开全量扫描 → 跳过                                    |
| **生产全量**     | `case_id` keyset 分页（`findActiveCaseIdsAfter`）       | 须 `daily-roll-full-scan-enabled=true` + Redis 游标；06:00 前跑完 |


**`collectionStatus` / `collection_status`**：由数仓在 Publisher 侧计算并写入 Pub/Sub 完整快照；接入层原样写入投影列，**不重算**。数仓可实现推导与验收 → [数仓契约 §2.3](./数仓_PubSub交付契约.md#23-collection_status-推导)、[§7](./数仓_PubSub交付契约.md#7-验收清单)。

**在催 / 参与日切比对**（读 `CaseService` / `t_ai_collection`；左列为数仓赋值，右列为日切处置）：


| `collection_status` | 数仓赋值（Publisher / BQ 可实现） | 日切读投影后 |
| ------------------- | --------------------------- | -------- |
| `IN_COLLECTION` | `dpd >= -3` 且 loan 存在未结清 bill（`is_loan_clear != 0`）；`dpd <= 90` | 读 `dpd` / `stage` 与活跃 plan 比对；变化 → `STAGE_CHANGED` |
| `SETTLED` | loan 下全部 bill `is_loan_clear = 0`；`repaymentEvent` 须 `isFullCleared=true` 且 `totalOutstanding` / `remainingAmount` 归零 | `isRepaid()` → 跳过（活跃 plan 已由 `REPAYMENT_RECEIVED` 取消） |
| `CEASED` | `dpd >= 91`（与 [数仓契约 §2.1](./数仓_PubSub交付契约.md#41-dpd-与阶段映射) 一致；`stage` 可为空） | 仍有活跃 plan → `CASE_CEASED`（与 `dpd >= 91` 双保险） |
| （复活，非 status 枚举值） | 退款/冲正后不发 `repaymentEvent`；随下一次每日 `caseEvent` 刷回 `IN_COLLECTION` | 无活跃 plan 且 `total_outstanding > 0` → `CASE_INGESTED`（[数仓契约 §5](./数仓_PubSub交付契约.md#7-写入与事件触发矩阵)） |


### 4.3 日切流程


| 项                  | 规格                                                                                                                              |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| **DPD / Stage 来源** | `t_ai_collection.dpd`；`stage` 字段或 `Stage.fromDpd(dpd)`（映射见 [数仓契约 §2.1](./数仓_PubSub交付契约.md#41-dpd-与阶段映射)） |
| **Stage 回退**       | `dpd` 下降导致 stage 降低 → `STAGE_CHANGED`（引擎取消旧计划并重建）                                                                               |
| **触发**             | 03:35 PHT 起；`DpdStageRollHandler.dailyRoll()`                                                                                   |
| **分页**             | Redis 游标 + `daily-roll-batch-size`（默认 1000）；满批保留游标，下次 Job 续跑                                                                    |


**伪代码**：

```
for each caseId in scanBatch(§4.2):
  row = t_ai_collection
  if row SETTLED: continue
  if row.dpd >= 91 and hasActivePlan:
      publish CASE_CEASED(caseId, maxDpd=row.dpd)
  else if noActivePlan and row.total_outstanding > 0:
      publish CASE_INGESTED(full snapshot from row)   // 复活
  else if hasActivePlan and plan.stage != row.stage:
      publish STAGE_CHANGED(caseId, stage=row.stage, full snapshot)
```

### 4.4 产出事件


| 条件                         | 内部事件            | 接入动作                        | 引擎（外链）                                                        |
| -------------------------- | --------------- | --------------------------- | ------------------------------------------------------------- |
| `dpd` 1–90 且 stage 变化（含回退） | `STAGE_CHANGED` | publish 完整快照                | [引擎 §4.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#44-中断处理)            |
| `dpd ≥ 91` 且有活跃 plan       | `CASE_CEASED`   | publish `caseId` + `maxDpd` | [渠道 §4.2](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#42-完全停催d91) |
| 无活跃 plan + 有到期余额           | `CASE_INGESTED` | publish 完整快照（复活）            | [引擎 §4.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#42-计划创建)            |


payload 字段 → [领域 §6.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#62-逐事件-payload-字段)。

### 4.5 幂等与重跑

- 同日多次触发须**重跑安全**：不重复 publish 已生效的 `STAGE_CHANGED` / `CASE_CEASED`；复活 `CASE_INGESTED` 受 `ingested:{caseId}` 约束（§3.3）。
- Redis dedup：`dedup:stage:{caseId}:{target_stage}:{yyyyMMdd}`、`dedup:ceased:{caseId}`（§3.3）；与 PubSub 幂等键分层。
- 全量扫描：`RedisDailyRollDeduplicator` 维护当日游标与完成标记；完成后跳过后续触发。

---

## 5. 领域事件发布

接入经 `CollectionEventBus.publish` 发布领域事件；payload 字段以 [领域模型 §6.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#62-逐事件-payload-字段) 为准，传输 / 可靠性由 [基础设施 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream) 保证。接入层只"组装 payload + publish"，不感知 Stream 细节。

**接入层产出的事件（触发点）**：


| 事件                     | 触发点                                                        | 来源章节           |
| ---------------------- | ---------------------------------------------------------- | -------------- |
| `CASE_INGESTED`        | `caseEvent` / `CASE_INGESTED` 校验通过；或日切复活（无活跃 plan + 有到期余额） | §2.2 / §3、§4.4 |
| `REPAYMENT_RECEIVED`   | `repaymentEvent` / `isFullCleared=true`                    | §2.2           |
| `CASE_BALANCE_UPDATED` | `repaymentEvent` / 部分还款                                    | §2.2           |
| `STAGE_CHANGED`        | 日切 Stage 变化（含回退）                                           | §4.4           |
| `CASE_CEASED`          | 日切 DPD≥91                                                  | §4.4           |


**发布契约（接入侧约束）**：

- **顺序**：校验通过 → 投影事务提交 → publish → 标记收件箱已发布；不得先 publish 后写投影。
- **失败语义**：瞬态失败（投影写入 / publish / 下游）nack 重投 + §3.3 幂等；校验不可修复 → ack + poison（口径见 [§3.2](#32-上游字段校验与防御) / §2.3）。
- payload key 新增须先在 `CollectionEvent` 增补常量并同步 [领域 §6.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#62-逐事件-payload-字段)（跨模块契约，改前对齐）。

---

## 6. 迁移与双写

Phase 1 入案由数仓直发 PubSub 驱动（`caseEvent` / `repaymentEvent`）；新系统独立消费 PubSub、独立落库与触达计划。本节约定 **联调隔离**（非生产验证）与 **生产切量**（D-3~D0 通知职责迁移）两套配置，互不替代。

### 6.1 联调隔离

非生产或预发**验证全链路**（真实 PubSub 消费、日切、落库、触达）时使用：在不影响现网用户的前提下，确认接入与引擎行为符合正文 §2～§5。


| 项                                        | 建议                                                    |
| ---------------------------------------- | ----------------------------------------------------- |
| `collection.ingestion.enabled`           | `true`                                                |
| `collection.ingestion.loan-id-whitelist` | 可选；仅处理名单内 `caseId`，其余 ack 跳过；生产关闭与审计见 [HANDOFF](../HANDOFF.md#3-模块未闭合待办) |
| `collection.notification.owner`          | `**NEW**`：由新系统发 S0；**不启用** §6.2 生产灰度                  |
| 信贷 / 旧系统                                 | 对 whitelist 内账户 **停发** D-3~D0，避免双发                    |


> 联调隔离 **不等于** 生产切量。生产灰度、切片规则、信贷签字 → [§6.2](#62-生产迁移d-3--d0-通知接管) 与 [PRD §10](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)。



### 6.2 生产迁移：D-3 ~ D0 通知接管

对齐 [架构 ADR](./MOCASA催收系统升级_Phase1_架构设计文档.md#附录-a架构决策记录-adr) / [PRD F9](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)：**终态**为数仓直发 PubSub 驱动入案，新系统接管 D-3~D0 触达。


| 机制                                 | 生产规格                                                                                                                    |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `**LEGACY**`                       | 信贷仍发 D-3~D0 Push/SMS；新系统 **不** 发 S0（全量收 `caseEvent` 入案）                                                                 |
| `**MIGRATING**`（配置键仍可为 `PARALLEL`） | **全量入案**；按 `product` / `hash(caseId)` 切片：**切片内**新系统发 S0 且信贷**须停发** D-3~D0；**切片外**新系统不发 S0、信贷发                           |
| `**NEW**`                          | 信贷 **全量停发** D-3~D0，仅数仓推 `caseEvent`；新系统机器轨 **接管 S0**（[渠道编排 §7.4](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#74-s0--到期前提醒)） |
| **切换粒度**                           | Nacos `collection.notification.owner` + 切片规则；回滚 = `LEGACY`                                                              |
| **历史触达**                           | 信贷历史 Push/SMS **ETL** → `t_contact_timeline`，`source=ETL_SYNC`                                                          |


> **生产灰度原则**：
>
> - **触达 / owner 层切片，接入层默认 100% 收消息**（独立订阅、各自落库）。切片 SSOT 与信贷停发须同一口径（[基础设施 A.4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a4-迁移与触达)）。
> - 不推荐「新系统只接入部分消息」；若必须，接入层 whitelist + ack 跳过，须规划 replay；生产关闭与审计见 [HANDOFF](../HANDOFF.md#3-模块未闭合待办)。



### 6.3 历史数据与存量 replay


| 范围                 | 策略                                                                                                                  |
| ------------------ | ------------------------------------------------------------------------------------------------------------------- |
| **在催案件入新系统**       | 一次性 replay / 批量 `caseEvent/CASE_INGESTED`：DPD ∈ [-3, 90]、`collection_status != CEASED` 的活跃 `caseId`（名单由数仓导出 + 产品确认） |
| **已 CEASED / 已结清** | 不建计划；仅 ETL 时间线（可选）                                                                                                  |
| **联调子集**           | 可用 `loan-id-whitelist` 限定 `caseId`；见 [§6.1](#61-联调隔离)                                                               |
| **协调项**            | 切换窗口、replay 顺序、与信贷对账口径 → [PRD §10](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md) 跨团队跟踪                                       |


> **上线前待补**：S1+ 触达职责矩阵、切片外 plan 策略、双发告警、投诉跨系统冻结 — 在 [PRD §10](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md) 的生产切量 Runbook 跟踪。

---

## 附录

正文 §2～§6 为**已定规格**；附录供运维验收和上线签字，**不是**测试用例文档（测试计划见 [测试文档](./testing/MOCASA催收系统升级_Phase1_测试文档.md)）。**接入消费参数 SSOT** → §2.1；**Nacos 热更属性与部署索引** → [基础设施 附录 A](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录运行配置与环境)（A.3 接入 / A.4 迁移 / A.6 调度）；**工程 / Pilot 待办** → [HANDOFF](../HANDOFF.md#3-模块未闭合待办)。


| 附录    | 读者            | 内容                         |
| ----- | ------------- | -------------------------- |
| **B** | 运维 / SRE | 运行证据、对账与异常处置 |
| **C** | 架构 + 数仓 + 运维 + 接入 | **未签字**的数仓交付与上线门禁 |


> **去重约定**：字段、事件、日切行为以正文和[数仓契约](./数仓_PubSub交付契约.md)为准；C 只记录未签字门禁；工程 / Pilot 进度只在 [HANDOFF](../HANDOFF.md)；[基础设施 附录 A](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录运行配置与环境)只索引键名与热更属性。

## 附录 B：可观测与对账

命名约束见 [基础设施 §7.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#73-指标与日志)。

### B.1 运行指标与证据来源

阈值、告警级别、Dashboard 查询和当班责任人由运维在上线单 / Runbook 落定；本文只定义**必须可观察的证据**，不预设未经容量验证的阈值。

| 关注点 | 指标 / 证据 | 用于判断 |
| --- | --- | --- |
| 消费健康 | `ingestion_pubsub_lag_seconds`；`ingestion_pubsub_processed_total{result=ack|nack|dedup|poison}` | 堆积、重试、毒丸是否异常 |
| 入案与版本 | `ingestion_case_event_ingested_total`；`ingestion_stale_version_skipped_total` | 新案量是否合理；是否出现乱序或异常重放 |
| 数据质量 | `ingestion_validation_rejected_total{reason}`；poison / DLQ 记录 | 必填字段、`caseId`、联系方式等契约是否被违反 |
| 内部事件可靠性 | `ingestion_inbox_pending_publish`；收件箱 `PENDING` 记录 | 投影已写但领域事件未发布是否需要补发 |
| 日切结果 | `ingestion_stage_roll_events_total{type=STAGE_CHANGED|CASE_CEASED|CASE_INGESTED}`；日切完成记录 | 阶段、停催、复活是否在 06:00 PHT 前完成 |
| 数仓发布 | Publisher 按 `dataType` 的发布审计、批次完成信号 | 每日批次是否完整并可与消费结果对账 |

### B.2 每日对账与异常处置

| 核对项 | 对账方法 | 不一致时先查什么 | 对应签字门禁 |
| --- | --- | --- | --- |
| 发布与消费 | 按日、`dataType` 对比数仓发布审计与 `ack` / `nack` / `poison` / `dedup` | Publisher 失败重试、订阅堆积、DLQ | [数仓契约 §7 #14](./数仓_PubSub交付契约.md#7-验收清单) |
| 投影新鲜度 | 批次内 `caseId` 均已消费，`t_ai_collection.synced_at` 与批次完成时间可追溯 | 批次完成信号、Consumer 堆积、投影写入失败 | [C-W-01](#c-w-数仓交付签字) |
| 入案结果 | `CASE_INGESTED` 不超过新入催 `caseId`；每日刷新不新增计划 | `ingested:{caseId}`、inbox、事件消费记录 | [数仓契约 §7 #11](./数仓_PubSub交付契约.md#7-验收清单) |
| 版本与数据质量 | `stale_version_skipped`、validation rejected、poison / DLQ 无异常突增 | `caseVersion`、必填集、`caseId` 与联系方式样例 | [C-W-02](#c-w-数仓交付签字)～[C-W-04](#c-w-数仓交付签字) |
| 日切结果 | `STAGE_CHANGED` 与 DPD 跨边界案件一致；06:00 前有完成记录 | 批次门控、Redis 游标、日切扫描日志 | [C-W-01](#c-w-数仓交付签字) |


---



## 附录 C：联调与上线签字台账

> **用途**：只记录尚未签字、会阻塞数仓交付或生产上线的门禁；关闭必须附验收证据。  
> **不在此维护**：字段与事件规格 → [数仓契约](./数仓_PubSub交付契约.md)；接入 / 日切实现 → 正文 §2～§5；工程与 Pilot 进度 → [HANDOFF](../HANDOFF.md#3-模块未闭合待办)；生产切量 Runbook → 正文 §6 / [PRD §10](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)。  
> **状态**：⬜ 待签字 / 待验收 · 🟡 已定方案、待部署或演练；已闭合项不在本台账重复维护。

### C-W 数仓交付签字

数仓契约 §7 是验收规格 SSOT；本表只提取尚未完成的行，并将多处重复的“每日批次 / 日切门控”合并为一个门禁。

| ID | 上线门禁与通过标准 | 数仓契约验收项 | 验收证据 | 责任方 |
| --- | --- | --- | --- | --- |
| C-W-01 | **批次完成信号与日切门控**：每日 `caseEvent` 发布完成后有可审计信号；消费完成前不得推进日切，迟到须告警 | §7 #11；§6 | 批次标识、发布审计、消费完成记录、延迟演练结果 | 数仓 + 运维 + 接入 |
| C-W-02 | **消息必填集**：`caseEvent` / `repaymentEvent` 样例通过接入校验；缺失必填字段进入 poison / DLQ | §7 #1 | 已签字字段清单、通过与拒绝样例 | 数仓 + 接入 |
| C-W-03 | **主键质量**：`caseId` 非空、唯一、规范数字；非法记录有隔离与告警 | §7 #2 | 非法样例、隔离记录、告警截图 / 日志 | 数仓 |
| C-W-04 | **联系方式格式**：phone 为 E.164；email / token 可空但格式正确 | §7 #10 | 正常、缺失、非法格式样例的联调结果 | 数仓 + 接入 |
| C-W-05 | **还款延迟**：成功正向还款在账务结清落库至少 360 秒后发布 | §7 #9 | 带时间戳的账务、发布、消费审计链路 | 数仓 |
| C-W-06 | **复活样例**：退款 / 冲正后，下一每日 `caseEvent` 将案件刷新为 `IN_COLLECTION`；日切仅在无活跃 plan 且有余额时重入催 | §5 场景矩阵 | 输入快照、投影变化、`CASE_INGESTED` 结果 | 数仓 + 接入 |

### C-O 运维与拓扑签字


本组确认系统能可靠接收、保留、重放消息，且数仓已退出业务库写入路径。运行后的持续证据与异常排查见[附录 B](#附录-b可观测与对账)。

| ID | 上线门禁与通过标准 | 数仓契约验收项 | 验收证据 | 责任方 |
| --- | --- | --- | --- | --- |
| C-O-01 | **Topic 与 IAM**：案件 Topic 与调度 Topic 物理分离；Publisher SA / Consumer SA 权限按环境验收 | §7 #16 | Topic、Subscription、SA 与 IAM 清单；联调发布 / 消费记录 | 运维 + 数仓 + 接入 |
| C-O-02 | **保留与重放**：v1 Topic 已启用消息保留、DLQ 与重放窗口；旧 Topic 下线时点已登记 | §7 #13 | 保留 / DLQ 配置、按时间点重放演练、旧 Topic 下线 Runbook | 运维 + 数仓 |
| C-O-03 | **数仓退出业务库写路径**：数仓 SA 无业务库写权限；旧 `t_ai_collection_outbox` 无生产者或待投递记录后再删除 | §7 #7 | 权限审计、发布器下线确认、outbox 清理记录 | 数仓 + 运维 + service |
| C-O-04 | **日切调度与告警**：Scheduler / 调度 Topic / IAM 已验收；日切未在 06:00 PHT 前完成可告警并进入 Runbook | — | cron、调度消费记录、失败告警演练 | 运维 + 接入 |

### C-S 已闭合口径索引


以下不再作为待办重复维护；联调时直接以对应 SSOT 验收：

| 已闭合事项 | SSOT |
| --- | --- |
| 两类外部事件、完整快照与 `eventId` / `caseVersion` 规则 | [数仓契约 §3～§5](./数仓_PubSub交付契约.md#3-数仓要发什么) |
| 投影单写者、收件箱、ACK / poison 处置 | 正文 [§2.3](#23-消费可靠性)、[§3.1](#31-入案快照主链路与边界) |
| DPD / stage / `collection_status` 来源、日切扫描与阶段 / 停催 / 复活 | 正文 [§4](#4-阶段变更与-dpd-日切) |









| 生产全量扫描、PENDING 补发、Redis / DLQ / whitelist 等 Pilot 任务 | [HANDOFF §3.B / §3.D](../HANDOFF.md#3-模块未闭合待办) |
| 迁移切片、replay、通知 owner 与回滚 | 正文 [§6](#6-迁移与双写)、[PRD §10](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md) |


---

> MOCASA Collection System Upgrade — Phase 1 Data Ingestion Spec — 2026-06-29（定稿）

