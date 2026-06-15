# 引擎↔渠道执行契约对齐（email / SMS / push 联调前置）

> **用途**：L2 联调（email/SMS/push）前，引擎与编排同事需书面确认的**执行运行时语义**。
> **与既有文档分工**：
> - 快照**数据**契约（字段/来源/SSOT）见 [`ContextSnapshot契约对齐_re.md`](./MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐_re.md)
> - SPI 实现/超时/生命周期 E1–E8 见 [`README_编排同事对齐清单.md`](./README_编排同事对齐清单.md)
> - 本文聚焦 **dispatch 回填 / 入参 metadata / 观察期与幂等 / 空地址** 四项，是上两者未覆盖的执行语义。
> **现状基线**：渠道侧 Mock 已实现 happy-path 初版值（见各节“现状”），**失败/边界值待真实化**。
> **维护**：引擎维护本契约；编排同事按此实现 `ChannelGateway` / `StepResolver` / `ExecutionGuard`。

---

## 速览：4 项对齐 + 现状

| # | 对齐项 | 现状（Mock 初版） | 待确认 |
|---|---|---|---|
| 1 | StepResult 回填 | 恒 `success=true / DELIVERED / retryable=false` | 失败映射、errorCode 表 |
| 2 | StepCommand metadata | `language="en"` 硬编码；异步 `timeoutMinutes=60` | language 取快照、各渠道 timeout、templateId 表 |
| 3 | 观察期 + 幂等 | 幂等 key = `plan:stepOrder:retryCount`（引擎/Mock 已一致） | 各渠道观察期时长、谁设 observationMinutes |
| 4 | 空地址处理 | Resolver 地址空返回 null；Guard 恒放行（**NO_EMAIL 未实现**） | 空地址走 Guard SKIP 还是 Resolver/Gateway |

---

## 1. StepResult 回填契约（最高优先）

### 现状
`MockChannelGateway.dispatch()` 恒返回 `success=true, contactResult=DELIVERED, retryable=false, providerMsgId=mock-xxx`，**没有任何失败分支**。

### 待对齐：失败映射与 errorCode 表（推荐样例）

| 渠道返回情形 | `success` | `contactResult` | `retryable` | `errorCode`（建议） |
|---|---|---|---|---|
| 发送受理/送达 | true | DELIVERED | false | — |
| 网络超时 / 供应商 5xx | false | FAILED | **true** | `*_TIMEOUT` / `*_PROVIDER_5XX` |
| 限流 / 配额 | false | FAILED | **true** | `*_RATE_LIMITED` |
| 地址无效（号码/邮箱/token 非法） | false | FAILED | **false** | `SMS_INVALID_NUMBER` / `EMAIL_INVALID_ADDR` / `PUSH_INVALID_TOKEN` |
| 退订 / 拒收 | false | REJECTED | **false** | `*_UNSUBSCRIBED` |
| 渠道熔断 | false | CHANNEL_DOWN | **true** | `*_CIRCUIT_OPEN` |

> 引擎语义：`retryable=true` 且未超 `maxRetryCount(3)` → 退避重试；否则 FAILED 推进。`errorCode` 仅落 timeline 对账，引擎不解析其值。

**讨论结论**：________________

## 2. StepCommand 寻址与 metadata

### 现状
`MockStepResolver`：地址按渠道分支取（SMS=`basic.primaryPhone` / PUSH=`device.jpushToken` / EMAIL=`basic.email`，已对齐 _re.md §12）；`metadata.language="en"` 硬编码；异步渠道写 `callbackUrl` + `timeoutMinutes=60`。

### 待对齐（推荐样例）

