# MOCASA 催收系统升级 — Phase 1 数据接入规格

> **版本**: Phase 1（定稿）  
> **日期**: 2026-06-29  
> **范围**: 仅覆盖菲律宾市场  
> **模块**: `collection-ingestion`  
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
- [3. 数据校验、幂等与接入边界](#3-数据校验幂等与接入边界)
  - [3.0 入案快照路径（决策 B）](#30-入案快照路径决策-b)
  - [3.1 上游字段校验与防御](#31-上游字段校验与防御)
  - [3.2 PubSub 字段 → 快照字段映射（审计参考）](#32-pubsub-字段--快照字段映射审计参考)
  - [3.3 接入幂等键](#33-接入幂等键)
  - [3.4 模块职责边界](#34-模块职责边界)
  - [3.5 jpushToken Phase 1：数仓同步 + 接入 enrichment](#35-jpushtoken-phase-1-数仓同步--接入-enrichment)
- [4. 阶段变更与 DPD 日切](#4-阶段变更与-dpd-日切)
- [5. 领域事件发布](#5-领域事件发布)
- [6. 迁移与双写](#6-迁移与双写)
- [附录 A：配置与上游对齐清单](#附录-a配置与上游对齐清单)
- [附录 B：接入侧可观测与对账](#附录-b接入侧可观测与对账)

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
- 字段校验 / 清洗 / 乱序与脏数据处置（§3.1）
- 组装 EventPayload 并 publish 领域事件（§2 / §5）
- DPD 日切 → `STAGE_CHANGED` / `CASE_CEASED`（§4）
- 到期前通知迁移灰度（§6）
- 接入幂等键、PubSub ACK / 毒丸处置（§3.3 / §2.3）

### 1.3 前置决策

按接入主链路排列：**PubSub 入案 → DPD 日切 → 迁移**。正文各节展开细节，此处只列须遵守的边界。

#### PubSub 入案

| 项 | 结论 |
|---|---|
| 读库 | **不读**旧库 `t_collection`。`jpushToken` 缺失时只读新库 `t_user_device_token`（§3.5）。 |
| payload | 快照字段随 `CASE_INGESTED` payload 带出（**决策 B** → [§3.0](#30-入案快照路径决策-b)）；冻结写入由引擎完成（[§4.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#42-计划创建)）。 |
| `CASE_INGESTED` | **本催收周期**内首次 publish；同周期增量 ack 跳过；全额结清后 key 清除（§2.2 / §3.3）。 |
| `REPAYMENT_RECEIVED` | 校验通过即 publish；**不写**库；全额结清时 DEL `ingestion:ingested:{loan_id}`。 |

#### DPD 日切

| 项 | 结论 |
|---|---|
| 读库 | 只读 `t_collection`（在催名单）+ `t_user_repayment_plan`（bill DPD）；Phase 1 显式依赖旧库，演进 §4.2 注。 |
| 产出事件 | **仅**日切产出 `STAGE_CHANGED` / `CASE_CEASED`；`assign_signal` 预留、不路由（§2.2）。 |
| 写库 | **不回写**任何库（`t_collection` 由旧系统 `case_load` 加工，非信贷直写）。 |

#### 迁移

| 项 | 结论 |
|---|---|
| 通知接管 | `LEGACY → PARALLEL → NEW`（§6.1）；接入全量收 PubSub，灰度在触达 / owner 层。 |

---

## 2. PubSub 消费与消息路由

### 2.1 订阅与并发消费

#### GCP 凭证与订阅

| 配置项 | Phase 1 |
|---|---|
| `GCP_PUBSUB_PROJECT` | 环境变量 / Nacos（不入仓） |
| `GCP_PUBSUB_SUBSCRIPTION` | **`collection-cases-ai-v1-sub`**（topic `collection-cases`；**禁止**复用 `collection-cases-sub`） |
| `GOOGLE_APPLICATION_CREDENTIALS` | 服务账号 JSON 路径（不入仓） |

#### 消费参数

| 配置项 | Phase 1 |
|---|---|
| `collection.ingestion.ack-deadline-seconds` | **60** |
| `collection.ingestion.max-concurrency` | **4** |
| `collection.ingestion.enabled` | 本地 / CI **`false`**；联调 / 生产 **`true`** |

#### Topic 拓扑

| 项 | 约定 |
|---|---|
| Topic | `collection-cases`（`case_push` 与 `repayment_push_and_load` 混投） |
| 订阅 | 扇出独立订阅，与旧系统各 ack 互不影响 |
| 未知 `dataType` | ack 跳过 |
| 历史 backlog | 订阅创建前消息不补收；存量 §6.2 replay |

联调入场、渠道沙箱、`loan_id` 白名单 → [测试 §L4b.1](./testing/MOCASA催收系统升级_Phase1_测试文档.md#l41-入场-checklist跑前必读)

### 2.2 消息路由与契约

#### 路由

| `dataType` | 处理 | 领域事件 |
|---|---|---|
| `case_push` | 校验 → 组装 payload → 条件 publish | `CASE_INGESTED` |
| `repayment_push_and_load` | 校验 → publish | `REPAYMENT_RECEIVED` |
| `assign_signal` | ack 跳过 | — |
| 其他 | ack 跳过 | — |

| PubSub 不产出 | §4 日切 → `STAGE_CHANGED` / `CASE_CEASED` |

| `assign_signal` | Phase 1 **不路由**（现网无此 `dataType`）；**非**旧库 `t_collection_assign*` 人工分案；阶段变更仅 §4 日切。Phase 2 接入须补 JSON 契约及与日切去重 → [§4.1](#41-阶段变更来源) |

#### 2.2.1 `case_push`

| 项 | 约定 |
|---|---|
| 字段 | 上游 PubSub JSON；payload 清单 → [领域模型 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)；别名 → `field-map`（[A.2 #1](#a2-信贷主系统联调确认项上线前签字)） |
| 校验 | §3.1 |
| 写库 | 无（→ [§3.0](#30-入案快照路径决策-b)） |
| `jpushToken` | §3.5 |

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
| 全额结清 | DEL `ingestion:ingested:{loan_id}`（+ 可选 `dedup:ceased`）→ §3.3、[A.2 #3](#a2-信贷主系统联调确认项上线前签字) |
| 部分还款 | 不清 ingested key |

幂等 → §3.3

### 2.3 消费可靠性

上游为 At-least-once 投递，接入层须保证幂等与不丢不毒：

- **ACK 语义**：写库（+ 可选 publish）在同一处理单元内完成；**全部成功**后 ack；任一失败 nack 触发重投。
- **处理顺序**：单 `loan_id` 不要求全局有序；乱序收敛见 §3.1。
- **消息级去重**：见 [§3.3](#33-接入幂等键)（接入层，与引擎 `processed:` / `lock:plan:` 分层）。
- **毒丸消息**：连续 N 次（建议 N=5）失败 → 写入接入死信表 / DLQ 并告警；重放机制复用 [基础设施 §2.1 DLQ](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#21-dlq-重放redrive)；**不得静默丢弃**。

---

## 3. 数据校验、幂等与接入边界

> 接入侧运营**对账**指标见 [附录 B](#附录-b接入侧可观测与对账)。

### 3.0 入案快照路径（决策 B）

> **决策 B（2026-06-29）** = 入案快照**主链路**走 `case_push` → `CASE_INGESTED` payload → 引擎组装 `ContextSnapshot`；**运行时不读**旧库 `t_collection`。  
> （另：**方案 A** = 全额结清 DEL `ingestion:ingested` key，见 §2.2.2 / §3.3——与 B 无关。）

| 步骤 | 模块 | 动作 |
|---|---|---|
| 1 | 接入 | 消费 `case_push` → 校验 → 映射快照字段进 payload（字段 SSOT → [领域模型 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)） |
| 2 | 接入 | `jpushToken` 缺失时只读新库 enrichment（→ [§3.5](#35-jpushtoken-phase-1-数仓同步--接入-enrichment)） |
| 3 | 接入 | publish `CASE_INGESTED`（**不写** plan / 不序列化 snapshot） |
| 4 | 引擎 | `buildSnapshotFromEvent(payload)` → 写 `t_contact_plan.context_snapshot` |

| 允许读库（非主链路） | 用途 |
|---|---|
| 新库 `t_user_device_token` | jpush enrichment（§3.5） |
| 旧库 `t_collection` via `RealCaseService` | payload 缺失兜底 / L4b 对账 |
| 旧库 via `PreFlightChecker.isRepaid` | 触达前实时还款守卫 → [核心引擎 §5.1](./MOCASA催收系统升级_Phase1_核心引擎规格.md#51-execute_step-七步管线) |
| 旧库 via §4 日切 | 在催名单扫描（**不同性质**，见 [§4.2 注](#42-max-dpd-计算口径与日切流程)） |

### 3.1 上游字段校验与防御

接入是脏数据进入系统的第一道闸口（金额 / DPD / 状态须强校验）。**原则**：不可修复 → ack+poison；可重试 → nack；引擎层终态单调兜底乱序 → [核心引擎 §3.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#32-并发与一致性模型)。

| 类别 | 处置 |
|---|---|
| **必填 / 格式** | 缺必填或格式不可修复 → **ack + poison/DLQ** + 告警（**不 nack**，避免毒丸重投） |
| **null 防御** | 非关键字段缺失 → payload 记缺省；下游 null 防御 → [HANDOFF C2](../HANDOFF.md) |
| **乱序** | `repayment_push_and_load` 先于 `case_push` → 仍 publish（引擎无活跃计划 noop）；过期 `case_push`（`publish_time` < `ingestion:last_seen:{loan_id}`）→ ack 跳过 |
| **迟到** | `publish_time` 超 24h 的 `case_push` → ack + 审计；**不**触发首次 `CASE_INGESTED`（存量 §6.2 replay） |
| **瞬态失败** | 下游超时 / DB 不可达等 → nack 重投；超 N 次 → poison（§2.3） |

### 3.2 PubSub 字段 → 快照字段映射（审计参考）

> 主链路见 [§3.0](#30-入案快照路径决策-b)。下表仅供 **L4b 对账**（payload vs 旧库 `t_collection`）及 `RealCaseService` **兜底**字段参考，**不是** publish 前置条件。

| PubSub / 语义字段 | 旧库列（审计参考） | 快照字段（payload → 引擎） |
|---|---|---|
| `loan_id` | `loan_id` | `caseContext.caseId`（数字串；**非** `t_collection.id` hex） |
| `user_id` | `user_id` | `caseContext.userId` |
| `max_dpd` / `overdue_days` | `overdue_days` | `dpd` / `Stage.fromDpd` |
| 金额、联系方式等 | `total_not_paid`, `overdue`, `phone`, `email`… | 见 [测试 §L4b.2(2)](./testing/MOCASA催收系统升级_Phase1_测试文档.md)；`totalOutstanding` 无直传时由 `principal`/`interest`/`overdue` 与已还分项在接入侧推算 |
| `jpushToken` | （非 `t_collection` 列）旧库 `t_user_extend.ji_guang_token` → 新库 `t_user_device_token` | payload `jpushToken` → `userProfile.device.jpushToken`（§3.5） |

> `t_user_repayment_plan` 只读；**Loan Max DPD** 目标口径见 §4.2（[渠道编排 §5.2 / §6.3.1](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#52-维度口径修正现网错误)）。

### 3.3 接入幂等键

| 场景 | Redis / 内存 key | TTL | 语义 |
|---|---|---|---|
| `case_push` 重复投递 | `ingestion:dedup:case_push:{message_id}` | 7d | 命中则 ack 跳过 |
| `case_push` 乱序水印 | `ingestion:last_seen:{loan_id}` | 90d | 最新 `publish_time`；更小者 ack 跳过（§3.1） |
| `case_push` 周期内入催 | `ingestion:ingested:{loan_id}` | 90d | 已 publish `CASE_INGESTED` 则不再 publish；全额结清 DEL（§2.2.2 / §3.3） |
| `repayment_push_and_load` | `ingestion:dedup:repayment:{user_id}:{message_id}` | 7d | 防重复取消 |
| `repayment_push_and_load` 全额结清 | （清除）`ingestion:ingested:{loan_id}`、`dedup:ceased:{loan_id}`（可选） | — | 允许下一周期 `CASE_INGESTED` |
| 日切阶段 | `ingestion:dedup:stage:{loan_id}:{target_stage}:{yyyyMMdd}` | 2d | 同日同阶段不重复 `STAGE_CHANGED` |
| 日切停催 | `ingestion:dedup:ceased:{loan_id}` | 90d | 不重复 `CASE_CEASED`；结清时可 DEL |

- 生产切 Redis 后 key 前缀挂 [基础设施 §3](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#3-运行时状态redis-kv) 同一实例；与引擎 `lock:plan:` **禁止共用 key**。**且须与旧催收系统 Redis（`db_collection` 侧实例 / 命名空间）物理或前缀隔离**——新系统统一 `ingestion:*` / `ai:*` 前缀，杜绝与旧系统 key 互撞。
- 引擎侧 `CASE_INGESTED` 计划创建幂等见 [核心引擎 §4.2](./MOCASA催收系统升级_Phase1_核心引擎规格.md#42-计划创建)（`idempotency_key` / 单活跃计划约束）。

<a id="34-与-caseservice--profileservice-的调用边界"></a>

### 3.4 模块职责边界

职责切分见 [§3.0](#30-入案快照路径决策-b)；`jpushToken` 机制见 [§3.5](#35-jpushtoken-phase-1-数仓同步--接入-enrichment)。

| 模块 | 入案主链路职责 |
|---|---|
| **接入** | 校验、`case_push`→payload 映射、jpush enrichment、publish 事件 |
| **引擎** | 消费 `CASE_INGESTED`、组装 / 冻结 `ContextSnapshot`、写 plan |
| **CaseService** | **非主链路**：payload 缺失兜底、L4b 对账、`PreFlightChecker.isRepaid` |
| **接入不做** | 不写 `t_contact_plan`、不调 `PlanFactory`、不读旧库组快照 |

- `repaymentUrl`：引擎组装 snapshot 时按 `collection.repayment-url-template` 生成。
- 快照字段溯源断言（L4b-5）→ [contracts](./contracts/README_ContextSnapshot契约对齐.md)。

### 3.5 jpushToken Phase 1：数仓同步 + 接入 enrichment

> **决策（2026-06-29）**：Phase 1 不运行时读旧库 `t_user_extend`；由**数仓每日同步**至新库，接入层只读新库写进 payload。终态仍优先上游 `case_push` 自带 token（上游就绪后关 enrichment）。

#### 3.5.1 数据流

```
旧库 db_collection.t_user_extend (ji_guang_token)
    → 数仓日批 ETL（单向、只读源、不回写）
    → 新库 ai_collection_db.t_user_device_token
    → ingestion 消费 case_push：消息无 jpushToken 时 SELECT BY user_id
    → CASE_INGESTED payload.jpushToken
    → 引擎 buildSnapshotFromEvent（不查库）
```

| 项 | 规格 |
|---|---|
| **源表** | 旧库 `t_user_extend`，列 `ji_guang_token`（表名 / 列名上线前 DBA 确认，见 A.2 #10） |
| **目标表** | 新库 `t_user_device_token`（DDL：[领域模型 §7.2.3](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#723-t_user_device_token--push-token-镜像phase-1)、[`db/schema.sql`](../db/schema.sql)） |
| **同步责任** | **数仓 / 数据平台**（非 `collection-ingestion` 代码内实现）；建议与日切同窗口或略早（如每日 0:00 PHT 全量 / 增量 upsert） |
| **同步粒度** | 按 `user_id` 一行一 token；多设备取**最新登录**一条（与 [字段透传 §2.1](./channel/MOCASA催收系统升级_Phase1_ContextSnapshot字段透传说明.md#21-jpushtoken-上游来源push-硬依赖) 一致） |
| **新鲜度** | 日同步 → token 最长延迟 ≈ 1 天；新注册用户当日入催可能无 token → fallback SMS，可接受 |
| **接入 enrichment** | `case_push` 消息体**已有** `jpushToken` / `ji_guang_token` 时**以消息为准**，不查新库；否则 `SELECT jpush_token FROM t_user_device_token WHERE user_id = ?`；查不到或空 → 不写 payload key |
| **失败语义** | 新库查询失败：记 warn + metric，**仍 publish**（缺 token，不 nack 整案） |
| **终态收口** | 上游 `case_push` 稳定带 JPush RID 后：`collection.ingestion.enrich-jpush-token=false`，可下线同步任务 |

#### 3.5.2 接入实现要点（B1 真实 Consumer）

```java
// 伪代码：assembleSnapshotFields(casePushJson)
Map<String, Object> fields = mapCasePushToSnapshot(casePushJson);
if (!fields.containsKey(CollectionEvent.JPUSH_TOKEN)
        && enrichJpushTokenEnabled) {
    String token = deviceTokenRepository.findByUserId(userId);
    if (token != null && !token.isBlank()) {
        fields.put(CollectionEvent.JPUSH_TOKEN, token.trim());
    }
}
ingestionService.ingestCase(caseId, userId, stage, fields);
```

- Repository 只连 **`ai_collection_db`**，禁止为 token 配置旧库数据源。
- 指标：`ingestion_jpush_enriched_total{source=message|new_db|missing}`。

---

## 4. 阶段变更与 DPD 日切

每日 0:05 PHT（`DpdStageRollHandler`，生产经 XXL-Job，见 [基础设施 §4](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#4-定时调度xxl-job)）重算 Max DPD 的日切逻辑。

### 4.1 阶段变更来源

Phase 1 阶段变更**唯一来源为 DPD 日切**。`assign_signal`（§2.2）Phase 1 不启用；若 Phase 2 接入，须定义 JSON 契约及其与日切的优先级与去重（避免同案件同日双重 `STAGE_CHANGED`）。

### 4.2 Max DPD 计算口径与日切流程

**SSOT**（与 [渠道编排 §4.1 / §5.2 / §6.3.1](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#531-难催子条件计算口径) 一致）：

| 项 | 规格 |
|---|---|
| **Loan Max DPD** | 整笔 loan 下各 bill 逾期天数 **`max(bill_dpd)`**；`bill_dpd = DATE_DIFF(as_of_date, due_date, DAY)`（bill 已到期且未结清） |
| **Stage 映射** | Max DPD → `Stage.fromDpd`（S0[-3,0]…S4[31,90]；≥91 停催，**不是** Stage） |
| **Stage 回退** | Max DPD **下降**时须 publish `STAGE_CHANGED`（目标 stage 降低），引擎取消旧计划并重建（渠道 §5.2） |

**Phase 1 实现分层**：

| 层 | 口径 |
|---|---|
| **目标** | 日切读 `t_user_repayment_plan`（**只读**）按 §6.3.1 重算 `max_dpd` |
| **过渡（L4b）** | 日间以信贷写入的 `t_collection.overdue_days` 为准（只读）；日切 0:05 **hybrid 重算**：优先 bill 级公式，缺 bill 数据时 fallback `DATE_DIFF(TODAY_PHT, repayment_date)` |

| 项 | 规格 |
|---|---|
| 触发 | 每日 **0:05 PHT**（`Asia/Manila`），`DpdStageRollHandler.dailyRoll()` |
| 扫描范围 | **旧库只读**：未还款且未停催案件（`t_collection`：`full_repay_time IS NULL` 且 `total_not_paid > 0`；每 `loan_id` 取最新行）；bill 级 DPD 读 `t_user_repayment_plan`。**Phase 1 显式依赖旧库**，见下注。 |
| 比对 | `oldStage = Stage.fromDpd(old_max_dpd)`，`newStage = Stage.fromDpd(new_max_dpd)` |

> **日切读旧库 vs. 入案不读旧库（两件性质不同的事）**
>
> 入案主链路见 [§3.0](#30-入案快照路径决策-b)（不读 `t_collection` 组快照）。
>
> 日切的旧库依赖是**另一种性质**：日切需要"当前所有在催案件列表"，这个视图（未还款、未停催的全量 `loan_id`）在 Phase 1 只存在于旧系统维护的 `t_collection`；`t_user_repayment_plan` 的 bill 数据同理。**Phase 1 不需要把这两张表同步到 `ai_collection_db`**，因为旧系统在并行运行，数据实时有效，无时间紧迫性。
>
> **演进路径（Phase 2）**：
> - `t_collection` 在催名单：§6.2 存量 replay 完成后，所有活跃案件均已经 `CASE_INGESTED` 进入新系统，`t_contact_plan` 即可作为在催案件源，日切切换为读新库。
> - `t_user_repayment_plan` bill 数据：旧系统下线前须单独规划（ETL 同步至 `ai_collection_db` 或信贷侧开放 API）；属 Phase 2 事项，时间要求为旧系统停写前完成迁移。

**日切伪代码**：

```
for each active loan_id (read-only latest row + bills):
  oldMaxDpd = current max_dpd
  newMaxDpd = recompute per channel §6.3.1
  if newMaxDpd == oldMaxDpd: continue
  if newMaxDpd >= 91:
      publish CASE_CEASED(caseId, maxDpd)   // 不写旧库
  else if Stage.fromDpd(newMaxDpd) != Stage.fromDpd(oldMaxDpd):
      publish STAGE_CHANGED(caseId, stage=newStage)
```

### 4.3 STAGE_CHANGED（DPD 1–90 阶段变化，含回退）

DPD 在 1–90 且 `Stage.fromDpd` 映射变化 → 发 `STAGE_CHANGED`（payload `caseId` + `stage`＝**目标阶段**，字段以 [领域 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段) 为准）。引擎消费后取消旧阶段计划并重建（[核心引擎 §4.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#44-中断处理)）。

### 4.4 CASE_CEASED（DPD ≥ 91 完全停催）

DPD ≥ 91 → **仅 publish `CASE_CEASED`**（payload `caseId` + `maxDpd`；**不写旧库** `colleciton_status`）。引擎 cancel 活跃计划、不再 create；`collectionStatus=CEASED` 由快照 / `shouldRejectPlan` 表达（[渠道编排 §4.2](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#42-完全停催d91) / L4b-4）。

### 4.5 日切幂等与重复执行

- 日切须**重跑安全**：同日多次执行不重复发 `STAGE_CHANGED` / `CASE_CEASED`。
- 幂等：§3.3 日切 dedup key；阶段事件以「目标 stage 已生效 / 已 publish CASE_CEASED」为 noop 条件。

### 4.6 strategyTone（Phase 1 占位）

[渠道编排 §6.3.1](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#631-难催子条件计算口径) 规定 **FIRM 难催标记** 由接入层在入案 / 日切 / 还款重算时计算。Phase 1 `RealCaseService` **固定 `STANDARD`**；完整 FIRM 规则在 service/ingestion 协作落地（不阻塞 L4b）。日切 / 入案逻辑须预留 `strategyTone` 输出扩展点。

---

## 5. 领域事件发布

接入经 `CollectionEventBus.publish` 发布领域事件；payload 字段以 [领域模型 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段) 为准，传输 / 可靠性由 [基础设施 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream) 保证。接入层只"组装 payload + publish"，不感知 Stream 细节。

**接入层产出的事件（触发点）**：

| 事件 | 触发点 | 来源章节 |
|---|---|---|
| `CASE_INGESTED` | `case_push` 首次入催且校验通过后 | §2.2 / §3 |
| `REPAYMENT_RECEIVED` | `repayment_push_and_load` 校验通过（不写库） | §2.2 |
| `STAGE_CHANGED` | 日切 Stage 变化（含回退） | §4.3 |
| `CASE_CEASED` | 日切 DPD≥91 | §4.4 |

**发布契约（接入侧约束）**：

- **顺序**：校验通过后 publish；`repayment_push_and_load` 无写库前置。
- **失败语义**：瞬态失败（publish / 下游）nack 重投 + §3.3 幂等；校验不可修复 → ack + poison（口径见 [§3.1](#31-上游字段校验与防御) / §2.3）。
- payload key 新增须先在 `CollectionEvent` 增补常量并同步 [领域 §9.2](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)（跨模块契约，改前对齐）。

---

## 6. 迁移与双写

到期前通知职责由信贷主系统迁移至新系统的过渡方案。

### 6.1 D-3 ~ D0 通知接管

对齐 [架构 ADR](./MOCASA催收系统升级_Phase1_架构设计文档.md#附录-a架构决策记录-adr) / [PRD F9](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)：**新系统接管发送**，信贷主系统仅 `case_push` 数据。

| 机制 | Phase 1 规格 |
|---|---|
| **`LEGACY`** | 信贷主系统仍发 D-3~D0 Push/SMS；新系统 **不** 发 S0 到期前触达（仅收 `case_push`） |
| **`PARALLEL`（灰度）** | **双发期**：信贷与新系统可能同时发到期前提醒 → 新系统 `ExecutionGuard` 查 `t_contact_timeline` 当日同用户同渠道是否已有触达，有则 SKIPPED（防重复打扰） |
| **`NEW`** | 信贷 **停发** D-3~D0 通知，仅推 `case_push`；新系统机器轨 **接管 S0**（[渠道编排 §0 迁移](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md)） |
| **切换粒度** | Nacos `collection.notification.owner` + 按 `app_name` / 批次日期灰度；回滚 = 改回 `LEGACY` |
| **历史触达** | 信贷历史 Push/SMS **ETL** → `t_contact_timeline`，`source=ETL_SYNC` |

> **灰度边界（关键，2026-06-29 补）**：
> - **"20% 走新系统"作用在触达 / owner 层，不在接入层。** 靠 PubSub 扇出，新旧系统都接入 **100%** 消息、各自落各自库（落库互不影响）；按 `app_name` / `hash(loan_id)` 等切片决定**哪些案件由谁触达**。灰度键须与上游 / 旧系统对齐成同一口径（A.2 #4）。
> - `ExecutionGuard` 查 `t_contact_timeline` 去重，**只能防新系统自身重复发，挡不住旧系统继续发**。因此对灰度切中的案件，**必须由信贷 / 旧系统侧同步停发** D-3~D0 通知（即 `NEW` 态"信贷停发"须按灰度切片提前生效），否则 `PARALLEL` 期该切片用户会被两边重复打扰。
> - 若确需"新系统只接入 20%"（限制落库 blast radius），需在接入处理器做应用级过滤（命中处理、其余 ack 跳过）；**默认推荐"全量接入 + 触达层灰度"**。

### 6.2 历史数据迁移范围

| 范围 | 策略 |
|---|---|
| **在催案件入新系统** | 一次性 replay / 批量 `case_push`：DPD ∈ [-3, 90]、`colleciton_status != CEASED` 的活跃 `loan_id`（具体名单由信贷导出 + 产品确认） |
| **已 CEASED / 已结清** | 不建计划；仅 ETL 时间线（可选） |
| **L4b 联调** | **白名单** `loan_id` 子集（测试库内部账户）；名单维护在运维侧，**不入仓**（见 [测试 §L4b.1](./testing/MOCASA催收系统升级_Phase1_测试文档.md)） |
| **协调项** | 切换窗口、replay 顺序、与信贷对账口径 → [PRD §10](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md) 跨团队跟踪 |

> Phase 1 代码路径不阻塞于生产迁移：L4b 仅验证 B1/B2 + 白名单；§6 为上线切换手册。

---

## 附录 A：配置与上游对齐清单

### A.1 环境变量 / Nacos（凭证不入仓）

| 键 | 说明 |
|---|---|
| `GCP_PUBSUB_PROJECT` | GCP 项目 |
| `GCP_PUBSUB_SUBSCRIPTION` | 独立订阅名（**`collection-cases-ai-v1-sub`**；A.2 #7 运维建订阅签字） |
| `GOOGLE_APPLICATION_CREDENTIALS` | 服务账号 JSON 路径 |
| `collection.ingestion.enabled` | 本地 `false`；联调 / 生产 `true` |
| `collection.ingestion.ack-deadline-seconds` | 默认 60 |
| `collection.ingestion.max-concurrency` | 默认 4 |
| `collection.ingestion.loan-id-whitelist` | 可选；联调 blast radius（[测试 §L4b.1](./testing/MOCASA催收系统升级_Phase1_测试文档.md#l41-入场-checklist跑前必读)） |
| `collection.notification.owner` | `LEGACY` / `NEW` / `PARALLEL`（§6.1） |
| `collection.ingestion.case-push.field-map` | 上游 JSON→语义字段别名（JSON） |
| `collection.ingestion.enrich-jpush-token` | 默认 `true`；消息无 token 时查新库 `t_user_device_token` enrichment（§3.5）；上游带 token 后可关 |

### A.2 信贷主系统联调确认项（上线前签字）

| # | 确认项 | 本文落点 |
|---|---|---|
| 1 | PubSub JSON 字段名是否与 §2.2 一致；若否，提供 `field-map` | §2.2 |
| 2 | `case_push` 推送频率与 `message_id` 稳定性 | §2.2 / §3.3 |
| 3 | `repayment_push_and_load` 字段与全额结清判定（DEL ingested key） | §2.2 / §3.3 |
| 4 | 切换 `NEW` 后信贷侧停发 D-3~D0 通知的生效时点；**灰度切片能否按 `app_name` / `hash(loan_id)` 同步停发** | §6.1 |
| 5 | 在催案件 replay 名单与批次窗口 | §6.2 |
| 6 | **topic 拓扑**：canonical topic 名与所在 project（`datacenter-ind/collection-cases`？）；新订阅是否跨 project；新系统服务账号 `pubsub.subscriber` 及建订阅权限 | §2.1 |
| 7 | **新建独立订阅** `collection-cases-ai-v1-sub`（不复用 `collection-cases-sub`）；真实 `dataType` 码值稳定性（`case_push` / `repayment_push_and_load`） | §2.1 / §2.2 |
| 8 | `case_push` payload 快照字段完整性；JPush token 终态 | §3.0 / §3.5 |
| 9 | 旧系统下线后该 topic / 这些消息是否继续发布（终态数据源生命周期） | §2.1 |
| 10 | **数仓同步**：旧库 `t_user_extend` → 新库 `t_user_device_token` 的表名/列名确认、日批 schedule、全量/增量策略、延迟 SLA | §3.5 |

## 附录 B：接入侧可观测与对账

接入专属埋点（命名约束见 [基础设施 §6.2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#62-可观测性接入约束)）：

| 指标 | 说明 |
|---|---|
| `ingestion_pubsub_lag_seconds` | 拉取 → 处理完成 |
| `ingestion_pubsub_processed_total{result=ack\|nack\|dedup\|poison}` | 消费结果 |
| `ingestion_case_push_ingested_total` | 首次 `CASE_INGESTED` 次数 |
| `ingestion_stage_roll_events_total{type=STAGE_CHANGED\|CASE_CEASED}` | 日切产出 |
| `ingestion_validation_rejected_total{reason}` | 校验 / 脏数据拒绝 |
| **对账** | 日级：`case_push` 条数 ≈ 信贷推送日志；`CASE_INGESTED` ≤ 新入催 `loan_id` 数；日切 `STAGE_CHANGED` 与 DPD 跨边界案件数一致 |

---

> MOCASA Collection System Upgrade — Phase 1 Data Ingestion Spec — 2026-06-29（定稿）
