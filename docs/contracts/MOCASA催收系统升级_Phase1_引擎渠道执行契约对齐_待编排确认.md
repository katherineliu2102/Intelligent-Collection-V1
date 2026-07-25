# 引擎↔渠道执行契约对齐（email / SMS / push 联调前置）

> **版本**: Phase 1  
> **日期**: 2026-06-11  
> **范围**: 仅覆盖菲律宾市场  
> **模块**: `collection-channel` / `collection-engine`  
> **关联文档**: [ContextSnapshot 契约对齐](./README_ContextSnapshot契约对齐.md)、[编排同事对齐清单](./README_编排同事对齐清单.md)

---

## 速览：4 项对齐 + 现状

| # | 对齐项 | 现状（Mock 初版） | ✅ 定稿（2026-06-11） |
|---|---|---|---|
| 1 | StepResult 回填 | 恒 `success=true / DELIVERED / retryable=false` | **3 种返回情形**：发送受理 / 网络超时(retryable) / 其他异常(不重试)；SMS/PUSH/EMAIL 同步完成口径一致 |
| 2 | StepCommand metadata | `language="en"` 硬编码；异步 `timeoutMinutes=60` | **采纳推荐**：language 取快照(空→en)、timeout 仅异步、templateId 按 stage×channel |
| 3 | 观察期 + 幂等 | 幂等 key = `plan:stepOrder:retryCount`（引擎/Mock 已一致） | **Phase 1：SMS/PUSH/EMAIL 均无观察期**（`dispatch` 成功即 `STEP_COMPLETED`）；观察期仅 Phase 2 消息渠道预留 |
| 4 | 空地址处理 | Guard 真实化待编排实现 | **采纳方案 A**：Guard 检测空地址 → `COMPLIANCE_BLOCKED` + 引擎写 timeline 后推进；PUSH 另叠加 C（空 jpushToken → 同槽 fallback SMS） |

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

> 引擎语义：`retryable=true` 且未超 `maxRetryCount(3)` → 退避重试；否则 FAILED 推进。`errorCode` 仅由**核心引擎**落 timeline 对账，引擎不解析其值。

**讨论结论（2026-06-11 定稿）**：渠道返回**收敛为 3 种情形**，编排同事按此实现 `ChannelGateway.dispatch` 回填：

| 情形 | `success` | `contactResult` | `retryable` | 引擎行为 |
|---|---|---|---|---|
| **① 发送受理** | true | DELIVERED | false | STEP_COMPLETED（SMS/PUSH/EMAIL 同步） |
| **② 网络超时**（含供应商 5xx/限流/熔断） | false | FAILED | **true** | 未超 maxRetry(3) → 退避重试 |
| **③ 其他异常**（地址无效/退订/未知错误） | false | FAILED | **false** | 直接 FAILED + 推进 |

> **Phase 1 消息类同步完成（2026-07-18 修订）**：SMS / PUSH / EMAIL 均 `dispatch` 成功即 `STEP_COMPLETED`，**不进** `STEP_WAITING`；DLR/打开等仅可 enrichment timeline，不用于完成步骤（与架构 §1.6.7、渠道编排 §3.5 一致）。
> `errorCode` 体系沿用上方建议表，仅落 timeline 对账。

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

**讨论结论（2026-06-11 定稿；2026-07-18 修订 SMS）**：

| 渠道 | 观察期 | 说明 |
|---|---|---|
| **PUSH** | **0（无）** | `dispatch` 返回即终态 |
| **EMAIL** | **0（无）** | 同上 |
| **SMS** | **0（无）** | 同上；引擎忽略 SMS 上的 `observationMinutes` |

- `observationMinutes`：Phase 1 消息三渠道恒 0；字段保留供 Phase 2（VIBER/WHATSAPP 等）。
- 幂等维度 `plan:stepOrder:retryCount`：**引擎与编排真实 `ChannelGateway` 须一致**（已确认）。

## 4. 空地址处理（已发现不一致，需拍板）

### 收敛口径
- EMAIL `email` 为空 → `ExecutionGuard` 判 `NO_EMAIL` → 引擎结果 `COMPLIANCE_BLOCKED`、写 timeline、推进。
- `StepResolver=null` 只表示策略性跳过，不用于表达空地址；该路径不写 timeline。
- **实现责任**：编排同事在真实 `ExecutionGuard` 补齐 `NO_EMAIL` / `NO_PHONE` / `NO_TOKEN`；引擎已负责写 timeline 与推进。

### 待对齐（三选一，推荐 A）

| 方案 | 做法 | 引擎现成支持 | 评价 |
|---|---|---|---|
| **A（已采纳）** | `ExecutionGuard` 检测空地址 → `block(NO_EMAIL/NO_TOKEN)` | ✅ 引擎已有 block→COMPLIANCE_BLOCKED、timeline、推进 | 语义最清晰，复用现有管线 |
| B | `StepResolver` 空地址抛异常 | ⚠ 走 FAILED 分支 | 把“无地址”当错误，语义偏差 |
| C | `ChannelGateway` 内 fallback（如 PUSH 无 token → 转 SMS） | ⚠ 对引擎透明 | 仅适合 PUSH→SMS，EMAIL 无 fallback |

> PUSH 可叠加 C（jpushToken 空 → 同槽 fallback SMS），EMAIL/SMS 走 A。

**讨论结论（2026-06-11 定稿，2026-07-24 收敛结果码）**：**采纳方案 A** —— `ExecutionGuard` 检测空地址 → `block(NO_EMAIL / NO_TOKEN)` → 引擎 `COMPLIANCE_BLOCKED`、写 timeline、推进。
分渠道细化：
- **EMAIL**：`basic.email` 空 → Guard `NO_EMAIL` → `COMPLIANCE_BLOCKED`。
- **SMS**：`basic.primaryPhone` 空 → Guard `NO_PHONE` → `COMPLIANCE_BLOCKED`。
- **PUSH**：**先叠加 C**（`device.jpushToken` 空 → `ChannelGateway` 同槽 fallback 改发 SMS，对引擎透明）；若连 SMS 号也空，则 Guard `NO_TOKEN` → `COMPLIANCE_BLOCKED`。
> 由编排同事在真实 `ExecutionGuard` 实现空地址检测；引擎侧 block→timeline→推进为唯一落数路径，channel/admin 不得重复写 timeline。

---

## 推进路线（email / message / push 联调步骤）

- [x] **S1 会议拍板**：本文 4 项已定稿（2026-06-11）；Webhook 签名格式 → 后续安排。
- [ ] **S2 渠道实现真实化**：编排同事按结论改 `MockChannelGateway`（3 情形回填 + PUSH fallback）、`MockStepResolver`（language/templateId）、`MockExecutionGuard`（NO_EMAIL/NO_PHONE/NO_TOKEN）。
- [ ] **S3 快照样例补齐**：使用 `ContextSnapshot.sample.json`（有值）与 `ContextSnapshot.empty-address.sample.json`（空地址）两组，供联调。
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
