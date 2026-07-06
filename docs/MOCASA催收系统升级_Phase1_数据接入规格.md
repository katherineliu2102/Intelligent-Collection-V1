# MOCASA 催收系统升级 — Phase 1 数据接入规格

> **版本**: Phase 1 · 仅覆盖菲律宾市场  
> **日期**: 2026-07-01  
> **关联文档**: [架构设计文档 §1.2.1](./MOCASA催收系统升级_Phase1_架构设计文档.md#121-上游数据接入)、[领域模型 §9 EventPayload](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#9-eventpayload-字段定义)、[基础设施交互规范 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream)、[核心引擎规格 §4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#4-计划生命周期与状态机)、[HANDOFF 模块 B](../HANDOFF.md)

---

## 目录

- [1. 定位与边界](#1-定位与边界)
  - [1.1 模块定位](#11-模块定位)
  - [1.2 职责边界](#12-职责边界)
  - [1.3 前置决策](#13-前置决策)
- [2. PubSub 消费与消息路由](#2-pubsub-消费与消息路由)
  - [2.1 订阅与并发消费](#21-订阅与并发消费)
  - [2.2 消息类型与路由](#22-消息类型与路由)
  - [2.3 消费可靠性](#23-消费可靠性)
- [3. 入案处理：主链路、校验、幂等与边界](#3-入案处理主链路校验幂等与边界)
  - [3.1 入案快照主链路与模块职责](#31-入案快照主链路与模块职责)
  - [3.2 上游字段校验与防御](#32-上游字段校验与防御)
  - [3.3 接入幂等键](#33-接入幂等键)
- [4. 阶段变更与 DPD 日切](#4-阶段变更与-dpd-日切)
  - [4.1 边界与职责](#41-边界与职责)
  - [4.2 读库与演进](#42-读库与演进)
  - [4.3 Max DPD 与日切流程](#43-max-dpd-与日切流程)
  - [4.4 产出事件](#44-产出事件)
  - [4.5 幂等与重跑](#45-幂等与重跑)
- [5. 领域事件发布](#5-领域事件发布)
- [6. 迁移与双写](#6-迁移与双写)
  - [6.0 联调隔离](#60-联调隔离)
  - [6.1 生产迁移：D-3 ~ D0 通知接管](#61-生产迁移d-3--d0-通知接管)
  - [6.2 历史数据与存量 replay](#62-历史数据与存量-replay)
- [附录](#附录)
  - [附录 B：可观测与对账](#附录-b可观测与对账)
  - [附录 C：联调与实现跟踪台账](#附录-c联调与实现跟踪台账)

---

## 1. 定位与边界

### 1.1 模块定位

接入层是系统北向入站边界之一（与 `collection-admin` 并列，见 [架构 §1.2](./MOCASA催收系统升级_Phase1_架构设计文档.md#12-系统边界北向入站)）：把上游信贷主系统的 PubSub 推送**校验、组装 payload 并 publish 领域事件**，另承担 DPD 日切与到期前通知迁移。**不做业务决策、不直接触达渠道、不回写旧库**。

```
上游 PubSub (case_push / repayment)
  → 接入：校验 / 组装 payload → EventBus：CASE_INGESTED | REPAYMENT_RECEIVED
  → 引擎：建计划 / 状态机 / 触达（非本文）

DpdStageRollHandler 每日 0:05 PHT
  → 只读旧库扫描在催名单（Phase 1 显式依赖，见 §1.3 决策与 §4）
  → EventBus：STAGE_CHANGED | CASE_CEASED
```

### 1.2 职责边界

**本文覆盖**（`collection-ingestion`）：

- 消费上游 PubSub（`case_push` / `repayment_push_and_load`）并路由（§2）
- 字段校验 / 清洗 / 乱序与脏数据处置（§3.2）
- 组装 EventPayload 并 publish 领域事件（§2 / §5）
- DPD 日切 → `STAGE_CHANGED` / `CASE_CEASED`（§4）
- 到期前通知迁移（§6.0 联调隔离 / §6.1 生产切量）
- 接入幂等键、PubSub ACK / 毒丸处置（§3.3 / §2.3）
- 未闭合字段 / 联调 / 实现项 → [附录 C](#附录-c联调与实现跟踪台账)

### 1.3 前置决策

按接入主链路排列：**PubSub 入案 → DPD 日切 → 迁移**。正文各节展开细节，此处只列须遵守的边界。

#### PubSub 入案

| 项 | 结论 |
|---|---|
| 读库 | 入案主链路**不读**旧库 `t_collection`；`jpushToken` 由上游 `case_push` 消息体携带（**已确认 2026-07**）；缺失时可降级读新库 `t_user_device_token`（见 [§3.1 读库](#读库)）。 |
| payload | 快照字段随 `CASE_INGESTED` payload 带出（[§3.1](#34-与-caseservice--profileservice-的调用边界)）；冻结写入由引擎完成（[§4.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#42-计划创建)）。 |
| `CASE_INGESTED` | **本催收周期**内首次 publish；同周期增量 ack 跳过；全额结清后 key 清除（§2.2 / §3.3）。 |
| `REPAYMENT_RECEIVED` | 校验通过即 publish；**不写**库；全额结清时 DEL `ingestion:ingested:{loan_id}`。 |

#### DPD 日切

| 项 | 结论 |
|---|---|
| 读库 | 并行期只读旧库 `t_collection` + `t_user_repayment_plan`（[§4.2](#42-读库与演进)）；切量后读新库见同节。 |
| 产出事件 | **仅**日切产出 `STAGE_CHANGED` / `CASE_CEASED`；`assign_signal` 预留、不路由（§2.2）。 |
| 写库 | **不回写**任何库（`t_collection` 由旧系统 `case_load` 加工，非信贷直写）。 |

#### 迁移

| 项 | 结论 |
|---|---|
| **联调隔离** | [§6.0](#60-联调隔离)：`owner=NEW` + 可选 `loan-id-whitelist`；不启用 §6.1 生产灰度。 |
| **生产切量** | [§6.1](#61-生产迁移d-3--d0-通知接管)：`LEGACY → MIGRATING → NEW`；接入全量收 PubSub，触达按切片切换。 |

---

## 2. PubSub 消费与消息路由

### 2.1 订阅与并发消费

#### GCP 凭证与订阅

| 配置项 | Phase 1 | 键名 SSOT |
|---|---|---|
| `GCP_PUBSUB_PROJECT` | 环境变量 / Nacos（不入仓） | [基础设施 A.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a3-接入与-pubsub) |
| `GCP_PUBSUB_SUBSCRIPTION` | **`collection-cases-ai-v1-sub`**（topic `collection-cases`；**禁止**复用 `collection-cases-sub`） | A.3 |
| `GOOGLE_APPLICATION_CREDENTIALS` | 服务账号 JSON 路径（不入仓） | A.3 |

#### 消费参数

| 配置项 | Phase 1 | 键名 SSOT |
|---|---|---|
| `collection.ingestion.ack-deadline-seconds` | **60** | [基础设施 A.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a3-接入与-pubsub) |
| `collection.ingestion.max-concurrency` | **4** | A.3 |
| `collection.ingestion.enabled` | 本地 / CI **`false`**；联调 / 生产 **`true`** | A.3 |

#### Topic 拓扑

| 项 | 约定 |
|---|---|
| Topic | `collection-cases`（`case_push` 与 `repayment_push_and_load` 混投） |
| 订阅 | 扇出独立订阅，与旧系统各 ack 互不影响 |
| 未知 `dataType` | ack 跳过 |
| 历史 backlog | 订阅创建前消息不补收；存量 §6.2 replay |

联调环境凭证、渠道沙箱、`loan-id-whitelist` 运维清单 → [基础设施 附录 A.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a3-接入与-pubsub)（凭证与名单**不入仓**）。

### 2.2 消息路由与契约

#### 路由

| `dataType` | 处理 | 领域事件 |
|---|---|---|
| `case_push` | 校验 → 组装 payload → 条件 publish | `CASE_INGESTED` |
| `repayment_push_and_load` | 校验 → publish | `REPAYMENT_RECEIVED` |
| `assign_signal` | ack 跳过 | — |
| 其他 | ack 跳过 | — |

| PubSub 不产出 | §4 日切 → `STAGE_CHANGED` / `CASE_CEASED` |

| `assign_signal` | Phase 1 **不路由**（现网无此 `dataType`）；**非**旧库 `t_collection_assign*` 人工分案；阶段变更仅 §4 日切。Phase 2 接入须补 JSON 契约及与日切去重 → [§4.1](#41-边界与职责) |

#### 2.2.1 `case_push`

| 项 | 约定 |
|---|---|
| 字段 | 上游 PubSub JSON；payload key 清单 → [领域模型 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)；**业务主键** `loan_id` ↔ payload `caseId` 见 [§3.1](#34-与-caseservice--profileservice-的调用边界)；JSON key 别名 → `field-map`（[A.6 #1](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a6-上线前联调签字接入)） |
| 校验 | §3.2 |
| 写库 | 无 |
| 读库 | 组装 payload 时按需读新库，见 [§3.1 读库](#读库) |

| publish 条件 | 动作 |
|---|---|
| 无 `ingestion:ingested:{loan_id}` | publish `CASE_INGESTED` |
| 已有 ingested key | ack |
| 同周期增量 | ack；阶段 → §4 日切 |

幂等 / 去重 → §3.3

#### 2.2.2 `repayment_push_and_load`

| 项 | 约定 |
|---|---|
| 字段 | [领域模型 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段) `REPAYMENT_RECEIVED` 行 |
| 处置 | publish `REPAYMENT_RECEIVED`；不写库 → [核心引擎 §4.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#44-中断处理) |
| 全额结清 | DEL `ingestion:ingested:{loan_id}`（+ 可选 `dedup:ceased`）→ §3.3、[A.6 #3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a6-上线前联调签字接入) |
| 部分还款 | 不清 ingested key |

幂等 → §3.3

### 2.3 消费可靠性

上游为 At-least-once 投递，接入层须保证幂等与不丢不毒：

- **ACK 语义**：写库（+ 可选 publish）在同一处理单元内完成；**全部成功**后 ack；任一失败 nack 触发重投。
- **处理顺序**：单 `loan_id` 不要求全局有序；乱序收敛见 §3.2。
- **消息级去重**：见 [§3.3](#33-接入幂等键)（接入层，与引擎 `processed:` / `lock:plan:` 分层）。
- **毒丸消息**：连续 N 次（建议 N=5）失败 → 写入接入死信表 / DLQ 并告警；重放机制复用 [基础设施 §2.1 DLQ](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#21-dlq-重放redrive)；**不得静默丢弃**。

---

## 3. 入案处理：主链路、校验、幂等与边界

<a id="34-与-caseservice--profileservice-的调用边界"></a>

### 3.1 入案快照主链路与模块职责

快照字段经 `case_push` → `CASE_INGESTED` payload 带出；引擎 `buildSnapshotFromEvent` 组装并冻结 `ContextSnapshot`。**入案主链路不读**旧库 `t_collection`、不写 plan。

**处理步骤**

| 步骤 | 模块 | 动作 |
|---|---|---|
| 1 | 接入 | 消费 `case_push` → 校验（§3.2）→ **组装 payload**（映射 PubSub 字段 + [按需读库](#读库) 补全消息缺失字段） |
| 2 | 接入 | publish `CASE_INGESTED` |
| 3 | 引擎 | `buildSnapshotFromEvent` → 写 `t_contact_plan.context_snapshot` |

**业务主键（`loan_id` / `caseId`）**

新旧系统并行时，**同一笔 loan 须用同一业务标识**，否则幂等、日切、灰度切片、对账均无法关联。

| 层 | 字段 | 规格 |
|---|---|---|
| 信贷 PubSub | 上游 key（待联调确认，常见 `loan_id`） | → 接入映射为 payload `caseId`（[C-I-02](#c-i-入案字段与-pubsub-映射)） |
| 事件 payload | **`caseId`** | SSOT：[领域模型 §9.2 `CASE_INGESTED`](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)；**数字 loan 标识**，非旧库 `t_collection.id`（hex 主键） |
| 旧库日切 | **`t_collection.loan_id`** | 与 payload `caseId` **同值**；扫描、比对、dedup key 均用此值 |
| 新库计划 | `t_contact_plan.case_id` 等 | 存同一业务 `caseId` |
| 灰度切片 | `hash(loan_id)` / `app_name` | 与信贷停发、触达 owner 须同一 loan 集合（[§6.1](#61-生产迁移d-3--d0-通知接管)、[C-M-01](#c-m-迁移与灰度)） |

上游 JSON key 名、类型与样例 → 附录 [C-I-02](#c-i-入案字段与-pubsub-映射)、[A.6 #8](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a6-上线前联调签字接入)。字段级对账口径 → [contracts/README](./contracts/README_ContextSnapshot契约对齐.md)（运行时映射实现见 `RealCaseService`）。

**Payload 组装**

接入层**只产出 payload**，不组装 `ContextSnapshot` JSON。职责边界：

| 环节 | 负责方 | 说明 |
|---|---|---|
| payload 字段清单与类型 | [领域模型 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段) | SSOT；接入按表填 key |
| PubSub 字段名对齐 | 接入 | 上游 JSON key 与契约不一致时，用 Nacos 别名表 `collection.ingestion.case-push.field-map` 映射到语义字段（联调确认 [A.6 #1](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a6-上线前联调签字接入)） |
| 语义映射与清洗 | 接入 | PubSub 语义字段 → payload key（清单 [§9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)）；清洗/推算规则见下表 |
| 按需读库补字段 | 接入 | 见 [读库](#读库)（**可选**：仅 `jpushToken` 缺失且 `enrich-jpush-token=true`） |
| payload → 快照 JSON | 引擎 | `buildSnapshotFromEvent`；字段路径见 [contracts](./contracts/README_ContextSnapshot契约对齐.md) |

**语义映射与清洗**（接入组装 payload 时执行；**引擎 payload→快照不做清洗**，原样写入）

| 类型 | 规则 | 规格落点 |
|---|---|---|
| PubSub key → 语义字段 | 默认与契约同名；不一致走 `field-map` | 本节 + [A.6 #1](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a6-上线前联调签字接入) |
| 语义字段 → payload key | 如 `loan_id`→`caseId`，`overdue_days`/`max_dpd`→`dpd` | [领域模型 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)（key 名 SSOT） |
| 金额推算 | `totalOutstanding` 无直传时由 principal/interest/overdue 等分项推算 | 接入实现（B1）；口径对齐 [contracts §12](./contracts/README_ContextSnapshot契约对齐.md) |
| phone / email 清洗 | E.164 `+63`；email 脏值→null | 规则见 `RealCaseService` JavaDoc；B1 须与之同口径 → [C-I-06](#c-i-入案字段与-pubsub-映射) |
| `strategyTone` | Phase 1 固定 `STANDARD` | 完整 FIRM 规则见 [渠道 §6.3.1](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#631-难催子条件计算口径ingestion-层)、[C-D-07](#c-d-日切与-dpd) |

> PubSub→payload 完整映射表、各字段上游来源及 B1/B2 实现缺口 → [附录 C](#附录-c联调与实现跟踪台账)。

**模块职责**（同一维度：各模块在入案链路中的分工）

| 模块 | 路径 | 职责 |
|---|---|---|
| **接入**（`collection-ingestion`） | 主链路 | 校验、组装 payload、publish 事件 |
| **引擎**（`collection-engine`） | 主链路 | 消费 `CASE_INGESTED`、组装 / 冻结 `ContextSnapshot`、写 plan |
| **CaseService**（`collection-service`，引擎 SPI） | 非主链路 | payload 缺失时引擎降级读旧库；**不由接入层直接调用** |

**接入层禁止**：不写 `t_contact_plan`、不调 `PlanFactory`、不读旧库 `t_collection` 组快照。

<a id="读库"></a>
<a id="35-jpushtoken-phase-1-数仓同步--接入-enrichment"></a>

**读库**

入案主链路的数据来源是 PubSub 消息体；上游 `case_push` **已携带 `jpushToken`**（运维确认 2026-07），与案件/画像字段同源，**主链路零读库**。以下为可选降级读库点：

| 场景 | 数据源 | 读取方 | 链路 | 约定 |
|---|---|---|---|---|
| `case_push` 缺 `jpushToken`（**可选降级**） | 新库 `t_user_device_token` | 接入 | 主链路 | 仅当 `enrich-jpush-token=true` 且消息缺失时查新库；正常路径不触发。无 token / 查失败：不写 key、warn，仍 publish（PUSH→SMS） |
| payload 缺失兜底 | 旧库 `t_collection` | CaseService | 非主链路 | 引擎降级路径；非 publish 前置 |
| 触达前还款守卫 | 旧库 | 引擎 `PreFlightChecker` | 非主链路 | 见 [核心引擎 §5](./MOCASA催收系统升级_Phase1_核心引擎规格.md#5-步骤执行管线) |
| 在催名单 / bill DPD | 旧库 `t_collection` + `t_user_repayment_plan` | 接入日切 | 非入案 | 见 [§4.2](#42-读库与演进) |

- `repaymentUrl`：引擎组装 snapshot 时按 `collection.repayment-url-template` 生成（非接入 payload 字段）。

### 3.2 上游字段校验与防御

接入是脏数据进入系统的第一道闸口（金额 / DPD / 状态须强校验）。**原则**：不可修复 → ack+poison；可重试 → nack；引擎层终态单调兜底乱序 → [核心引擎 §3.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#32-并发与一致性模型)。

| 类别 | 处置 |
|---|---|
| **必填 / 格式** | 缺必填或格式不可修复 → **ack + poison/DLQ** + 告警（**不 nack**，避免毒丸重投） |
| **null 防御** | 非关键字段缺失 → payload 记缺省；下游 null 防御 → [HANDOFF C2](../HANDOFF.md) |
| **乱序** | `repayment_push_and_load` 先于 `case_push` → 仍 publish（引擎无活跃计划 noop）；过期 `case_push`（`publish_time` < `ingestion:last_seen:{loan_id}`）→ ack 跳过 |
| **迟到** | `publish_time` 超 24h 的 `case_push` → ack + 审计；**不**触发首次 `CASE_INGESTED`（存量 §6.2 replay） |
| **瞬态失败** | 下游超时 / DB 不可达等 → nack 重投；超 N 次 → poison（§2.3） |

<a id="33-接入幂等键"></a>

### 3.3 接入幂等键

上游 **At-least-once** 投递：同一条 PubSub 可能重复到达，接入须在 **publish 前**去重，避免重复发领域事件。以下 key 只管**接入层**；引擎侧计划创建 / 步骤执行另有 `processed:`、`lock:plan:` 等（[核心引擎 §4.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#42-计划创建)），**禁止混用 key**。

**`case_push` 三道检查**（各管一类重复，依次判断，不互相替代）：

| 检查 | 防什么 |
|---|---|
| `dedup:case_push:{message_id}` | 同一条消息重投 |
| `last_seen:{loan_id}` | 同 loan 更旧 `publish_time` 的乱序消息（§3.2） |
| `ingested:{loan_id}` | 本催收周期已 publish 过 `CASE_INGESTED` 后的增量推送（阶段靠 §4 日切） |

| 场景 | Redis / 内存 key | TTL | 命中处置 |
|---|---|---|---|
| `case_push` 同 message 重投 | `ingestion:dedup:case_push:{message_id}` | 7d | ack 跳过 |
| `case_push` 乱序（旧 `publish_time`） | `ingestion:last_seen:{loan_id}` | 90d | ack 跳过 |
| `case_push` 周期内重复入催 | `ingestion:ingested:{loan_id}` | 90d | ack 跳过；全额结清 DEL（§2.2.2） |
| `repayment_push_and_load` 同 message 重投 | `ingestion:dedup:repayment:{user_id}:{message_id}` | 7d | ack 跳过 |
| `repayment_push_and_load` 全额结清 | （清除）`ingestion:ingested:{loan_id}`、`dedup:ceased:{loan_id}`（可选） | — | 允许下一周期再 `CASE_INGESTED` |
| 日切阶段变更 | `ingestion:dedup:stage:{loan_id}:{target_stage}:{yyyyMMdd}` | 2d | 同日同目标 stage 不重复 publish |
| 日切停催 | `ingestion:dedup:ceased:{loan_id}` | 90d | 不重复 `CASE_CEASED`；结清时可 DEL |

- 生产切 Redis 后 key 前缀挂 [基础设施 §3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#3-运行时状态redis-kv) 同一实例；键名汇总见 [基础设施 A.5](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a5-接入层-redis-键)。**须与旧催收 Redis 物理或前缀隔离**（新系统 `ingestion:*` / `ai:*`）。对账指标见 [附录 B](#附录-b可观测与对账)。

---

## 4. 阶段变更与 DPD 日切

`DpdStageRollHandler` 每日 **0:05 PHT** 重算 Max DPD 并 publish 阶段事件（生产经 XXL-Job，见 [基础设施 §4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#4-定时调度xxl-job)）。

> **Phase 1 日切交付（B2）**：在 [§4.2 并行期读库](#42-读库与演进) 上跑通 [§4.3](#43-max-dpd-与日切流程) hybrid 重算，产出 [§4.4](#44-产出事件) 事件，[§4.5](#45-幂等与重跑) 可重跑。切量后读库切换（§4.2）与 FIRM / `strategyTone`（[C-D-07](#c-d-日切与-dpd)）Phase 1 固定 `STANDARD`。

### 4.1 边界与职责

| 项 | 规格 |
|---|---|
| **阶段变更来源** | Phase 1 **唯一**来源为 DPD 日切；`assign_signal`（§2.2）不路由 |
| **模块职责** | B2：只读库 → 重算 Max DPD → publish 事件；**不写**旧库 / 新库 |
| **与 §3.1 读库** | 入案主链路不读旧库组快照（§3.1）；日切需**全量在催名单** → 读旧库是 B2 专属，非矛盾 |
| **Phase 2 预留** | 若接入 `assign_signal`，须定义 JSON 契约及与日切的优先级 / 去重（避免同案同日双重 `STAGE_CHANGED`） |

### 4.2 读库与演进

B2 **只读**；定义读哪张表，不算 DPD。未闭合项 → [附录 C（C-D-04～05、C-X-02～03）](#附录-c联调与实现跟踪台账)。

| 阶段 | 在催名单 | bill DPD | 说明 |
|---|---|---|---|
| **并行期（B2 必做）** | 旧库 `t_collection` | 旧库 `t_user_repayment_plan` | 旧系统并行，数据实时有效；**无需 ETL 至** `ai_collection_db` |
| **切量后** | 新库 `t_contact_plan` | ETL 至 `ai_collection_db` 或信贷 API | 前提：§6.2 replay 完成；bill 迁移须在旧系统停写前完成 |

**并行期扫描条件**（在催名单）：

- `t_collection`：`full_repay_time IS NULL` 且 `total_not_paid > 0`；每 `loan_id` 取最新行（排序键待 [C-D-05](#c-d-日切与-dpd) 闭合）

### 4.3 Max DPD 与日切流程

**口径 SSOT**（不展开，见 [渠道编排 §4.1 / §5.2 / §6.3.1](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#631-难催子条件计算口径ingestion-层)）：

| 项 | 规格 |
|---|---|
| **Loan Max DPD** | 整笔 loan 下各 bill **`max(bill_dpd)`**；`bill_dpd = DATE_DIFF(as_of_date, due_date, DAY)`（bill 已到期且未结清） |
| **Stage 映射** | Max DPD → `Stage.fromDpd`（S0[-3,0]…S4[31,90]；≥91 停催，**不是** Stage） |
| **Stage 回退** | Max DPD **下降**时须 publish `STAGE_CHANGED`（目标 stage 降低）；引擎取消旧计划并重建（渠道 §5.2） |

**并行期重算（B2 交付标准）**：

| 项 | 规格 |
|---|---|
| 触发 | 每日 **0:05 PHT**（`Asia/Manila`），`DpdStageRollHandler.dailyRoll()` |
| 读库 | [§4.2 并行期](#42-读库与演进) |
| 算法 | **hybrid**：优先 bill 级公式；缺 bill → fallback `DATE_DIFF(TODAY_PHT, repayment_date)` |
| 比对 | `oldStage = Stage.fromDpd(old_max_dpd)`，`newStage = Stage.fromDpd(new_max_dpd)` |

> 实现与联调未闭合项 → [附录 C（C-D-* / C-B-*）](#附录-c联调与实现跟踪台账)。

**日切伪代码**：

```
for each active loan_id (§4.2 并行期读库):
  oldMaxDpd = current max_dpd
  newMaxDpd = recompute hybrid per above
  if newMaxDpd == oldMaxDpd: continue
  if newMaxDpd >= 91:
      publish CASE_CEASED(caseId, maxDpd)   // 不写旧库
  else if Stage.fromDpd(newMaxDpd) != Stage.fromDpd(oldMaxDpd):
      publish STAGE_CHANGED(caseId, stage=newStage)
```

### 4.4 产出事件

| 条件 | 事件 | 接入动作 | 引擎（外链） |
|---|---|---|---|
| Stage 变化，DPD 1–90（含回退） | `STAGE_CHANGED` | publish（`caseId` + `stage`＝**目标阶段**） | [§4.4 中断处理](./MOCASA催收系统升级_Phase1_核心引擎规格.md#44-中断处理)：取消旧计划并重建 |
| DPD ≥ 91 | `CASE_CEASED` | publish（`caseId` + `maxDpd`）；**不写**旧库 `colleciton_status` | cancel 活跃计划、不再 create；[渠道 §4.2](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#42-完全停催d91) |

payload 字段 → [领域 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)。

### 4.5 幂等与重跑

- 日切须**重跑安全**：同日多次执行不重复发 `STAGE_CHANGED` / `CASE_CEASED`。
- 幂等：§3.3 日切 dedup key（与 PubSub 接入幂等键**分层**，互不替代）；noop 条件为「目标 stage 已生效 / 已 publish `CASE_CEASED`」。

---

## 5. 领域事件发布

接入经 `CollectionEventBus.publish` 发布领域事件；payload 字段以 [领域模型 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段) 为准，传输 / 可靠性由 [基础设施 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream) 保证。接入层只"组装 payload + publish"，不感知 Stream 细节。

**接入层产出的事件（触发点）**：

| 事件 | 触发点 | 来源章节 |
|---|---|---|
| `CASE_INGESTED` | `case_push` 首次入催且校验通过后 | §2.2 / §3 |
| `REPAYMENT_RECEIVED` | `repayment_push_and_load` 校验通过（不写库） | §2.2 |
| `STAGE_CHANGED` | 日切 Stage 变化（含回退） | §4.4 |
| `CASE_CEASED` | 日切 DPD≥91 | §4.4 |

**发布契约（接入侧约束）**：

- **顺序**：校验通过后 publish；`repayment_push_and_load` 无写库前置。
- **失败语义**：瞬态失败（publish / 下游）nack 重投 + §3.3 幂等；校验不可修复 → ack + poison（口径见 [§3.2](#32-上游字段校验与防御) / §2.3）。
- payload key 新增须先在 `CollectionEvent` 增补常量并同步 [领域 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)（跨模块契约，改前对齐）。

---

## 6. 迁移与双写

并行运行期：新系统独立消费 PubSub、独立落库；旧系统继续维护 `t_collection` 与现网触达。本节约定 **联调隔离**（非生产验证）与 **生产切量**（通知职责迁移）两套配置，互不替代。

| 章节 | 目的 |
|---|---|
| **[§6.0](#60-联调隔离)** | 非生产环境：限制处理范围、避免与信贷/旧系统双发 |
| **[§6.1](#61-生产迁移d-3--d0-通知接管)** | 生产：D-3~D0 触达职责自信贷迁至新系统 |
| **[§6.2](#62-历史数据与存量-replay)** | 存量案件入新系统与 replay |

<a id="60-联调隔离"></a>

### 6.0 联调隔离

非生产或预发**验证全链路**（真实 PubSub 消费、日切、落库、触达）时使用：在不影响现网用户的前提下，确认接入与引擎行为符合正文 §2～§5。

| 项 | 建议 |
|---|---|
| `collection.ingestion.enabled` | `true` |
| `collection.ingestion.loan-id-whitelist` | 可选；仅处理名单内 `loan_id`，其余 ack 跳过，缩小影响面（[C-P-08](#c-p-基础设施与可靠性)） |
| `collection.notification.owner` | **`NEW`**：由新系统发 S0；**不启用** §6.1 生产灰度 |
| 信贷 / 旧系统 | 对 whitelist 内账户 **停发** D-3~D0，避免双发 |

> 联调隔离 **不等于** 生产切量。生产灰度、切片规则、信贷签字 → [§6.1](#61-生产迁移d-3--d0-通知接管)、附录 [C-M](#c-m-迁移与灰度)。

<a id="61-生产迁移d-3--d0-通知接管"></a>

### 6.1 生产迁移：D-3 ~ D0 通知接管

对齐 [架构 ADR](./MOCASA催收系统升级_Phase1_架构设计文档.md#附录-a架构决策记录-adr) / [PRD F9](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)：**终态**为信贷主系统仅 `case_push`，新系统接管 D-3~D0 触达。

| 机制 | 生产规格 |
|---|---|
| **`LEGACY`** | 信贷仍发 D-3~D0 Push/SMS；新系统 **不** 发 S0（全量收 `case_push` 入案） |
| **`MIGRATING`**（配置键仍可为 `PARALLEL`） | **全量入案**；按 `app_name` / `hash(loan_id)` 切片：**切片内**新系统发 S0 且信贷**须停发** D-3~D0；**切片外**新系统不发 S0、信贷发。`ExecutionGuard` 仅本系统 dedup，**不能**替代信贷停发 |
| **`NEW`** | 信贷 **全量停发** D-3~D0，仅推 `case_push`；新系统机器轨 **接管 S0**（[渠道编排 §7.4](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#74-s0--到期前提醒)） |
| **切换粒度** | Nacos `collection.notification.owner` + 切片规则；回滚 = `LEGACY` |
| **历史触达** | 信贷历史 Push/SMS **ETL** → `t_contact_timeline`，`source=ETL_SYNC` |

> **生产灰度原则**：
> - **触达 / owner 层切片，接入层默认 100% 收消息**（独立订阅、各自落库）。切片 SSOT 与信贷停发须同一口径（[A.6 #4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a6-上线前联调签字接入)、[C-M-01](#c-m-迁移与灰度)）。
> - 不推荐「新系统只接入部分消息」；若必须，接入层 whitelist + ack 跳过，须规划 replay（[C-P-08](#c-p-基础设施与可靠性)）。

<a id="62-历史数据与存量-replay"></a>

### 6.2 历史数据与存量 replay

| 范围 | 策略 |
|---|---|
| **在催案件入新系统** | 一次性 replay / 批量 `case_push`：DPD ∈ [-3, 90]、`colleciton_status != CEASED` 的活跃 `loan_id`（具体名单由信贷导出 + 产品确认） |
| **已 CEASED / 已结清** | 不建计划；仅 ETL 时间线（可选） |
| **联调子集** | 可用 `loan-id-whitelist` 限定 `loan_id`；见 [§6.0](#60-联调隔离) |
| **协调项** | 切换窗口、replay 顺序、与信贷对账口径 → [PRD §10](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md) 跨团队跟踪 |

> **上线前待补**：S1+ 触达职责矩阵、切片外 plan 策略、双发告警、投诉跨系统冻结 — 跟踪见 [C-M](#c-m-迁移与灰度) / [C-X](#c-x-phase-2跟踪占位不阻塞)。

---

## 附录

正文 §2～§6 为**已定规格**；附录供运维、联调签字与实现跟踪，**不是**测试用例文档（测试计划见 [测试文档](./testing/MOCASA催收系统升级_Phase1_测试文档.md)）。**配置键与签字索引 SSOT** → [基础设施 附录 A](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录运行配置与环境)（A.3 接入 / A.4 迁移 / A.6 签字）。

| 附录 | 读者 | 内容 |
|---|---|---|
| **B** | 运维 / SRE | 运行时指标与生产对账 |
| **C** | 接入负责人 + 信贷/运维 | **未闭合**项台账（字段确认、实现缺口、生产切量） |

> **去重约定**：待办明细只在 **C**；[基础设施 A.6](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a6-上线前联调签字接入) 只映射 ID；B 不列待办。

## 附录 B：可观测与对账

命名约束见 [基础设施 §6.2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#62-可观测性接入约束)。

### B.1 指标

| 指标 | 说明 |
|---|---|
| `ingestion_pubsub_lag_seconds` | 拉取 → 处理完成 |
| `ingestion_pubsub_processed_total{result=ack\|nack\|dedup\|poison}` | 消费结果 |
| `ingestion_case_push_ingested_total` | 首次 `CASE_INGESTED` 次数 |
| `ingestion_jpush_enriched_total` | 消息无 token、经 §3.1 读库补全（关联 [C-I-10](#c-i-入案字段与-pubsub-映射)） |
| `ingestion_stage_roll_events_total{type=STAGE_CHANGED\|CASE_CEASED}` | 日切产出 |
| `ingestion_validation_rejected_total{reason}` | 校验 / 脏数据拒绝 |

### B.2 对账规则

| 维度 | 规则 |
|---|---|
| 消息量 | 日级：`case_push` 条数 ≈ 信贷推送日志 |
| 入案量 | `CASE_INGESTED` ≤ 新入催 `loan_id` 数 |
| 日切 | `STAGE_CHANGED` 与 DPD 跨边界案件数一致 |
| jpush 补全 | `ingestion_jpush_enriched_total` 突增 → 排查 [C-I-10](#c-i-入案字段与-pubsub-映射) 数仓延迟 |

---

<a id="附录-c联调与实现跟踪台账"></a>

## 附录 C：联调与实现跟踪台账

> **SSOT**：未签字、未实现、口径未闭合项均在此维护；正文与 [基础设施 A.6](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a6-上线前联调签字接入) 只引用 ID，不重复描述。  
> **状态**：⬜ 待确认 · 🟡 规格已定 / 待实现 · ✅ 已闭合（闭合后改状态并同步 A.6 / [HANDOFF](../HANDOFF.md)）

### C.0 类别图例

| 前缀 | 含义 |
|---|---|
| **C-I** | 入案：PubSub / payload 字段与映射 |
| **C-D** | 日切：DPD 重算与阶段事件 |
| **C-P** | 基础设施：PubSub 拓扑、Redis、ACK / DLQ |
| **C-M** | 迁移：灰度、replay、通知接管 |
| **C-B** | 代码：B1 Consumer / B2 日切 / 规格待补 |
| **C-X** | Phase 2，不阻塞 Phase 1 |

<a id="c-i-入案字段与-pubsub-映射"></a>

### C-I 入案字段与 PubSub 映射

**payload key SSOT**：[领域模型 §9.2 `CASE_INGESTED`](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)。下表补充上游 PubSub 是否提供、语义名、接入侧转换——多数 **⬜ 待信贷联调**（[A.6 #8](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a6-上线前联调签字接入)）。

| ID | 项 | 现状 | 待确认 / 待对齐 | 责任方 | 关联 |
|---|---|---|---|---|---|
| C-I-01 | PubSub JSON **key 名** vs 契约 | `field-map` 已定义配置项，**代码未读** | 上游样例 JSON + 别名表（A.6 #1） | 信贷 + 接入 | §3.1、A.6 #1 |
| C-I-02 | **`caseId`** | payload 必填 | PubSub 字段名（`loan_id`？）与类型（数字串，非 hex `t_collection.id`） | 信贷 | §9.2 |
| C-I-03 | **`userId`** | 缺省取 `caseId` | 上游是否稳定提供 `user_id` | 信贷 | §9.2 |
| C-I-04 | **`stage`** | payload 必填 | 入案时由接入算还是上游带；与 `dpd` 关系 | 信贷 + 接入 | §9.2、§3.2 |
| C-I-05 | **`dpd`** | 映射 `overdue_days` / `max_dpd`（口径见 RealCaseService） | PubSub 字段名；与 `t_collection.overdue_days` 是否一致 | 信贷 | §3.1 |
| C-I-06 | **`phone` / `email`** | 清洗规则在 `RealCaseService`（E.164、脏 email→null） | B1 组装 payload 时**须与之同口径**；是否由上游预清洗 | 接入 + 信贷 | §3.1 |
| C-I-07 | **`totalOutstanding`** | 无直传时由分项推算（principal/interest/overdue…） | PubSub 直传字段名；**推算公式与分项字段名**未写入接入规格 | 信贷 + 接入 | [contracts §12](./contracts/README_ContextSnapshot契约对齐.md) |
| C-I-08 | **`penaltyAmount`** | §9.2 可选 | PubSub 源字段（是否 = `overdue` 罚息列） | 信贷 | §9.2 |
| C-I-09 | **`product` / `dueDate` / `fullRepayTime` / `name`** | §9.2 可选 | 各字段 PubSub 源名与格式（日期 ISO 串） | 信贷 | §9.2 |
| C-I-10 | **`jpushToken`** | **`case_push` 消息体**（已确认 2026-07）；缺则可选读 `t_user_device_token` | 联调验证消息体含 token；enrichment 默认关 | 信贷 + 运维 | §3.1 读库 |
| C-I-11 | **`case_push` 必填集** | §3.2 原则有，**未列字段级必填清单** | 除 `caseId`/`stage` 外哪些缺失 → poison | 接入 + 信贷 | §3.2 |
| C-I-12 | **`message_id` / 推送频率** | 幂等与乱序依赖 | 上游字段名、格式、单调性、重投是否同 id（A.6 #2） | 信贷 | §3.3、A.6 #2 |
| C-I-13 | **`REPAYMENT_RECEIVED` 字段** | §9.2 行 | 全额结清判定条件（DEL `ingested` key 触发点）（A.6 #3） | 信贷 | §2.2.2、A.6 #3 |
| C-I-14 | **PubSub→payload 映射表** | 🟡 分散在 §9.2、`RealCaseService` | B1 落地前须抽成**接入侧单表**（可版本化）；与 C-I-01 同步闭合 | 接入 | §3.1、[C-B-01](#c-b-代码实现) |
| C-I-15 | **`dataType` 码值稳定** | 规格 `case_push` / `repayment_push_and_load` | 联调确认无新增/改名（A.6 #7） | 信贷 + 运维 | §2.1、A.6 #7 |
| C-I-16 | **未知 `dataType`** | 当前 ack 跳过 | 是否告警 / DLQ / 指标计数 | 信贷 + 接入 | §2.2 |

<a id="c-d-日切与-dpd"></a>

### C-D 日切与 DPD

| ID | 项 | 现状 | 待确认 / 待对齐 | 责任方 | 关联 |
|---|---|---|---|---|---|
| C-D-01 | **Max DPD 公式** | ✅ SSOT：[渠道编排 §6.3.1](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#631-难催子条件计算口径ingestion-层) | — | — | §4.3 |
| C-D-02 | **`DpdStageRollHandler`** | 🟡 **占位**（无扫库/重算/发事件） | B2 全量实现 + 单测 | 接入 | `DpdStageRollHandler.java`、[C-B-02](#c-b-代码实现) |
| C-D-03 | **并行期 hybrid 算法** | 规格：bill 优先，缺则 `repayment_date` fallback | bill 缺失判定；fallback 与 `t_collection.overdue_days` 偏差可接受范围 | 接入 + 信贷 | §4.3 |
| C-D-04 | **`t_user_repayment_plan`** | 日切 bill 数据源（并行期读旧库） | 表结构、bill 粒度、与 loan 关联键、联调库是否有完整 bill | DBA + 信贷 | §4.2 |
| C-D-05 | **在催扫描 SQL** | 规格：`full_repay_time IS NULL` 且 `total_not_paid > 0`，每 `loan_id` 最新行 | 「最新行」排序键；与现网 `case_load` 逻辑一致 | 信贷 + DBA | §4.2 |
| C-D-06 | **日切 `old_max_dpd`** | 伪代码有 `oldMaxDpd` | 取自 `t_collection.overdue_days` 还是上日重算缓存 | 接入 | §4.3 |
| C-D-07 | **`strategyTone` / FIRM** | Phase 1 固定 `STANDARD`（§3.1 / 渠道 §6.3.1） | 难催子条件入案/日切/还款何时计算 | 接入 + service | 渠道 §6.3.1 |
| C-D-08 | **XXL-Job `dailyRoll`** | 规格 0:05 PHT | Job 注册、环境、与 B2 联调 | 运维 + 接入 | [基础设施 §4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md) |

<a id="c-p-基础设施与可靠性"></a>

### C-P 基础设施与可靠性

| ID | 项 | 现状 | 待确认 / 待对齐 | 责任方 | 关联 |
|---|---|---|---|---|---|
| C-P-01 | **GCP topic / project / 订阅** | 规格 `collection-cases` + `collection-cases-ai-v1-sub` | project 名、跨 project 订阅、服务账号权限（A.6 #6–#7） | 运维 + 信贷 | §2.1、A.6 #6–#7 |
| C-P-02 | **Topic 终态生命周期** | ⬜ | 旧系统下线后是否仍 publish（A.6 #9） | 信贷 + 架构 | A.6 #9 |
| C-P-03 | **Redis 隔离与切生产** | 内存版 Phase 1 | 切 Redis 时点；与旧系统 `db_collection` key 隔离验收 | 运维 + 接入 | §3.3 |
| C-P-04 | **结清 DEL `dedup:ceased`** | 规格写「可选」 | 全额结清是否必须 DEL；结清后再 DPD≥91 边界 | 接入 + 信贷 | §3.3 |
| C-P-05 | **毒丸阈值 N=5 / DLQ** | 建议值 | 是否采纳；DLQ 表结构；与 [基础设施 §2.1 DLQ](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#21-dlq-重放redrive) 对齐 | 接入 + 运维 | §2.3 |
| C-P-06 | **ACK 单元** | 规格：publish 成功后 ack | B1 读库失败仍 publish 时是否在同事务写 dedup key | 接入 | §2.3、§3.1 |
| C-P-07 | **`ack-deadline` 调优** | 默认 60s（§2.1） | 长耗时读库 / 重试场景是否需加大 | 接入 + 运维 | §2.1 |
| C-P-08 | **联调 whitelist** | 可选配置项 | 生产关闭流程与审计 | 接入 + 运维 | §6.0、[基础设施 A.3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a3-接入与-pubsub) |

<a id="c-m-迁移与灰度"></a>

### C-M 迁移与灰度

> **联调隔离**见 [§6.0](#60-联调隔离)。下表 **C-M-01～04 为生产切量**，与联调配置独立。

| ID | 项 | 现状 | 待确认 / 待对齐 | 责任方 | 关联 |
|---|---|---|---|---|---|
| C-M-01 | **通知灰度切片** | `app_name` / `hash(loan_id)` | 与信贷停发 D-3~D0 同一口径（A.6 #4） | 产品 + 信贷 | §6.1 |
| C-M-02 | **存量 replay** | DPD∈[-3,90] 活跃 loan | 名单、批次、与 `ingested` key 关系（A.6 #5） | 产品 + 信贷 | §6.2 |
| C-M-03 | **MIGRATING 切片停发** | 切片内信贷须停 S0 | 生效时点、Runbook（非 Guard 防双发） | 产品 + 信贷 | §6.1 |
| C-M-04 | **`notification.owner` 变更** | 三态开关 | 切 `NEW` 审批与回滚 playbook | 产品 + 架构 | §6.1、[基础设施 A.4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#a4-迁移与触达) |

<a id="c-b-代码实现"></a>

### C-B 代码实现

| ID | 项 | 现状 | 下一步 | 责任方 |
|---|---|---|---|---|
| C-B-01 | **B1 真实 PubSub Consumer** | 🟡 `IngestionService` 可 publish；**无 GCP Consumer** | 实现消费 + `field-map`（[C-I-01](#c-i-入案字段与-pubsub-映射)）+ 映射表（[C-I-14](#c-i-入案字段与-pubsub-映射)） | 接入 |
| C-B-02 | **B2 日切** | 🟡 占位 | 实现 [C-D-02](#c-d-日切与-dpd)～C-D-06 | 接入 |
| C-B-03 | **jpush enrichment Repository** | 规格有 | 连 `ai_collection_db` 只读 + `ingestion_jpush_enriched_total` | 接入 |
| C-B-04 | **接入必填字段校验** | §3.2 原则 | 字段级清单闭合 [C-I-11](#c-i-入案字段与-pubsub-映射) 后编码 | 接入 |
| C-B-05 | **接入禁止直调 CaseService 写库** | ✅ 规格 §3.1 已明确 | 实现评审保持边界 | 接入 + service |

### C-X Phase 2（跟踪占位，不阻塞）

| ID | 项 | 说明 |
|---|---|---|
| C-X-01 | `assign_signal` 路由与日切去重 | §2.2、§4.1 |
| C-X-02 | 日切改读新库 `t_contact_plan` | §4.2 切量后 |
| C-X-03 | `t_user_repayment_plan` 迁入新库 | §4.2 切量后 |
| C-X-04 | 增量 `case_push` 刷新 payload | 当前 `ingested` key 跳过；若业务需要须新策略 |

---

> MOCASA Collection System Upgrade — Phase 1 Data Ingestion Spec — 2026-06-29（定稿）
