# MOCASA 催收系统升级 Phase1 — 新系统消息契约设计

**日期**：2026-08-10
**状态**：与数据 / 接入团队对齐稿
**目的**：定义新催收系统在独立 topic 上消费的 2 类消息契约，作为老系统分流测试的数据接口基线。

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [设计原则](#2-设计原则)
3. [关键约束：数据口径差异](#3-关键约束数据口径差异bill-维度-vs-loan-维度-max-dpd)
4. [消息契约总览](#4-消息契约总览)
5. [caseEvent 字段定义](#5-caseevent-字段定义)
6. [repaymentEvent 字段定义](#6-repaymentevent-字段定义)
7. [计算逻辑](#7-计算逻辑)
8. [3 期产品还款结构](#8-3-期产品还款结构)
9. [渠道字段完整性](#9-渠道字段完整性)
10. [产品分期口径](#10-产品分期口径)
11. [数据口径差异详述](#11-数据口径差异详述)
12. [取数范围（源表清单）](#12-取数范围源表清单)
13. [参考文档与验证脚本](#13-参考文档与验证脚本)

---

## 1. 背景与目标

新催收系统（Phase1）以独立 Pub/Sub topic 接收数据，分流在路由层按 `caseId` 切片完成，老系统推送 schema 保持不变。本文件定义新 topic 上的消息契约，使数据 / 接入团队可据此完成适配层开发，保障分流测试推进。

核心结论：新系统采用**全新、独立**的消息契约，字段名由新系统统一定义，不复用老系统推送字段、不做任何老字段名兼容。旧库表仅作为取数来源，在适配层一次性计算为规范化的新消息后发出。新 topic 上仅存在 2 类消息，不存在多消息关联、缓冲等待或乱序处理。

---

## 2. 设计原则

1. **新消息即新定义**。契约字段统一 camelCase（与领域模型 SSOT 一致）。老推送的 `loanId/loanID`、`userId/userID`、`STATUS/status`、`planJson`、`currentAmmout`（错拼）等一律不出现于新契约。
2. **旧库表是取数源，不是契约**。每个新字段的"来源"标注旧库表 / 列（均已验证存在），仅表示适配层取数位置；新系统消费端只看新字段。
3. **无多消息关联**。一次案件入案 = 适配层读旧库计算出完整 `caseEvent` 发出，单条即完整。
4. **字段名彻底统一**。`caseId` 恒为字符串（适配层统一 cast `t_loan_repayment_plan.loan_id` INT64 与 `t_loan_repayment.loan_id` STRING），不再有大小写分支、拼写错或嵌套 JSON。
5. **计算逻辑内建**。`dpd` 统一取贷款级 Max DPD（全产品通用，不分 1/3 期）；`stage` 按渠道规格阈值计算；`pushToken` 取自 `detail.user.jpush_token`；FIRM 两字段由同一轮扫描计算并纳入契约。
6. **还款链接不计入字段**。`repayUrl` 为固定链接 `https://mocasa.com/s/4cTu`，由模板硬编码，不作为数据流字段。

---

## 3. 关键约束：数据口径差异（Bill 维度 vs Loan 维度 Max DPD）

> 本约束直接影响对账与验收，须优先知悉。

老系统按每期各自 due_date 独立计算账单 DPD（Bill 维度）；新系统按整笔贷款取最坏值 `MAX(overdue_days)`（Loan 维度 Max DPD）。该差异**仅存在于 3 期产品，属预期口径差异，不作为缺陷处理**。

- 示例：3 期贷款期1 逾期32天、期2 逾期2天 → 老系统分别报 32/2 天，新系统两期均计 **32** 天。
- 1 期产品：两种口径一致，无差异。
- 影响：并行测试期，同一 3 期账户在新老系统的 DPD / 催回率口径天然不同。测试报告须单列"口径差异"列，详情见第 11 节。

---

## 4. 消息契约总览

新系统内部消费 4 个事件，对外仅暴露 2 个消息类型，以 `eventType` 区分。

| 消息类型 | eventType 取值 | 触发条件 | 说明 |
| --- | --- | --- | --- |
| `caseEvent` | `CASE_INGESTED`<br>`CASE_STAGE_CHANGED`<br>`CASE_CEASED` | 案件入案<br>dpd 跨阈值<br>D+91 停催 | 案件级生命周期快照<br>（含 FIRM 两字段） |
| `repaymentEvent` | `REPAYMENT_RECEIVED`<br>`CASE_BALANCE_UPDATED` | 收到还款回单<br>整笔结清 | 实收净额与未结清状态<br>用于部分还款判定与停催 |

---

## 5. caseEvent 字段定义

### 5.1 字段示例

基于真实数据（3 期贷款 `loan_id=525441`，mocasa）：

```json
{
  "eventType": "CASE_INGESTED",
  "caseId": "525441",
  "userId": "2145521",
  "product": "mocasa",
  "stage": "S0",
  "dpd": -2,
  "maxDpd": -2,
  "caseStatus": "IN_COLLECTION",
  "overdueAmount": 9000.0,
  "penaltyAmount": 0.0,
  "totalOutstanding": 9000.0,
  "dueDate": "2026-08-11",
  "isFirstLoan": false,
  "payCount": 3,
  "repayPlan": [
    {
      "period": 1,
      "principal": 3000.0,
      "interest": 0.0,
      "penaltyInterest": 0.0,
      "dueDate": "2026-08-11",
      "status": "UNPAID",
      "clearTime": null
    },
    {
      "period": 2,
      "principal": 3000.0,
      "interest": 0.0,
      "penaltyInterest": 0.0,
      "dueDate": "2026-09-11",
      "status": "UNPAID",
      "clearTime": null
    },
    {
      "period": 3,
      "principal": 3000.0,
      "interest": 0.0,
      "penaltyInterest": 0.0,
      "dueDate": "2026-10-11",
      "status": "UNPAID",
      "clearTime": null
    }
  ],
  "borrower": {
    "name": "CORA PAULINE AQUINO HIZON",
    "phone": "+639563093217",
    "email": "hizoncorapauline@gmail.com",
    "language": "en"
  },
  "device": {
    "pushToken": "161a3797c92cf7eaea6"
  },
  "everReachedS2Overdue": true,
  "concurrentOverdueBills": 5
}
```

### 5.2 字段表

| 新字段 | 类型 | 取数源 | 计算逻辑 |
| --- | --- | --- | --- |
| `caseId` | String | `t_loan_repayment_plan.loan_id`<br>（INT64 → 字符串） | 直接取贷款号 |
| `userId` | String | `t_loan.user_id`<br>（NUMERIC → 字符串） | 直接取用户号 |
| `product` | String | `t_loan.product_id` | 原始值，不做品牌重映射（见第 10 节） |
| `stage` | String | 派生 | `computeStage(dpd)`（第 7.2 节） |
| `dpd` | Integer | `t_loan_repayment_plan.overdue_days` | `MAX(overdue_days)` 跨全期（第 7.1 节） |
| `maxDpd` | Integer | 同上 | 同 `dpd`；`CASE_CEASED` 默认 91 |
| `caseStatus` | String | 派生（第 7.3 节） | `IN_COLLECTION` / `SETTLED` / `CEASED` |
| `overdueAmount` | Decimal | `t_loan_repayment_plan` | `SUM(principal+interest)` 未结清期 |
| `penaltyAmount` | Decimal | 同上 | `SUM(penaltyInterest)` 未结清期 |
| `totalOutstanding` | Decimal | 派生 | `overdueAmount + penaltyAmount` |
| `dueDate` | Date | 同上 | `MIN(due_date)` 未结清期 |
| `isFirstLoan` | Boolean | `t_loan.user_loan_rank` | `== 1` |
| `payCount` | Integer | `t_loan.user_loan_rank_total` | 直接取 |
| `repayPlan[]` | Array | `t_loan_repayment_plan` | 每期一行，见第 8 节 |
| `borrower.name` | String | `user.user_name` | 直接取 |
| `borrower.phone` | String | `user.mobile` | 转 E.164 |
| `borrower.email` | String | `user.email` 或 `personal_email` | 取非空 |
| `borrower.language` | String | 配置常量 | Phase1 固定 `en` |
| `device.pushToken` | String | `user.jpush_token` | 适配层读 `detail.user` 填入 |
| `everReachedS2Overdue` | Boolean | `t_loan_repayment_plan` + `t_loan`（按 user） | 该用户全部 loan 的 `MAX(overdue_days) >= 4`（第 7.5 节） |
| `concurrentOverdueBills` | Integer | 同上 | 该用户"已到期且未结清" bill 计数（第 7.5 节） |

> 取数源均位于 `detail` schema，下文简写表名。

其他事件类型：`CASE_STAGE_CHANGED` 必填 `caseId`+`stage`+`dpd`+`maxDpd`；`CASE_CEASED` 必填 `caseId`+`maxDpd`（默认 91）。`fullRepayTime` 已剔除——结清由 `repayPlan[].clearTime` 全非 null 判定。FIRM 两字段直接纳入契约，与策略 tone 解耦；当前固定 `STANDARD` 不影响其产出。

---

## 6. repaymentEvent 字段定义

> 接入数据源：`detail.t_loan_repayment`（按 loan 汇总），每笔还款单插入即触发。

### 6.1 字段示例

基于真实数据（贷款 `52632`，3 期产品，分 2 笔还款单结清：第 1 笔还第 1 期，第 2 笔一笔结清剩余 2 期）：

```json
{
  "eventType": "REPAYMENT_RECEIVED",
  "caseId": "52632",
  "userId": "2145521",
  "messageId": "435684",
  "repayTime": "2024-05-24 16:27:43",
  "paidAmount": 827.0,
  "totalPeriods": 3,
  "remainingPeriods": 2,
  "remainingAmount": 1654.0,
  "isFullCleared": false,
  "product": "mocasa"
}
```

> **字段口径说明（催收视角）**：还款事件只承载「催收决策所需状态」，不再携带支付单的手续费口径字段。`paidAmount` 为本笔还款**实收净额**（第 1 期本息 827，不含 17 元支付手续费），还清后 `remainingPeriods=2`、`remainingAmount=1654`（剩余 2 期本息）。`paidAmount`（本笔记账实收）与 `remainingAmount`（剩余待收）口径不同（已收 vs 待收），不矛盾，且均不含支付手续费。

当同一笔还款导致全期清完时，适配层发出 `CASE_BALANCE_UPDATED`（`remainingPeriods=0` 即表示整笔结清）：

```json
{
  "eventType": "CASE_BALANCE_UPDATED",
  "caseId": "52632",
  "userId": "2145521",
  "messageId": "435685",
  "repayTime": "2024-05-24 16:33:10",
  "paidAmount": 1654.0,
  "totalPeriods": 3,
  "remainingPeriods": 0,
  "remainingAmount": 0.0,
  "isFullCleared": true,
  "product": "mocasa"
}
```

### 6.2 字段表

| 新字段 | 类型 | 取数源 | 计算逻辑 |
| --- | --- | --- | --- |
| `eventType` | String | 派生 | `isFullCleared` ? `CASE_BALANCE_UPDATED` : `REPAYMENT_RECEIVED` |
| `caseId` | String | `t_loan_repayment.loan_id` | 直接取，统一为字符串 |
| `userId` | String | `t_loan.user_id` | 直接取 |
| `messageId` | String | `t_loan_repayment.id` | 去重键 |
| `repayTime` | DateTime | `t_loan_repayment.trade_finish_time` | 直接取 |
| `paidAmount` | Decimal | `t_loan_repayment.real_amount` | 本笔**实收净额**（本息罚合计）<br>不含支付手续费 |
| `remainingPeriods` | Integer | 派生（`is_loan_clear`） | `COUNT(is_loan_clear != 0)`（第 7.7 节） |
| `totalPeriods` | Integer | `t_loan_repayment_plan` | 该 loan 总期数 |
| `isFullCleared` | Boolean | 派生（`is_loan_clear`） | `MAX(is_loan_clear) = 0`（第 7.7 节） |
| `remainingAmount` | Decimal | 派生 | 未结清期 `SUM(principal+interest+penalty_interest)` |
| `product` | String | `t_loan.product_id` | 原始值，路由 / 统计用 |

**字段关系**：

- `REPAYMENT_RECEIVED` 与 `CASE_BALANCE_UPDATED` 使用**同一 schema**，仅 `eventType` / `isFullCleared` 取值不同；订阅者可按 `eventType` 路由处理。
- `totalPeriods` / `remainingPeriods` / `remainingAmount` 由适配层写入，引擎无需回库即可决定响应。
- 部分还款由 `isFullCleared=false` 表达；每期明细由 `caseEvent.repayPlan[]` 承载，引擎收到事件后无需重读 `t_loan_repayment_plan` 即可刷新状态。

---

## 7. 计算逻辑

已验证真实存在的表（取数源）：`detail.t_loan_repayment_plan`、`detail.t_loan_repayment`、`detail.t_loan`、`detail.user`（另 `detail.t_repayment` 仅用于第 8 节结构验证，不作取数源）。

### 7.1 dpd / maxDpd — 贷款级 Max DPD

```sql
-- as_of = CURRENT_DATE('Asia/Shanghai')
-- 该 loan 全部期：取 overdue_days 最大值（overdue_days 即各期 DPD，正负含义一致）
dpd = MAX(overdue_days)   -- 当前快照 dpd，驱动 stage / 模板变量
```

验证样例：某 3 期贷款 `MAX(overdue_days) = -2`（D-2，未到期，处于可传输范围）；user 2145521 全部 loan `MAX(overdue_days) = 357`（已触达 S2 及以上）。

统一使用 `t_loan_repayment_plan`、不分 1/3 期：1 期产品每笔仅 1 期，`MAX` 即该期本身；3 期产品取最坏一期。两条产品共用同一 SQL，无需 `t_collection.overdue_day` 单独校验。该口径与校准 SQL（`MAX(bill_dpd)`）一致，可作对账基线。

### 7.2 stage 计算

```text
computeStage(dpd):
    dpd >= 91   → 无 Stage（CASE_CEASED 完全停催）
    dpd >= 31   → S4  (D+31~90)
    dpd >= 16   → S3  (D+16~30)
    dpd >= 4    → S2  (D+4~15)
    dpd >= 1    → S1  (D+1~3)
    dpd >= -3   → S0  (D-3~D0)
    dpd <  -3   → S0  (无提醒)
```

### 7.3 caseStatus — 由还款计划推导的生命周期状态

`caseStatus` 表示案件在催收体系中的生命周期状态，取值 `IN_COLLECTION` / `SETTLED` / `CEASED`。该字段**由还款计划状态推导，不从 `loan_status` 枚举映射**，理由如下：

- `loan_status`（来源 `detail.t_loan.loan_status`，取值 `complete/received/closed/reject`）是贷款产品的生命周期状态，与催收案件状态语义不一致，直接映射（如 `received→OVERDUE`）在语义上不成立。
- 案件状态应反映"是否仍在催收、是否已结清、是否已停催"，这些均可由还款计划（`repayPlan`）与 `dpd` 推导。

推导规则：

```text
deriveCaseStatus(repayPlan, dpd):
    if all periods have clearTime (non-null):  → SETTLED    # 全部账单已结清
    elif dpd >= 91:                             → CEASED    # 逾期超期，停催
    else:                                       → IN_COLLECTION
```

验证（交叉计数，全量）：

- `loan_status = complete` 的 437,089 笔贷款，**全部**满足"所有期已结清"，与 `SETTLED` 完全对应。
- `loan_status = received` 的 77,346 笔中，66,043 笔无任何期结清、11,095 笔部分结清，仅 208 笔全部结清（状态未及时更新）。即 `received` 主要对应"未全清、仍在催收"，与 `IN_COLLECTION` 对应。

结论：`loan_status` 可作为 `SETTLED` 的交叉校验参考（经验证 `complete` 必全清），但**不作为 `caseStatus` 的赋值来源**。案件状态的唯一权威来源是 `repayPlan` + `dpd`。

### 7.4 device.pushToken 取数

```sql
SELECT jpush_token FROM detail.user WHERE user_id = :userId;
```

真源为 `detail.user.jpush_token`（已验证，非 `t_user_extend.ji_guang_token`）。新契约不从推送消息体取 token。

### 7.5 FIRM 两字段

```sql
WITH plans AS (
  SELECT lp.loan_id, lp.period, lp.due_date, lp.clear_time, lp.overdue_days
  FROM detail.t_loan_repayment_plan lp
  JOIN detail.t_loan l ON lp.loan_id = l.loan_id
  WHERE l.user_id = :userId
)
SELECT
  MAX(overdue_days) AS max_bill_dpd,
  COUNT(CASE WHEN due_date <= CURRENT_DATE('Asia/Shanghai')
              AND (clear_time IS NULL OR clear_time > due_date)
             THEN 1 END) AS concurrent_overdue_bills
FROM plans;
```

验证（user 2145521）：`max_bill_dpd=357 → everReachedS2Overdue=true`、`concurrent_overdue_bills=5`。该查询与第 7.1 节共用同一份 `t_loan_repayment_plan` 扫描，无额外取数成本。两个字段常算常传，FIRM tone 接线后引擎据阈值选 `*_FIRM` 模板即可，无需变更取数。

### 7.6 整笔结清信号

**真实可用的整笔结清信号**：

- `detail.t_loan_repayment_plan.is_loan_clear`：单期账单状态枚举。
  - `0` = 已结清期。
  - `1` = 已到期未还。
  - `2` = 部分结清。
  - `3` = 未到期。
- 派生规则：

```text
isFullCleared(loan) =
    if MAX(is_loan_clear) over 该 loan 全部期 = 0:  true   # 全部期已结清
    else:                                            false
remainingPeriods(loan) =
    COUNT(*) of periods where is_loan_clear != 0            # 未结清期数
remainingAmount(loan) =
    SUM(principal+interest+penalty_interest) where is_loan_clear != 0
# clearedPeriods(loan) = totalPeriods − remainingPeriods   # 派生备用，不进 payload
```

> **取数信号说明**：结清判定一律以 `t_loan_repayment_plan.is_loan_clear` 为准（权威结清标志），**不使用 `clear_time` 与 `trade_finish_time` 的时间比较**。真实数据中存在 `clear_time` 晚于还款单 `trade_finish_time` 约 1 秒的情况（如贷款 `52632` 第 2 笔还款单），按时间比较会少算已结清期数。适配层须在该笔还款对应的 `is_loan_clear` 落库后再计算上述字段。

### 7.7 repaymentEvent 设计决策

**采纳同事的事件拆分设计，但用真实信号替代错误字段**：保留单一 `repaymentEvent` 消息类型，通过 `eventType` 字段区分：

| eventType | 触发条件 | 引擎响应 |
| --- | --- | --- |
| `REPAYMENT_RECEIVED` | `isFullCleared=false`（部分还款） | 刷新 `repayPlan[]`、继续催收 |
| `CASE_BALANCE_UPDATED` | `isFullCleared=true`（整笔结清） | 取消计划，切状态 `SETTLED` |

**适配层计算时机**：每笔 `t_loan_repayment` 新增时，适配层立即同步执行 `isFullCleared` / `remainingPeriods` / `remainingAmount` 计算（基于 `t_loan_repayment_plan`，与第 7.1 / 7.5 节共用同一次扫描），写入事件 payload。引擎无需回库即可决策。

**延迟容忍**：老系统推送周期约 20 分钟；新系统适配层随还款单到达实时触发上述计算，端到端延迟为分钟级，完全满足催收响应要求，无需秒级流处理架构。

**结清状态字段的设计（回应同事"是否设置该字段"的疑问）**：同事的原意是「新系统是否应在还款事件上携带一个"整笔结清"状态信号」，而非询问老库 `repayment_status` 字段当前是否存在。本契约的回答是**携带，且用新系统的自有信号表达**：

- 整笔结清状态由 `isFullCleared`（Boolean）+ `eventType` 分流（`REPAYMENT_RECEIVED` / `CASE_BALANCE_UPDATED`）承载，等价于同事所设想的"STATUS=4 表示整笔结清"语义，订阅者可据此直接停催；
- **不复制老表 `t_loan_repayment.repayment_status`**：该字段在全量 577,964 行中恒为 `2`，不携带任何区分信息，属老系统遗留，新契约不引入。

**不引入 `repayStatus` 守卫（方案 C）**：因老表 `repayment_status` 恒为 2，以其作为守卫是恒空操作；部分退款通过负 `real_amount` 表现（min=-3.0），`repayment_status` 同为 2 亦无法拦截。故事件触发交由上游保证（仅推送成功还款单），引擎收到即视为"已收到还款"，以 `isFullCleared` / `remainingAmount` 决定后续动作。

---

## 8. 3 期产品还款结构

验证（loan 358438，3 期全部提前结清）：

- `t_loan_repayment_plan`：3 期各自 `clear_time` 均填入（每期明细完整）。
- `t_loan_repayment`（按 loan）：2 条汇总记录（`apply=2295/real=2280`、`apply=4575/real=4560`），即跨期合并的多笔交易。
- `t_repayment`（按 plan）：该 loan 无记录——还款未必下钻到每期。

结论：

1. 3 期还款为跨多期账单的汇总，还款事件本身不保证带每期拆分。
2. 每期明细仅存在于 `detail.t_loan_repayment_plan`（`repayPlan[]`），是 Max DPD 与金额的唯一 SSOT。
3. `repaymentEvent` 不传每期明细。适配层在收到 `t_loan_repayment` 新增时同步计算 `remainingPeriods` / `isFullCleared` / `remainingAmount`（基于 `t_loan_repayment_plan`），并据此分流 `REPAYMENT_RECEIVED`（部分还款，催收继续）与 `CASE_BALANCE_UPDATED`（整笔结清，停催）。引擎按 `eventType` 决定响应，无需重读 plan 表。
4. `repayPlan[]` 在 `CASE_STAGE_CHANGED` 及每次还款后应重算刷新。

---

## 9. 渠道字段完整性

| 渠道 | 结论 | 缺口 |
| --- | --- | --- |
| SMS | 完整 | 无（repayUrl 模板内硬编码） |
| EMAIL | 完整 | 无 |
| PUSH | 完整 | `device.pushToken` ← `user.jpush_token` |
| AI_CALL | 完整 | 部署配置项（callbackUrl 等） |

全渠道字段对 `caseEvent` / `repaymentEvent` 已完整。

---

## 10. 产品分期口径

`detail.t_loan.product_id` 直接作为 `product` 字段值（原始值，不做品牌重映射）。

分期口径（基于真实表 `detail.t_loan.product_id`）：

| product_id | 分期 |
| --- | --- |
| 1, 2, 5, 6, 7 | 1 期 |
| 3, 4 | 3 期 |

仅 3 期产品（product_id ∈ {3,4}）存在 Bill / Loan 维度 DPD 口径差异（第 3 / 11 节）；1 期产品两口径一致。

---

## 11. 数据口径差异详述

| 维度 | 老系统（Bill 维度） | 新系统（Loan Max DPD） |
| --- | --- | --- |
| DPD | 每期按各自 due_date 独立算 | 整笔取 `MAX(overdue_days)` |
| 示例 | 期1=32天、期2=2天 → 分别报 32/2 | 两期均计 32 天 |

- 1 期产品：两种口径一致，无差异。
- 3 期产品：差异显著，属预期差异。
- 校准 SQL 中的 `S2≥4 / S3≥16 / S4≥31 / CO≥91` 为 vintage 报告桶，与运营阶段枚举（第 7.2 节）不是同一套，对账勿混淆。

---

## 12. 取数范围（源表清单）

### `detail.t_loan_repayment_plan`

- **用到的列**：`loan_id`, `user_id`, `period`, `principal`, `interest`, `penalty_interest`, `due_date`, `is_loan_clear`, `clear_time`, `overdue_days`
- **产出字段**：`dpd`, `maxDpd`, `overdueAmount`, `penaltyAmount`, `totalOutstanding`, `dueDate`, `repayPlan[]`, `isFullCleared`, `remainingPeriods`, `remainingAmount`, FIRM 两字段

### `detail.t_loan`

- **用到的列**：`loan_id`, `user_id`, `product_id`, `product_name`, `loan_status`, `user_loan_rank`, `user_loan_rank_total`
- **产出字段**：`caseId`, `userId`, `product`, `isFirstLoan`, `payCount`

### `detail.user`

- **用到的列**：`user_id`, `user_name`, `mobile`, `email`, `personal_email`, `jpush_token`
- **产出字段**：`borrower.*`, `device.pushToken`

### `detail.t_loan_repayment`

- **用到的列**：`loan_id`, `id`, `real_amount`, `trade_finish_time`
- **产出字段**：`caseId`, `messageId`, `paidAmount`, `repayTime`（repaymentEvent）

### 配置

- **用到的列**：语言（Phase1 固定 `en`）
- **产出字段**：`borrower.language`

新系统仅消费上述字段；老系统其余字段（画像、申请、remark 等）适配层一律忽略。

---

## 13. 参考文档与验证脚本

- [核心引擎规格](../MOCASA催收系统升级_Phase1_核心引擎规格.md)
- [领域模型与数据定义](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md)
- [渠道编排规格](../channel/MOCASA催收系统升级_Phase1_渠道编排规格.md)（Stage 阈值 / 模板变量 / 策略标记）
- [ContextSnapshot 契约对齐](../contracts/README_ContextSnapshot契约对齐.md)
