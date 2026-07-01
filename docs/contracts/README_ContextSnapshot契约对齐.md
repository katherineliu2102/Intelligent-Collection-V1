# ContextSnapshot 契约对齐（发给编排同事 / 服务同事）

> **版本**: Phase 1  
> **日期**: 2026-06-29  
> **范围**: 仅覆盖菲律宾市场  
> **模块**: `collection-common`  
> **关联文档**: [领域模型 §9.2](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md#92-逐事件-payload-字段)、[ContextSnapshot.sample.json](./ContextSnapshot.sample.json)、[数据接入规格 §3.1](../MOCASA催收系统升级_Phase1_数据接入规格.md#34-与-caseservice--profileservice-的调用边界)

---

## 数据流向

```
接入层从 case_push 填充快照字段 → 随 CASE_INGESTED payload 带出（不读旧库）
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
| `userProfile.device.jpushToken` | PUSH `targetAddress`（JPush Registration ID）；空 → PushAdapter 同槽 fallback SMS | **终态**：上游 `case_push` 消息体。**Phase 1**：数仓日同步旧库 `t_user_extend.ji_guang_token` → 新库 `t_user_device_token`；ingestion **只读新库** 补全进 payload（[接入 §3.1](../MOCASA催收系统升级_Phase1_数据接入规格.md#35-jpushtoken-phase-1-数仓同步--接入-enrichment)）。引擎不查库 |
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

对外文案金额变量**只认 `caseContext.*`**。（注：原 `userProfile.repayment.*` 仅画像辅助、禁用于文案，已于 **2026-06-18** 从 Phase 1 模型移除，见文末变更。）

| 文案变量 | SSOT 字段 |
|---|---|
| `amount_due` | `caseContext.totalOutstanding` |
| `overdue_days` | `caseContext.dpd` |
| 罚息展示 | `caseContext.penaltyAmount` |

## 开放问题（已定稿 2026-06-09，详见 [契约对齐回复](./README_ContextSnapshot契约对齐.md) §6）

1. **PUSH device token 来源**：✅ 字段口径 `device.jpushToken`（JPush RID）已决。**Phase 1（2026-06-29）**：数仓日同步 `t_user_extend` → 新库 `t_user_device_token`；ingestion 只读新库写 payload；**终态**上游 `case_push` 自带。缺失 → fallback SMS。
2. **`targetAddress` 由谁定**：✅ 已决 → **StepResolver** 从快照填入，Gateway/Adapter 不再取号。
3. **手机号格式**：✅ 已决 → 快照统一 **E.164 `+63...`**；通知中心 `mobile` 可容错，Adapter 可再归一化。
4. **`work.*` / `risk.*` 等是否需要**：✅ 消息渠道模板可不填。**更新（2026-06-18，编排同事已授权）**：`repayment.*`（金额冗余）与 `risk.*`（编排策略不需要、PTP 履约率暂不计算）**Phase 1 移除**；`work.* / contacts.* / behavior.* / device.{deviceModel,osVersion,phoneValidity,viber/whatsapp}` 及 `basic` 人口属性 **结构保留、Phase 1 不填充（Phase 2 预留）**。

## 约定

- 快照契约（本目录）由**我（主架构）维护**；任何字段增删先在此对齐再改 `collection-common`。
- 样例 JSON 字段名 = Java 模型字段名（fastjson 默认）；注意 `isFirstLoan` 序列化为 `firstLoan`。

## 变更记录

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-06-18 | **移除 `UserProfile.repayment`（RepaymentInfo）与 `UserProfile.risk`（RiskScore）** | 编排同事已授权。理由：`repayment.*` 与 `caseContext` 金额冗余且禁用于文案；`risk.*` 编排策略不需要、PTP 履约率暂不计算；二者均无代码消费。`work / contacts / behavior / device 扩展维度` 与 `basic` 人口属性结构保留，Phase 1 不填充（Phase 2 预留）。样例 JSON 已同步移除两块。 |
| 2026-06-18 | **`ContactHistory.ptpCount` / `ptpFulfilledCount` Phase 1 为 null** | 类型 `int`→`Integer`；Phase 1 不计算 PTP，返回 null（非 0），避免与「零承诺」混淆。样例 JSON 已同步为 null。 |