| 项 | 现状 | 推荐 |
|---|---|---|
| `metadata.language` | 硬编码 `"en"` | 取 `snapshot.basic.language`，空则默认 `"en"` |
| `metadata.timeoutMinutes` | 异步固定 60 | 仅异步渠道需要；消息类不设。AI_CALL/TTS 建议 60，按 LTH 实际可调 |
| `metadata.stage` | 取 `plan.stage` | 保持 |
| `templateId` | `step.templateId`，空则 `"default"` | 确认模板 ID 取值表（按 stage × channel） |
| `targetAddress` | 见 _re.md §12 | 保持，不在此重复 |

**讨论结论**：________________

## 3. 观察期（STEP_WAITING）与幂等

### 现状
- 幂等 key 维度 = `planId:stepOrder:retryCount`，**引擎 `buildIdempotencyKey` 与 `MockStepResolver` 已一致**（重试用新 key，不自锁）。
- `observationMinutes` 由 `step` 携带（PlanFactory 设定），引擎只读；Mock 未设 → 走无观察期分支。

### 待对齐
- 各消息渠道默认观察期时长（SMS / PUSH / EMAIL 分别多少分钟，0 = 无观察期立即完成）。
- 确认 `observationMinutes` 归属：由 `PlanFactory` 按 stage 设定，引擎与 StepResolver 都不改。
- 编排真实 `ChannelGateway` 的幂等维度须与引擎一致（`plan:stepOrder:retryCount`）。

**讨论结论**：________________

## 4. 空地址处理（已发现不一致，需拍板）

### 现状（不一致）
- 契约/用例 C3 期望：EMAIL `email` 为空 → `ExecutionGuard` 判 `NO_EMAIL` → 步骤 **SKIPPED**。
- 实际：`MockExecutionGuard` **恒放行**；`MockStepResolver` 地址空时返回 `null` → `StepCommand.targetAddress=null` 会被 dispatch 出去。
- **结论：NO_EMAIL / NO_TOKEN 的拦截逻辑目前没人实现。**

### 待对齐（三选一，推荐 A）

| 方案 | 做法 | 引擎现成支持 | 评价 |
|---|---|---|---|
| **A（推荐）** | `ExecutionGuard` 检测空地址 → `block(NO_EMAIL/NO_TOKEN)` | ✅ 引擎已有 block→SKIPPED+推进 | 语义最清晰，复用现有管线 |
| B | `StepResolver` 空地址抛异常 | ⚠ 走 FAILED 分支 | 把“无地址”当错误，语义偏差 |
| C | `ChannelGateway` 内 fallback（如 PUSH 无 token → 转 SMS） | ⚠ 对引擎透明 | 仅适合 PUSH→SMS，EMAIL 无 fallback |

> PUSH 可叠加 C（jpushToken 空 → 同槽 fallback SMS），EMAIL/SMS 走 A。

**讨论结论**：________________

---

## 推进路线（email / message / push 联调步骤）

- [ ] **S1 会议拍板**：本文 4 项 + `README_编排同事对齐清单` 的 E3/E4，填写各节“讨论结论”。
- [ ] **S2 渠道实现真实化**：编排同事按结论改 `MockChannelGateway`（失败映射）、`MockStepResolver`（language/templateId）、`MockExecutionGuard`（NO_EMAIL/NO_TOKEN）。
- [ ] **S3 快照样例补齐**：`ContextSnapshot.sample.json` 含 email/jpushToken/language 的有值与空值两组，供联调。
- [ ] **S4 落 C1–C7 测试**：按 [`测试总览_Phase1.md`](../测试总览_Phase1.md) L2 表，把 7 个用例写成可执行测试（先用真实 Resolver/Guard + Mock 发送，再接真实供应商）。
- [ ] **S5 验证回报**：按 `ic-v1-validation.mdc` 跑 `collection-channel` + `collection-engine` 测试，回报结果。
- [ ] **S6 异常面补强**：超时/限流/熔断/空地址各跑一遍，确认引擎重试与 SKIP 行为符合预期。

---

> 本文结论定稿后，同步更新 `ic-v1-channel-contract.mdc`（若语义冻结值变化）与 `测试总览_Phase1.md` 的 C1–C7。
