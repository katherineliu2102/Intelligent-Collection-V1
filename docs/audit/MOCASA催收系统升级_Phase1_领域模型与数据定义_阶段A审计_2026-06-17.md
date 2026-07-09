# 阶段 A 审计报告 · 领域模型与数据定义.md

> **审计日期**: 2026-06-17  
> **目标文档**: [`MOCASA催收系统升级_Phase1_领域模型与数据定义.md`](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md)  
> **审计类型**: 只读（阶段 A）；本报告不修改任何规格正文  
> **真相源优先级**: `collection-common` Java（model/dto/enums）> `db/schema.sql` > `docs/contracts/` > 目标文档现有正文

---

## A1. 代码 ↔ 文档差异表（§2–§7）

### §2 实体模型

| 位置 | 文档现状 | 代码/schema 现状 | 动作建议 |
|---|---|---|---|
| §2.1 ContactPlan 字段集 | 16 字段，无 `steps` | `ContactPlan.java` 多一个 `List<ContactPlanStep> steps`（内存态，不落单表，注释已说明） | 改文档：补一行 `steps`（标注"仅内存态，持久化落 t_contact_plan_step"），规模 S |
| §2.2 ContactPlanStep `idempotencyKey` 说明 | `plan_id:step_order:attempt` | 引擎实际生成 `planId:stepOrder:retryCount`（见 K3） | 改文档：`attempt`→`retryCount`，规模 S（属 K3，标 ⚠️） |
| §2.3 DecisionLog | 12 字段 | `DecisionLog.java` 完全一致 | ✅一致 |

### §3 决策上下文模型 —— **主要缺口**

| 位置 | 文档现状 | 代码现状 | 动作建议 |
|---|---|---|---|
| §3.1 CaseContext | 16 字段 | `CaseContext.java` 多 **5 个**：`strategyTone`、`complaintFrozen`、`collectionStatus`、`repaymentUrl`、`emailScriptSlot`（`CaseContext.java:34-46`） | 改文档：补 5 字段。`repaymentUrl` 是契约必填（contracts §SMS/PUSH/EMAIL），优先级高。规模 M |
| §3.2 BasicInfo | 9 字段（name…alternatePhones） | `UserProfile.BasicInfo` 多 **2 个**：`email`、`language`（`UserProfile.java:41-44`） | 改文档：补 `email`(EMAIL targetAddress)、`language`(metadata.language)；二者均为契约字段。规模 M |
| §3.2 DeviceInfo | 列 6 字段（含 jpushToken） | 一致（仅字段顺序不同，doc 把 jpushToken 列首） | ✅一致 |
| §3.2 RiskScore / WorkInfo / ContactInfo / RepaymentInfo / BehaviorProfile | — | 一致 | ✅一致 |
| §3.3 ContactHistory | 11 字段 | `ContactHistory.java` 一致 | ✅一致 |
| §3.4 ContextSnapshot | 5 字段 | `ContextSnapshot.java` 一致 | ✅一致（usage 重复见 A2） |

### §4 SPI 契约 DTO

| 位置 | 文档现状 | 代码现状 | 动作建议 |
|---|---|---|---|
| §4.1 ExecutionContext | 4 字段 | `ExecutionContext.java` 一致 | ✅一致 |
| §4.2 GuardVerdict | 3 字段 | `GuardVerdict.java` 一致（含 allow()/block()） | ✅一致 |
| §4.3 StepCommand.metadata | **4** key | `StepCommand.java` 定义 **13** META_* 常量 | 改文档：补 9 key（见 K1）。规模 M |
| §4.4 StepResult | 5 字段 | `StepResult.java` 一致；success/retryable 语义重复见 A2 | ✅字段一致 |
| §4.5 AdvancementDecision | 3 值 | `AdvancementDecision.java` 一致 | ✅一致 |
| §4.6 ExhaustionResult | 4 字段 + 约束表 | `ExhaustionResult.java` 一致 | ✅一致 |

### §5 触达记录

| 位置 | 文档现状 | 代码现状 | 动作建议 |
|---|---|---|---|
| §5.1 ContactRecord | 13 字段（无 id/createdAt） | `ContactRecord.java` 含 `id`、`createdAt` | 改文档：可补 id/createdAt（标准列），规模 S |

