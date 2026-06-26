# 引擎↔渠道执行契约对齐（email / SMS / push 联调前置）

> **状态**：✅ 4 项已于 2026-06-11 与编排同事讨论定稿（见各节「讨论结论」）；Webhook 签名格式另行安排。
> **用途**：L2 联调（email/SMS/push）前，引擎与编排同事需书面确认的**执行运行时语义**。
> **与既有文档分工**：
> - 快照**数据**契约（字段/来源/SSOT）见 [`ContextSnapshot契约对齐_re.md`](./README_ContextSnapshot契约对齐.md)
> - SPI 实现/超时/生命周期 E1–E8 见 [`README_编排同事对齐清单.md`](./README_编排同事对齐清单.md)
> - 本文聚焦 **dispatch 回填 / 入参 metadata / 观察期与幂等 / 空地址** 四项，是上两者未覆盖的执行语义。
> **现状基线**：渠道侧 Mock 已实现 happy-path 初版值（见各节“现状”），**失败/边界值待真实化**。
> **维护**：引擎维护本契约；编排同事按此实现 `ChannelGateway` / `StepResolver` / `ExecutionGuard`。

---

## 速览：4 项对齐 + 现状

| # | 对齐项 | 现状（Mock 初版） | ✅ 定稿（2026-06-11） |
|---|---|---|---|
| 1 | StepResult 回填 | 恒 `success=true / DELIVERED / retryable=false` | **3 种返回情形**：发送受理 / 网络超时(retryable) / 其他异常(不重试)；**SMS 另分「发送受理」与「送达」两段**（送达需等供应商回调 DLR） |
| 2 | StepCommand metadata | `language="en"` 硬编码；异步 `timeoutMinutes=60` | **采纳推荐**：language 取快照(空→en)、timeout 仅异步、templateId 按 stage×channel |
| 3 | 观察期 + 幂等 | 幂等 key = `plan:stepOrder:retryCount`（引擎/Mock 已一致） | **PUSH / EMAIL 无观察期立即完成；仅 SMS 等供应商回传 DLR**；`observationMinutes` 由 PlanFactory 设定 |
| 4 | 空地址处理 | Resolver 地址空返回 null；Guard 恒放行（**NO_EMAIL 未实现**） | **采纳方案 A**：Guard 检测空地址 → block `NO_EMAIL`/`NO_TOKEN`→SKIPPED；PUSH 另叠加 C（空 jpushToken → 同槽 fallback SMS） |

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

**讨论结论（2026-06-11 定稿）**：渠道返回**收敛为 3 种情形**，编排同事按此实现 `ChannelGateway.dispatch` 回填：

| 情形 | `success` | `contactResult` | `retryable` | 引擎行为 |
|---|---|---|---|---|
| **① 发送受理** | true | DELIVERED（PUSH/EMAIL）/ 受理（SMS，见下） | false | STEP_COMPLETED |
| **② 网络超时**（含供应商 5xx/限流/熔断） | false | FAILED | **true** | 未超 maxRetry(3) → 退避重试 |
| **③ 其他异常**（地址无效/退订/未知错误） | false | FAILED | **false** | 直接 FAILED + 推进 |

> **SMS 两段语义（重要）**：SMS 的「发送受理」与「送达」是**两个结果**——`dispatch` 同步返回时只代表**供应商已受理**（success=true）；真实**送达（DLR）部分供应商需异步回调**。
> 因此 SMS 走「**受理→观察期等 DLR→结转**」（见 §3），PUSH/EMAIL 无此两段、`dispatch` 返回即终态。
> `errorCode` 体系沿用上方建议表（`*_TIMEOUT`/`*_INVALID_*`/`*_UNSUBSCRIBED` 等），仅落 timeline 对账。

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

**讨论结论（2026-06-11 定稿）**：**整表采纳推荐**。补充：`targetAddress` 取号口径与 token 字段以 **`device.jpushToken`** 为准（见文末「token 口径」）；`templateId` 的 stage×channel 取值表由编排同事在 `渠道模板清单与配置.md` 维护，引擎只透传不解析。

## 3. 观察期（STEP_WAITING）与幂等

### 现状
- 幂等 key 维度 = `planId:stepOrder:retryCount`，**引擎 `buildIdempotencyKey` 与 `MockStepResolver` 已一致**（重试用新 key，不自锁）。
- `observationMinutes` 由 `step` 携带（PlanFactory 设定），引擎只读；Mock 未设 → 走无观察期分支。

