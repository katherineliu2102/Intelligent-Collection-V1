# ContextSnapshot 契约

> 适用：`collection-common`、`collection-ingestion`、`collection-engine`、`collection-channel`
> 权威字段定义：[领域模型](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md)；冻结样例：[可触达](./ContextSnapshot.sample.json) / [空地址](./ContextSnapshot.empty-address.sample.json)。

## 职责边界

1. 完整 `caseEvent` 填充案件、用户与设备快照；`repaymentEvent` 只更新运行态金额、下一期提醒与结清状态。
2. 引擎将快照写入 `t_contact_plan.context_snapshot`；SPI 只读快照，不查库。
3. `StepResolver` 根据渠道从快照生成 `StepCommand`；Gateway 和 Adapter 不得重新查库取号。

## 消息渠道最小字段

| 字段路径 | SMS | PUSH | EMAIL | 来源 / 规则 |
|---|:---:|:---:|:---:|---|
| `caseContext.caseId` / `userId` / `stage` | 必填 | 必填 | 必填 | `caseId = loanId`；ingestion 映射、引擎组装 |
| `caseContext.dpd` | 必填 | — | 必填 | 选模板及 `overdue_days` |
| `caseContext.totalOutstanding` | 必填 | — | — | `overdueAmount`（含罚息）映射；文案 `amount_due` |
| `caseContext.repaymentUrl` | 必填 | 必填 | 必填 | 引擎按受控 repayment-url-template 生成 |
| `userProfile.basic.primaryPhone` | 必填 | fallback | — | E.164 `+63...` |
| `userProfile.basic.name` | 必填 | — | — | 文案 `borrower_name` |
| `userProfile.basic.language` | 必填 | — | — | `metadata.language`；空值默认 `en` |
| `userProfile.device.jpushToken` | — | 必填 | — | 来自完整 `caseEvent.device.pushToken`；空值同槽 fallback SMS，不回查 |
| `userProfile.basic.email` | — | — | 必填 | 空值由 Guard 返回 `NO_EMAIL` |

## 寻址与发送边界

| 渠道 | `targetAddress` | 空地址行为 |
|---|---|---|
| SMS | `basic.primaryPhone` | Guard `NO_PHONE` → `COMPLIANCE_BLOCKED` |
| PUSH | `device.jpushToken` | token 空时同一 dispatch fallback SMS；手机号也空则 Guard `NO_TOKEN` |
| EMAIL | `basic.email` | Guard `NO_EMAIL` → `COMPLIANCE_BLOCKED` |

`targetAddress` 由 `DefaultStepResolver` 填入 `StepCommand`。PUSH fallback 只能使用已解析的手机号，不得查库。

## 文案字段

| 文案变量 | 字段 | 取值规则 |
|---|---|---|
| `amount_due` | `caseContext.totalOutstanding` | 发送前由实时 `CaseInfo` 覆盖内存快照 |
| `overdue_days` | `caseContext.dpd` | 发送前由实时 `CaseInfo` 覆盖内存快照 |
| 罚息展示 | `caseContext.penaltyAmount` | Phase 1 模板不渲染；后续使用须接入同一刷新链路 |

`stage` 决定模板与话术，发送前不覆盖。`work.*`、`contacts.*`、`behavior.*`、`risk.*`、Offer、投诉冻结和 Override 不是 Phase 1 `StepResolver` 输入。

## 约定

- PUSH 只使用 `jpushToken`，不使用 `fcmToken`。
- 样例 JSON 字段名与 Java 模型字段名一致；`isFirstLoan` 序列化为 `firstLoan`。
