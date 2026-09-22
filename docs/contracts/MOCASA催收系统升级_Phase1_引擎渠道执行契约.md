# 引擎↔渠道执行契约

> **状态**: ✅ 已确定（dispatch / metadata / 观察期 / 空地址 / token）；未闭合项见 [未对齐](#未对齐)  
> **日期**: 2026-09-01  
> **适用范围**: `collection-engine`、`collection-channel`  
> **上游**: 字段与寻址 [ContextSnapshot 契约](./README_ContextSnapshot契约对齐.md)；状态机与 SPI [核心引擎规格 §4 / §6](../MOCASA催收系统升级_Phase1_核心引擎规格.md#4-计划生命周期与状态机)

---

## 目录

- [已对齐](#已对齐)
  - [StepResult 与重试](#stepresult-与重试)
  - [StepCommand](#stepcommand)
  - [完成、观察期与幂等](#完成观察期与幂等)
  - [空地址](#空地址)
- [未对齐](#未对齐)
- [固定边界](#固定边界)

---

## 已对齐

### StepResult 与重试

渠道按请求是否可证明未发送分类：

| 结果 | `success` | `retryable` | 引擎行为 |
|---|:---:|:---:|---|
| 供应商受理 | true | false | 消息渠道完成步骤 |
| 可证明未发送：熔断、未配置、429、DNS/连接/TLS 失败 | false | true | 未超重试上限时退避重试 |
| 结果未知或确定失败：读超时、写后中断、5xx、地址/业务错误、退订 | false | false | 标记失败并推进，不重发 |

未分类的 `dispatch` 异常由引擎按结果未知处理，写入 `CHANNEL_OUTCOME_UNKNOWN`。`errorCode` 只写 timeline 对账，引擎不按其值分支。

### StepCommand

| 字段 | 规则 |
|---|---|
| `targetAddress` | `DefaultStepResolver` 从快照填充；Gateway/Adapter 不查库取号 |
| `metadata.language` | `basic.language`，空值默认 `en` |
| `metadata.stage` | `plan.stage` |
| `metadata.callbackUrl` / `timeoutMinutes` | 仅异步渠道使用 |
| `templateId` | PlanFactory 透传；映射由渠道模板配置维护 |

### 完成、观察期与幂等

- SMS、PUSH、EMAIL 成功 `dispatch` 后立即 `STEP_COMPLETED`，`observationMinutes=0`。
- `idempotencyKey`：`{planId}:{stepOrder}:{retryCount}`，用于尝试级去重与审计。

### 空地址

| 渠道 | 处理 |
|---|---|
| SMS 无手机号 | Guard `NO_PHONE` → `SKIPPED`，结果为 `COMPLIANCE_BLOCKED` |
| EMAIL 无邮箱 | Guard `NO_EMAIL` → `SKIPPED`，结果为 `COMPLIANCE_BLOCKED` |
| PUSH 无 token | 有手机号时同槽 fallback SMS；token 与手机号均为空时 Guard `NO_TOKEN_NO_PHONE` → `SKIPPED`，结果为 `COMPLIANCE_BLOCKED` |

`StepResolver` 返回 `null` 仅表示策略性跳过。Guard 拦截后的 timeline 由引擎唯一写入。

## 未对齐

| 项目 | 状态 | 说明 |
|---|---|---|
| 供应商幂等透传 | ❓ 待确认 | 已生成稳定键 `providerIdempotencyKey={planId}:{stepOrder}`；Notification 与 SendGrid 尚未透传，Facade 仅映射为 `externalBatchId`，供应商去重保证未验证。须编排同事确认各供应商是否接受该键。⏳ Phase 1 不据此放开结果未知后的重试（默认按已对齐的「未知即不重发」）。 |
| Facade AI_CALL 回调 | ❓ 待确认 | 通用回调入口已具备；Facade 的账户级回调反查、签名和结果映射未完成，须编排同事按 [Facade 回调入站交接 §2 / §3](../channel/MOCASA催收系统升级_Phase1_AI_Call_Facade回调入站交接.md#2-关联怎么从回调找回步骤) 闭合。 |

## 固定边界

- PUSH 只使用 `device.jpushToken`，不使用 `fcmToken`。
- Phase 1 不生成 `HUMAN_CALL` step。
- AI_CALL 为异步渠道；消息渠道的投递/打开回执只做 timeline enrichment，不影响步骤完成。