### 待对齐
- 各消息渠道默认观察期时长（SMS / PUSH / EMAIL 分别多少分钟，0 = 无观察期立即完成）。
- 确认 `observationMinutes` 归属：由 `PlanFactory` 按 stage 设定，引擎与 StepResolver 都不改。
- 编排真实 `ChannelGateway` 的幂等维度须与引擎一致（`plan:stepOrder:retryCount`）。

**讨论结论（2026-06-11 定稿）**：

| 渠道 | 观察期 | 说明 |
|---|---|---|
| **PUSH** | **0（无）** | `dispatch` 返回即终态，立即 STEP_COMPLETED |
| **EMAIL** | **0（无）** | 同上 |
| **SMS** | **有**（等供应商 DLR 回传） | 受理后进 `STEP_WAITING`，到期/收到 DLR 结转 |

- `observationMinutes` 归属确认：**由 `PlanFactory` 按 stage 设定**，引擎与 StepResolver 都不改。
- SMS 观察期**默认时长建议 10 分钟**（依据见下，最终以 LTH/供应商 SLA 为准；可按 stage 在模板配置覆盖）：
  - 菲律宾聚合短信 DLR（送达回执）典型延迟：正常 **数秒～30 秒**内回；P95 约 **1～3 分钟**；网络拥塞/跨网时可达 **5～15 分钟**，少数永不回。
  - 取 **10 min** 兼顾「绝大多数 DLR 已到」与「不长期占用 WAITING」；超时未收到 DLR 按「受理即视为完成」结转（不阻断管线推进）。
- 幂等维度 `plan:stepOrder:retryCount`：**引擎与编排真实 `ChannelGateway` 须一致**（已确认）。

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

**讨论结论（2026-06-11 定稿）**：**采纳方案 A** —— `ExecutionGuard` 检测空地址 → `block(NO_EMAIL / NO_TOKEN)` → 引擎 SKIPPED + 推进。
分渠道细化：
- **EMAIL**：`basic.email` 空 → Guard `NO_EMAIL` → SKIPPED。
- **SMS**：`basic.primaryPhone` 空 → Guard `NO_PHONE` → SKIPPED。
- **PUSH**：**先叠加 C**（`device.jpushToken` 空 → `ChannelGateway` 同槽 fallback 改发 SMS，对引擎透明）；若连 SMS 号也空，则 Guard `NO_TOKEN` → SKIPPED。
> 由编排同事在真实 `ExecutionGuard` 实现空地址检测；引擎侧 block→SKIPPED→推进已现成支持，无需改动。

---

## 推进路线（email / message / push 联调步骤）

- [x] **S1 会议拍板**：本文 4 项已定稿（2026-06-11）；Webhook 签名格式 → 后续安排。
- [ ] **S2 渠道实现真实化**：编排同事按结论改 `MockChannelGateway`（3 情形回填 + PUSH fallback）、`MockStepResolver`（language/templateId）、`MockExecutionGuard`（NO_EMAIL/NO_PHONE/NO_TOKEN）。
- [ ] **S3 快照样例补齐**：`ContextSnapshot.sample.json` 含 email/jpushToken/language 的有值与空值两组，供联调。
- [x] **S4 落 C1–C7 测试骨架**：引擎侧已落 `ChannelContractL2Test`（真实语义可配置替身 + mock 发送）；待编排同事真实化后对接即绿。
- [ ] **S5 验证回报**：按 `ic-v1-validation.mdc` 跑 `collection-channel` + `collection-engine` 测试，回报结果。
- [ ] **S6 异常面补强**：超时/异常/空地址各跑一遍，确认引擎重试与 SKIP 行为符合预期。

---

## token 口径（2026-06-11 定稿）

- PUSH/Message 经**内部 notification 系统**下发，PUSH token 口径**确认为 `device.jpushToken`（JPush Registration ID）**，**不使用 `fcmToken`**。
- 代码现状：`UserProfile.DeviceInfo` 暂同时保留 `jpushToken`（定稿口径）与 `fcmToken`（兼容 main 现有 `FcmPushAdapter`）。
  **收口动作**：编排同事将 `FcmPushAdapter`/取号逻辑切到 `jpushToken` 后，由主架构在 `collection-common` 移除 `fcmToken`（契约改动，统一发版）。

---

> 本文结论定稿后，同步更新 `ic-v1-channel-contract.mdc`（若语义冻结值变化）与 `MOCASA催收系统升级_Phase1_测试文档.md` 的 C1–C7。