### §6 枚举 —— **含 1 处重大漂移**

| 位置 | 文档现状 | 代码现状 | 动作建议 |
|---|---|---|---|
| §6.1 ChannelType 值 | 8 值 | 一致 | ✅ |
| §6.1 PUSH 供应商列 | `FCM / APNs` | 契约已拍板走 JPush/通知中心（`UserProfile.java:96` fcmToken 已移除） | 改文档：供应商列校正为 JPush/通知中心。规模 S |
| §6.1 行为分组 | 无 | `ChannelType.isMessageChannel()/isAsyncChannel()`（`ChannelType.java:34-41`） | 改文档：补"渠道行为分组"列（见 K5）。规模 S |
| §6.2 ContactResult | **12** 值（无 SKIPPED） | `ContactResult.java` **13** 值，含 `SKIPPED(0)`（line 22） | 改文档：补 SKIPPED 行（见 K4）。规模 S |
| §6.2 priority/canUpgradeFrom | DELIVERED1<READ2<CLICKED3<REPLIED4 | 代码完全一致 | ✅一致 |
| §6.3 PlanStatus | 6 态 | 一致 | ✅ |
| §6.4 StepStatus | 5 值 | 一致 | ✅ |
| §6.5 DecisionType | 5 值 | 一致 | ✅ |
| §6.6 EventType | **8** 值 | `EventType.java` **10** 值，多 `CALLBACK_TIMEOUT`、`CASE_CEASED`（line 19-21） | 改文档：补 2 值（CALLBACK_TIMEOUT=内部哨兵；CASE_CEASED=D+91 停催）。规模 M |
| §6.7 CancelReason | **5** 值 | `CancelReason.java` **6** 值，多 `CEASED(true)`（line 12） | 改文档：补 CEASED 行（引擎管辖=是）。规模 S |
| §6.8 ChannelMode | 2 值 | 一致 | ✅ |
| **§6.9 Stage** | **6** 值（S0/S1/S2/S3/S4/**S4_PLUS**）；区间 S2=D4–10、S3=D11–15、S4=D16–30、S4_PLUS=D31+ | `Stage.java` **5** 值，**无 S4_PLUS**；区间 S1[1,3]、**S2[4,15]**、**S3[16,30]**、**S4[31,∞)**（已与编排对齐 2026-06-15，line 6-15） | 改文档：删 S4_PLUS、重写 DPD 区间以匹配代码。**最大缺口**，规模 L |
| §6.10 ExhaustionAction | 3 值 | 一致 | ✅ |
| §6.11 Direction/DataSource/SensitivityTag/PhoneValidity | — | 全部一致 | ✅ |

### §7 DDL（详见 K2）

| 位置 | 文档现状 | schema.sql 现状 | 动作建议 |
|---|---|---|---|
| §7.1–§7.2 全 5 表 列/类型/索引 | — | **完全一致**（逐列核对） | ✅结构一致 |
| 建表头 | 裸 `CREATE TABLE` | `CREATE TABLE IF NOT EXISTS` | 改文档：对齐 IF NOT EXISTS。规模 S |
| 表级 COMMENT | 无 | 5 表均有 `COMMENT='…'` | 改文档：补表级 COMMENT。规模 S |
| §7.1.1 stage 列注释 | `S0/S1/S2/S3/S4/S4_PLUS` | schema 同（**两处都含 S4_PLUS**，与 enum 不符） | 改文档注释去 S4_PLUS；schema.sql 禁改，仅标注待主架构修 |
| cancel_reason 列注释 | 缺 CEASED | schema 同缺 CEASED | 改文档注释补 CEASED |

---

## A2. 重复内容清单（本文件 vs contracts/ vs 引擎规格 §4）

| 本文件 §x | 重复对象 | 建议 |
|---|---|---|
| §3.1/§3.2/§3.4 最小必填集、金额 SSOT、targetAddress 取号叙述 | `contracts/README_ContextSnapshot契约对齐.md`（§最小必填、§金额 SSOT） | **LINK**：contracts 为"快照用法/必填/金额 SSOT"唯一源，本文档只保留字段结构 + 指针 |
| §4.1–§4.6 DTO 字段结构 | `核心引擎规格 §4.2 共享 DTO 定义` | **LINK**：本文档 §4 为 DTO 唯一 SSOT，引擎 §4.2 应改为链接本文档（动作落在引擎侧，本阶段只标注） |
| §4.4 StepResult `success`/`retryable` 运行时语义 + §4.3 metadata key 口径 | `ic-v1-channel-contract.mdc` Top3 §1/§2、`contracts/引擎渠道执行契约对齐_待编排确认.md` | **ONE_LINE**：本文档留字段定义；运行时语义一句话 + 链接 contracts（语义归执行契约） |
| §6.9 Stage 区间表 | `Stage.java` fromDpd 边界（代码） | **LINK/校正**：区间以代码为 SSOT，文档表标注"边界从模板读取，默认值见 Stage.java" |

---

## A3. 已知待核项结论（K1–K6）

### K1 — StepCommand.metadata 缺 9 个 key｜确认成立

文档 §4.3 仅 4 key（stage/language/callbackUrl/timeoutMinutes）。`StepCommand.java:23-35` 定义 13 个常量。

- **缺失 9 个**：`scriptSlot`、`sms_body`、`fallback_sms_body`、`title`、`body`、`pushData`、`dynamicTemplateData`、`case_id`、`fallback_sms`。
- **多余**：无。

→ 建议补全 9 行。

### K2 — §7 DDL vs schema.sql｜无结构差异，仅系统性写法差异

逐表核对 5 张表的列名/类型/索引：**全部一致**。系统性差异仅三类：

1. schema 用 `CREATE TABLE IF NOT EXISTS`，doc 用裸 `CREATE TABLE`
2. schema 每表有表级 `COMMENT='…'`，doc 无
3. 列 COMMENT 文案细节不同（doc 较详，如 `t_contact_timeline.result` doc 列出枚举值、schema 留空）

另两处**双向一致但都过时**：`stage` 列注释含 `S4_PLUS`、`cancel_reason` 注释缺 `CEASED`。

→ 建议 doc §7 对齐 schema 写法；schema.sql 禁改，停催相关注释交主架构。

### K3 — 幂等键口径｜代码统一为 `planId:stepOrder:retryCount`，3 处需标 ⚠️

代码真相（truth #1）：

- `StepExecutionOrchestrator.buildIdempotencyKey` → `plan.getId()+":"+step.getStepOrder()+":"+step.getRetryCount()`（`StepExecutionOrchestrator.java:249-250`）
- `DefaultStepResolver` → 同构（`DefaultStepResolver.java:104-108`）
- 测试 C7 / #31 断言同上

| 来源 | 表述 | 标记 |
|---|---|---|
| 文档 §2.2 step.idempotencyKey | `plan_id:step_order:attempt` | ⚠️ 待确认（`attempt` 应为 `retryCount`） |
| 文档 §4.3 / §7.1.2 注释 | "透传 step.idempotencyKey" / `plan_id:step_order:attempt` | ⚠️ 措辞/口径 |
| `ic-v1-channel-contract.mdc` | `plan + step + channel` | ⚠️ **过时**（含 channel，与代码不符；跨模块规则，需通知编排同事，禁自改） |
| contracts/README | `plan:stepOrder:retryCount` | ✅ 与代码一致 |

→ **建议（仅建议）**：以代码为准统一为 `planId:stepOrder:retryCount`。文档归本阶段 B 修；`.mdc` 规则与 contracts 跨模块，**不自行拍板统一**，标 ⚠️ 待对齐。

### K4 — ContactResult 漂移｜确认成立

`ContactResult.java` 含 `SKIPPED(0)`（line 21-22，注释"StepResolver 主动跳过/非失败/引擎照常推进"），文档 §6.2 表格**未列**。`priority` 字段与 `canUpgradeFrom()`（line 36-41，严格 `>`）与文档实现约束**完全一致**。

→ 建议仅补 SKIPPED 行。

### K5 — ChannelType 行为方法｜确认成立

`ChannelType.java:34-41` 有 `isMessageChannel()`(SMS/PUSH/EMAIL/VIBER/WHATSAPP) 与 `isAsyncChannel()`(AI_CALL/TTS/HUMAN_CALL)，文档 §6.1 无对应表达。

→ 建议补"渠道行为分组"列或附注（消息类/异步类）。

### K6 — 文内断链｜本文档内无 `操作说明.md` 断链

- 全文 grep：目标文档**未引用** `操作说明`，故本文档内**无**该断链。`操作说明.md`（应为 `操作说明_Nacos本地启动.md`）断链实际位于 `channel/开发执行指南`、`channel/功能测试指南`（只读）与 `基础设施交互规范.md:258`（他文档）——**均在本任务禁改范围**，仅记录不处理。
- 文首「关联文档」（line 7）仅列 架构设计文档 + 核心引擎规格，**确缺**：基础设施交互规范、`contracts/`(ContextSnapshot 契约对齐)、`db/schema.sql`。

→ 建议补关联文档指针。

---

## A4. 变更计划（供 B2 执行，按 §1→§8）

| 章节 | 动作 | 规模 | 备注 |
|---|---|---|---|
| §1 数据资产总览 | 校对；§1.2 stage 提及与 §6.9 联动 | S | 表级矩阵本身无误，仅随 §6.9 联动检查 |
| §2 实体 | 补 ContactPlan `steps`(内存态)；§2.2 `attempt`→`retryCount` | S | K3 项标 ⚠️ |
| §3 上下文 | 补 CaseContext 5 字段、BasicInfo `email`/`language`；§3.4 最小必填/金额 SSOT 改 LINK contracts | **M–L** | 契约字段优先（repaymentUrl/email/language） |
| §4 DTO | §4.3 补 9 个 metadata key；§4.4 success/retryable 语义 ONE_LINE+LINK；标注 §4.2 引擎侧去重 | M | DTO 结构本身已一致 |
| §5 触达记录 | 补 id/createdAt（可选） | S | 标准列 |
| §6 枚举 | §6.1 供应商列+行为分组；§6.2 补 SKIPPED；§6.6 补 CALLBACK_TIMEOUT/CASE_CEASED；§6.7 补 CEASED；**§6.9 Stage 删 S4_PLUS+重写 DPD 区间** | **L** | §6.9 为最大缺口，影响 §1/§7 注释联动 |
| §7 DDL | 对齐 schema：补 `IF NOT EXISTS` + 表级 COMMENT；stage 注释去 S4_PLUS、cancel_reason 注释补 CEASED | M | schema.sql 禁改；仅改文档侧 |
| §8 渠道编排层 | **不动** | — | 禁写 CREATE TABLE；维持"待补充" |

> 跨文档/跨模块项（`ic-v1-channel-contract.mdc` 幂等键口径、引擎 §4.2 DTO 去重、schema.sql 的 S4_PLUS/CEASED 注释、channel 与基础设施的 `操作说明.md` 断链）均**标注待对齐、本阶段不改**，需通知编排同事/主架构后续处理。

---

## 附录：审计范围与核对文件

### 按章节挂钩的代码文件

| 文档章节 | 核对文件 |
|---------|----------|
| §2 实体 | `ContactPlan.java` / `ContactPlanStep.java` / `DecisionLog.java` |
| §3 上下文 | `ContextSnapshot.java` / `CaseContext.java` / `UserProfile.java` / `ContactHistory.java` |
| §4 DTO | `ExecutionContext.java` / `GuardVerdict.java` / `StepCommand.java` / `StepResult.java` / `ExhaustionResult.java` |
| §6 枚举 | Stage, ChannelType, ChannelMode, ContactResult, PlanStatus, StepStatus, EventType, CancelReason, DecisionType, AdvancementDecision, ExhaustionAction, Direction, DataSource, PhoneValidity, SensitivityTag |
| §7 DDL | `db/schema.sql` |

### 只读参考（未改）

- `docs/contracts/README_ContextSnapshot契约对齐.md`
- `docs/contracts/ContextSnapshot.sample.json`
- `docs/README.md`
