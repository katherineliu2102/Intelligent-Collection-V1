# ContextSnapshot 契约对齐（发给编排同事 / 服务同事）

> **版本**: Phase 1  
> **日期**: 2026-06-29  
> **范围**: 仅覆盖菲律宾市场  
> **模块**: `collection-common`  
> **关联文档**: [领域模型 §6.2](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md#62-逐事件-payload-字段)、[ContextSnapshot.sample.json](./ContextSnapshot.sample.json)、[数据接入规格 §3.2](../MOCASA催收系统升级_Phase1_数据接入规格.md#32-投影写入inbox-与幂等)

---

## 数据流向

```
接入层从完整 `caseEvent` 填充入案快照；增量 `repaymentEvent` 只合并运行态金额、下一期提醒和结清状态，不经 CaseService 回填
        → 引擎建计划时据 payload 组装 ContextSnapshot → JSON 落 t_contact_plan.context_snapshot
        → SPI 决策只读快照（零 DB I/O）
        → StepResolver(编排同事) 读快照产出 StepCommand(channelType/targetAddress/templateId)
        → ChannelGateway 真实发送
```

## 跑通一条「消息渠道」真实数据所需的最小必填字段

> 原则：先填跑通**一个渠道**的最小集，其余字段可暂留 `null`（阶段 2 约定）。

### SMS

| 字段路径 | 用途 | 谁负责 |
|---|---|---|
| `caseContext.caseId` / `userId` / `stage` / `dpd` | 选模板、定位案件；caseId=信贷 loan_id | ingestion 映射/回填，引擎组装 |
| `caseContext.totalOutstanding` | 模板 `amount_due`（金额 SSOT，来自已含罚息的 `overdueAmount`） | ingestion 映射/回填 |
| `caseContext.repaymentUrl` | 模板 `payment_link` | 引擎按受控 repayment-url-template 生成 |
| `userProfile.basic.primaryPhone` | SMS `targetAddress`（E.164 `+63` 格式） | ingestion payload |
| `userProfile.basic.name` | 模板 `borrower_name` | ingestion payload |
| `userProfile.basic.language` | `metadata.language`（默认 `en`） | ingestion payload |
| `contactHistory.todayTouchCount` / `channelTouchCounts` | 冻结审计快照；运行时频控不读此值 | 引擎/计数器 |

### PUSH

| 字段路径 | 用途 | 谁负责 |
|---|---|---|
| `caseContext.caseId` / `userId` / `stage` | 选模板、定位 | 完整 `caseEvent` 映射/回填，引擎组装 |
| `userProfile.device.jpushToken` | PUSH `targetAddress`（JPush Registration ID）；空 → PushAdapter 同槽 fallback SMS | 完整 `caseEvent.device.pushToken`；还款增量不带该字段，缺失不回查 |
| `caseContext.repaymentUrl` | `data.deep_link` | 引擎按受控 repayment-url-template 生成 |

### EMAIL

| 字段路径 | 用途 | 谁负责 |
|---|---|---|
| `caseContext.caseId` / `userId` / `stage` / `dpd` | 选模板、定位、变量 `overdue_days` | ingestion 映射/回填，引擎组装 |
| `userProfile.basic.email` | EMAIL `targetAddress`；空值 → Guard `NO_EMAIL` → COMPLIANCE_BLOCKED | ingestion payload |
| `caseContext.repaymentUrl` | 模板 `payment_link` | 引擎按受控 repayment-url-template 生成 |

> ⚠ 三渠道共用**同一份** ContextSnapshot，按 `channelType` 取地址：
> SMS→`basic.primaryPhone`、PUSH→`device.jpushToken`、EMAIL→`basic.email`。
> `targetAddress` 由 **StepResolver** 从快照填入 `StepCommand`，Gateway/Adapter **不再取号**。唯一例外是 PUSH 无 token 时，Gateway 可在同一 dispatch 使用已解析的 SMS 地址 fallback；该 fallback 不读库。
> 真实 `StepResolver` 须按渠道分支取号（`MockStepResolver` 已按此分支，真实实现由编排同事替换）。

## 金额 SSOT（对外文案变量）

对外文案金额变量**只认 `caseContext.*`**。（注：原 `userProfile.repayment.*` 仅画像辅助、禁用于文案，已于 **2026-06-18** 从 Phase 1 模型移除，见文末变更。）

| 文案变量 | SSOT 字段 | 新鲜度 |
|---|---|---|
| `amount_due` | `caseContext.totalOutstanding` | **发送时刻值**：引擎在 ④ 解析前用步骤② 的实时 `CaseInfo` 覆盖内存快照 |
| `overdue_days` | `caseContext.dpd` | 同上 |
| 罚息展示 | `caseContext.penaltyAmount` | 快照冻结值；Phase 1 任何模板均未渲染，如要展示须先接入同一刷新链路 |

> **日变字段刷新（2026-08-11）**：`dpd` / `totalOutstanding` 进入用户可见文案，快照冻结值会失真（单阶段最长跨 60 天，S4 = DPD 31–90）。引擎在步骤② 已实时读到 `CaseInfo`，解析前用它覆盖**内存中的**快照副本，`stage` 不覆盖（决定模板与话术，须与计划一致）。编排侧无需改动：`StepResolver` 照常从 `ExecutionContext` 读快照，拿到的就是当日真值。

## 开放问题（已定稿 2026-06-09，详见[契约对齐回复](./_archive/MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐_re.md) §6）

1. **PUSH device token 来源**：✅ 上游完整快照 `device.pushToken`。ingestion 映射进 payload → 快照 `device.jpushToken`；缺失 → PUSH fallback SMS。**不使用 fcmToken。**
2. **`targetAddress` 由谁定**：✅ 已决 → **StepResolver** 从快照填入，Gateway/Adapter 不再取号。
3. **手机号格式**：✅ 已决 → 快照统一 **E.164 `+63...`**；通知中心 `mobile` 可容错，Adapter 可再归一化。
4. **`work.*` / `risk.*` 等是否需要**：✅ 消息渠道模板可不填。`repayment.*`（金额冗余）与 `risk.*` 已从 Phase 1 移除；`work.* / contacts.* / behavior.* / device.{deviceModel,osVersion,phoneValidity,viber/whatsapp}` 及 `basic` 人口属性结构保留、Phase 1 不填充（Phase 2 预留）。Offer 字段、投诉冻结/Override 均为 Phase 2，不得作为 Phase 1 StepResolver 输入。

## 约定

- 快照契约（本目录）由**我（主架构）维护**；任何字段增删先在此对齐再改 `collection-common`。
- 样例 JSON 字段名 = Java 模型字段名（fastjson 默认）；注意 `isFirstLoan` 序列化为 `firstLoan`。

## 变更记录

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-08-12 | **`jpushToken` 来源 = `caseEvent.device.pushToken`** | 用户存在 JPush 注册时上游携带；无 token 不回查，由渠道 fallback SMS。 |
| 2026-08-17 | **还款增量与运行态字段收敛** | `caseEvent` 是完整快照；`repaymentEvent` 为增量，不带产品、借款人、设备或 `caseVersion`，但携带还款后的 `dpd`、`stage`、金额和三期提醒字段。`overdueAmount`（含罚息）映射 `totalOutstanding`，三期提醒使用 `upcomingAmount` / `nextDueDate`，不取代历史 `dueDate`。 |
| 2026-08-12 | **入案快照字段上游契约收敛（已被 2026-08-17 收敛替代）** | 旧“两类完整快照 / 日期全面必填”口径不再适用；其余 poison 与 repaymentUrl 约束保持。 |
| 2026-08-11 | **`dpd` / `totalOutstanding` 渲染前刷新** | 复用步骤② 已有的实时读覆盖内存快照（不回写快照列、不覆盖 `stage`）；日切 `STAGE_CHANGED` 另携带这两个字段刷新新计划快照列。编排侧无需改动。 |
| 2026-06-18 | **`ContactHistory.ptpCount` / `ptpFulfilledCount` Phase 1 为 null** | 类型 `int`→`Integer`；Phase 1 不计算 PTP，返回 null（非 0），避免与「零承诺」混淆。样例 JSON 已同步为 null。 |
