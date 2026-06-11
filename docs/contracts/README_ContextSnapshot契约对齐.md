# ContextSnapshot 契约对齐（发给编排同事 / 服务同事）

> 用途：阶段 0「冻结快照契约」产物。编排同事拿样例即可开发/确认 `StepResolver`，
> 服务同事据此确认旧库（`t_collection` 等）→ 快照字段映射，**不必互相等待**。
>
> 样例文件：[`ContextSnapshot.sample.json`](./ContextSnapshot.sample.json)
> 模型定义：`collection-common/.../model/ContextSnapshot.java`（含 `CaseContext` / `UserProfile` / `ContactHistory`）

## 数据流向

```
数据接入层(我) 组装 ContextSnapshot → JSON 落 t_contact_plan.context_snapshot
        → SPI 决策只读快照（零 DB I/O）
        → StepResolver(编排同事) 读快照产出 StepCommand(channelType/targetAddress/templateId)
        → ChannelGateway 真实发送
```

## 跑通一条「消息渠道」真实数据所需的最小必填字段

> 原则：先填跑通**一个渠道**的最小集，其余字段可暂留 `null`（阶段 2 约定）。

### SMS

| 字段路径 | 用途 | 谁负责 |
|---|---|---|
| `caseContext.caseId` / `userId` / `stage` / `dpd` | 选模板、定位案件 | 服务同事映射 |
| `caseContext.totalOutstanding` | 模板 `amount_due`（金额 SSOT） | 服务同事映射 |
| `caseContext.repaymentUrl` | 模板 `payment_link` | 接入 / 信贷结账链路 |
| `userProfile.basic.primaryPhone` | SMS `targetAddress`（E.164 `+63` 格式） | 服务同事映射 |
| `userProfile.basic.name` | 模板 `borrower_name` | 服务同事映射 |
| `userProfile.basic.language` | `metadata.language`（默认 `en`） | 服务同事映射 |
| `userProfile.basic.alternatePhones` | 备用号（可选） | 服务同事映射 |
| `userProfile.device.phoneValidity` | 无效号过滤 | 服务同事映射 |
| `contactHistory.todayTouchCount` / `channelTouchCounts` | 频控守卫 | 我（接入）组装 |

### PUSH

| 字段路径 | 用途 | 谁负责 |
|---|---|---|
| `caseContext.caseId` / `userId` / `stage` | 选模板、定位 | 服务同事映射 |
| `userProfile.device.jpushToken` | PUSH `targetAddress`（JPush Registration ID）；空 → PushAdapter 同槽 fallback SMS | App → `t_user_equipment` → ProfileService |
| `caseContext.repaymentUrl` | `data.deep_link` | 接入 / 信贷结账链路 |
| `userProfile.behavior.appLastActiveTime` | 活跃度判断（可选块） | 服务同事映射 |

### EMAIL

| 字段路径 | 用途 | 谁负责 |
|---|---|---|
| `caseContext.caseId` / `userId` / `stage` / `dpd` | 选模板、定位、变量 `overdue_days` | 服务同事映射 |
| `userProfile.basic.email` | EMAIL `targetAddress`；`null` → Guard `NO_EMAIL` → 步骤 SKIP | 服务同事映射 |
| `caseContext.repaymentUrl` | 模板 `payment_link` | 接入 / 信贷结账链路 |

> ⚠ 三渠道共用**同一份** ContextSnapshot，按 `channelType` 取地址：
> SMS→`basic.primaryPhone`、PUSH→`device.jpushToken`、EMAIL→`basic.email`。
> `targetAddress` 由 **StepResolver** 从快照填入 `StepCommand`，Gateway/Adapter **不再取号**。
> 真实 `StepResolver` 须按渠道分支取号（`MockStepResolver` 已按此分支，真实实现由编排同事替换）。

## 金额 SSOT（对外文案变量）

对外文案金额变量**只认 `caseContext.*`**；`userProfile.repayment.*` 仅画像辅助，**不得**被 StepResolver 用作文案变量。

| 文案变量 | SSOT 字段 |
|---|---|
| `amount_due` | `caseContext.totalOutstanding` |
| `overdue_days` | `caseContext.dpd` |
| 罚息展示 | `caseContext.penaltyAmount` |

## 开放问题（已定稿 2026-06-09，详见 [契约对齐回复](./MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐_re.md) §6）

1. **PUSH device token 来源**：✅ 已决 → `device.jpushToken`（JPush RID），App → `t_user_equipment` → ProfileService。
2. **`targetAddress` 由谁定**：✅ 已决 → **StepResolver** 从快照填入，Gateway/Adapter 不再取号。
3. **手机号格式**：✅ 已决 → 快照统一 **E.164 `+63...`**；通知中心 `mobile` 可容错，Adapter 可再归一化。
4. **`work.*` / `risk.*` 等是否需要**：✅ 消息渠道模板可不填；**结构保留**，PlanFactory/Guard 可能读取。

## 约定

- 快照契约（本目录）由**我（主架构）维护**；任何字段增删先在此对齐再改 `collection-common`。
- 样例 JSON 字段名 = Java 模型字段名（fastjson 默认）；注意 `isFirstLoan` 序列化为 `firstLoan`。
