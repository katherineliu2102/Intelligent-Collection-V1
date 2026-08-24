# MOCASA Phase 1 — AI Call（Valubo Facade）接入说明

> **版本**: v1.3
> **日期**: 2026-08-21
> **v1.3**：对照 Facade 手册 2026-08-20。Callback 契约未改；`dial_policy` 旧字段会 422（Adapter 已合规）；`/retry` 补洞须带 `completed_reasons`。公网 Callback URL 仍待运维开通，见 §3.7。
> **v1.2**：`script.language` 不传（用租户 Taglish）；`product_type=Quick Loan`；Facade TLS 为自签名（实测）；`AI_CALL` 日上限 2。
> **v1.1**：D1 / D2 拍板——联调一案一批、上量同波次聚合；新增 `ContactResult.VOICEMAIL`。
> **范围**: 仅覆盖菲律宾市场
> **模块**: `collection-channel`（AI_CALL Adapter）+ `collection-admin`（Webhook）+ `collection-engine`（推进决策）
> **供应商**: Valubo Voice / Facade 批次外呼
> **关联文档**: [Facade 客户接入手册](../data-alignment/FACADE客户接入手册.md)、[Facade Webhook 实现规格（草案）](./MOCASA催收系统升级_Phase1_AI_Call_Facade_Webhook实现规格.md)、[渠道编排规格 V1.6](./MOCASA催收系统升级_Phase1_渠道编排规格.md)、[collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md)、[t_ai_collection 数仓最终对齐清单 v1.4](../data-alignment/MOCASA分流测试_t_ai_collection_数仓最终对齐清单.md)
>
> **本文取代** [LTH Voice 对接说明](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) 作为 `AI_CALL` 渠道 SSOT。LTH 仅保留人工轨例外外呼，不再承接机器轨 AI。

---

## 目录

- [1. 结论与边界](#1-结论与边界)
- [2. 实测基线（2026-08-13）与由此确定的口径](#2-实测基线2026-08-13与由此确定的口径)
- [3. 已确定流程](#3-已确定流程)
  - [3.1 端到端链路](#31-端到端链路)
  - [3.2 出站字段映射（案件 → Facade）](#32-出站字段映射案件--facade)
  - [3.3 回站映射（Facade Callback → 引擎）](#33-回站映射facade-callback--引擎)
  - [3.4 编排规则（Wave-1 / Wave-2 / 互斥 / 停催）](#34-编排规则wave-1--wave-2--互斥--停催)
  - [3.5 幂等、超时与对账](#35-幂等超时与对账)
  - [3.6 批次粒度](#36-批次粒度)
  - [3.7 公网 Callback URL 与运维开通](#37-公网-callback-url-与运维开通)
  - [3.8 手册 2026-08-20 修订影响](#38-手册-2026-08-20-修订影响)
- [4. 需要修改项](#4-需要修改项)
- [5. 需要进一步讨论项](#5-需要进一步讨论项)
- [6. 接入测试路线（L0–L3）](#6-接入测试路线l0l3)
- [7. 检查清单](#7-检查清单)
- [附录 A：回放 fixture 黄金集](#附录-a回放-fixture-黄金集)
- [附录 B：已确认作废的旧约定](#附录-b已确认作废的旧约定)

---

## 1. 结论与边界

机器轨语音渠道 = **`AI_CALL` 仅此一种**，供应商为 **Valubo Facade**。

**职责边界（不可越线）**

| 层 | 职责 | 明确不做 |
|---|---|---|
| `collection-engine` | Stage / Tone、plan 槽位、Wave-1/Wave-2、还款与冻结停催、推进决策 | 不感知 batch |
| `collection-channel`（AiCallAdapter） | 案件 → Facade batch/case 映射、拨号窗口传参、限流、撤单 | 不查业务库（哑管道原则） |
| `collection-admin` | Facade Callback 验签、幂等、转 `CHANNEL_CALLBACK` | 不做业务重拨判断 |
| Facade | 单次外呼执行、技术故障短间隔透明重试、终态回传 | 不做忙线/未接业务重拨 |

**关键原则**：编排大脑只有一个，在引擎。业务重拨用 plan 的 `*_VOICE_RETRY` step 表达，**不使用** Facade `/retry` 与 `include_completed` 作为日常策略（仅作运维补洞）。

**已拍板（2026-08-17）**

| 项 | 结论 |
|---|---|
| D1 批次粒度 | 联调 **一案一批**；上量 **同波次聚合**（≤500/批）。见 §3.6 |
| D2 信箱枚举 | 新增 `ContactResult.VOICEMAIL`；`CALL_SCREENING` 同落此值。见 §3.3 |

---

## 2. 实测基线（2026-08-13）与由此确定的口径

来源：`AI collection/AI call测试/eod_cases-2026.8.13`（生产线路，208 唯一号码，三轮拨打，话术 `collection` / `fil`，Facade 振铃并发 20）。

### 2.1 线路结果结构

| 结果 | R1 (208) | R2 (208) | R3 (200) |
|---|---:|---:|---:|
| `BUSY`（SIP 486） | 48.6% | 50.0% | 46.0% |
| `MEDIA_NEGOTIATION_FAILED`（SIP 406） | 28.4% | 25.0% | 31.5% |
| `NO_ANSWER` | 20.2% | 21.2% | 21.5% |
| `ANSWERED`（真人） | 1.9% | 1.9% | 1.0% |
| `VOICEMAIL` / `CALL_SCREENING` | 各 0.5% | `VOICEMAIL` 0.5% | 0 |

### 2.2 由实测确定 / 修正的五条口径

**(1) 真人接通判定不能用 `was_ai_connected`。**

Facade 手册 §10 称信箱场景 `was_ai_connected=false`，但生产回传相反：

| 样本 | `line_outcome.reason` | `was_answered` | `was_ai_connected` |
|---|---|---|---|
| `case-20260813-637337` | `VOICEMAIL` | true | **true** |
| `case-20260813-683295` | `CALL_SCREENING` | true | **true** |

机器已对信箱开口留言，故该标志位为 true。**判定改用 `reason` / `outcome_label`**：

```text
真人接通 ⇔ outcome_label == "ANSWERED"  或  line_outcome.reason == "NORMAL"
信箱/筛选 ⇔ reason ∈ { VOICEMAIL, CALL_SCREENING }   → 不算接通
```

**(2) `MEDIA_NEGOTIATION_FAILED`（SIP 406）是主力失败码，必须进映射表并允许补呼。**

占比 25–31%，与空号无关，属线路/媒体侧问题。不得按 `INVALID_NUMBER` 处理（不得停拨该号）。

**(3) Wave-2 是主路径，不是边缘分支。**

真人接通仅 1–2%，即 98% 以上案件上午结束后仍需下午补呼。R3 人工「剔除当日已接通 8 个号」正是引擎 `CONNECT_AND_STOP` 应自动完成的行为。

**(4) 协商结果稀有且标签不止 PTP。**

当日 10 通真人接通中仅 1 例落库协商（`case-20260813-684200`）：

| 字段 | 值 |
|---|---|
| `result_label` | `partial_payment_plan` |
| `promises` | `[{amount: 500, currency: PHP, promised_date: 2026-08-13, status: logged}]` |
| 其余真人接通 | `incomplete` / `refused_to_discuss` / `null` |

故 disposition **原样存 `result_label`**，不要预压成 `PTP / NO_PTP` 三态。

**(5) 生产 Callback 形状 = `attempts.json`，不是 `eod_merged.json`。**

`eod_merged.json` 是 EOD 按 caseId 把多轮字段收成**数组**的对账产物；`session.completed` 是**一通一条**。映射实现与 fixture 一律以 `attempts.json` 为准。

同理 `attempt_count`（当日样本恒为 1）是 Facade **内部技术重试**计数，与我们的业务轮次无关；业务轮次由 plan step 表达，`total_dial_count` 仅供对账。

### 2.3 其它可用于设计的观测

| 观测 | 结论 |
|---|---|
| `parties.caller_cli = "6310001"`（非 E.164） | 主叫由 Facade 账户侧配置，**我方不传** `caller_cli` / `caller_pool` |
| 未接通案 `dialed_at` → callback 为秒级；接通案为分钟级 | `timeoutMinutes=60` 足够，**前提是不做大 batch 排队** |
| 当日 `RECONCILE_*` / `SIP_PLACE_CALL_FAILED` = 0 | 平台侧稳定，未接通主因在线路 |
| 话术含 due date 口播（"past due since Agosto 10"） | `debt.due_date` 会被念出，取值需可信（见 §3.2） |
| 实测话术为 Taglish | 出站不传 `script.language`，用租户默认（见 §3.2） |

---

## 3. 已确定流程

### 3.1 端到端链路

```text
PlanFactory 生成 AI_CALL step（09:15 *_VOICE_PRIMARY / 14:30 *_VOICE_RETRY）
  → PLAN_STEP_DUE
  → PreFlightChecker（已还 / overdueAmount=0 / 冻结）
  → ExecutionGuard（触达窗 08:00–21:00 PHT、频次、CONNECT_AND_STOP）
  → StepResolver → StepCommand{ channelType=AI_CALL, targetAddress=E.164, metadata }
  → AiCallAdapter：POST /batches → /cases → /start
  → StepResult{ success=true, contactResult=DELIVERED }   // 仅表示已受理
  → plan STEP_EXECUTING + CALLBACK_TIMEOUT(60min)
  → Facade session.completed  →  POST /webhook/facade/voice（HMAC 验签 + session_id 幂等）
  → CHANNEL_CALLBACK{ planId, stepId, caseId, result, disposition, providerMsgId }
  → step STEP_COMPLETED → AdvancementPolicy
       真人接通 → 取消当日 *_VOICE_RETRY
       其余     → 保留补呼
```

### 3.2 出站字段映射（案件 → Facade）

**批次级（`POST /batches`）**

| Facade 字段 | 取值 | 说明 |
|---|---|---|
| `external_batch_id` | `mocasa-{yyyyMMdd}-{stage}-{slot}-{seq}` | 同账户唯一；`slot` = `w1` / `w2` |
| `script.domain` | 固定 `collection` | |
| `script.language` | **不传** | 用 Mocasa 租户默认 **Taglish**；勿传 `fil`/`en` 以免覆盖。文本渠道仍用 `en` |
| `dial_policy.timezone` | `Asia/Manila` | |
| `dial_policy.windows` | 与合规窗一致或略宽（如 `08:00–21:00`） | 防止 Facade 排队等窗导致 `CALLBACK_TIMEOUT` 误杀 |
| `dial_policy.weekdays` | 按运营配置 | |
| `line_id` / `caller_pool` | **不传** | 用账户默认线路与默认主叫（实测 `6310001`） |

**案件级（`POST /batches/{id}/cases`）**

| Facade 字段 | 必填 | 数据来源 | 规则 |
|---|---|---|---|
| `external_case_id` | 是 | `{caseId}-{planId}-{stepId}` | 同 batch 唯一，可反查 |
| `callee_e164` | 是 | `borrower.phone` | **必须归一化 `+63`**；失败则该案不入批并告警 |
| `caller_cli` | — | 不传 | 见批次级 |
| `earliest_dial_at` | 否 | ≈ step `trigger_time` | 引擎决定何时允许拨 |
| `business_context.borrower.name` | 是 | `borrower.name` | 空则不入批 |
| `business_context.debt.product_type` | 是 | 固定 **`Quick Loan`** | 与租户约定；不是手册通用枚举 `personal_loan` |
| `business_context.debt.currency` | 是 | 固定 `PHP` | |
| `business_context.debt.overdue_amount` | 是 | **`overdueAmount`** | 已到期未结清（含罚息），对客金额唯一来源；**禁用** `remainingAmount` |
| `business_context.debt.days_past_due` | 是 | `dpd` | |
| `business_context.debt.due_date` | 是 | 见下方「due_date 取值」 | |
| `business_context.debt.principal` | 否 | `overduePrincipal` | 三者齐全时之和须 = `overdue_amount` |
| `business_context.debt.interest` | 否 | `overdueInterest` | |
| `business_context.debt.penalty` | 否 | `overduePenaltyAmount` / `penaltyAmount` | |
| `business_context.installments` | 否 | **暂不传** | `paid/overdue/upcoming` 无现成来源，见 §5 |
| `business_context.prior_contacts` | 否 | **Phase 1 传 `[]`** | 见 §5 |
| `business_context.prior_promises` | 否 | **Phase 1 传 `[]`** | 见 §5 |
| `business_context.negotiation_options` | 否 | **不传** | Phase 1 无 Offer，AI 只推全额 |
| `business_context.brand_name` | 否 | 不传（默认 Mocasa） | 实测话术已自称 Mocasa |
| `business_context.client_metadata` | 否 | `{caseId, planId, stepId, stage, scriptSlot, wave}` | 原样回传，用于回写与对账 |

**金额一致性校验（Adapter 内前置）**

```text
若 principal / interest / penalty 三者齐传：
    principal + interest + penalty == overdue_amount   // 否则只传 overdue_amount
若 overdue_amount <= 0：
    该案不入批（引擎 PreFlight 也会拦，双保险）
```

新 Pub/Sub（主题 `intelligent-collection-cases-v1`）已提供 `overduePrincipal` / `overdueInterest` / `overduePenaltyAmount`，其和等于 `overdueAmount`（样例 5800 + 765.6 + 133.98 = 6699.58），故金额构成**可以传全**，AI 被追问时能拆解。

**due_date 取值（已定）**

不要求上游为 AI Call 新增字段。取值优先级：

```text
1) t_ai_collection.due_date（最早未结清期到期日）—— 首选
2) 回退推导：due_date = CURRENT_DATE('Asia/Manila') - dpd
```

理由：Facade 校验 `debt.due_date` 为**必填**（缺失 → `INVALID_BUSINESS_CONTEXT` 整案被拒），但语义只用于话术口播；`dpd` 已是最大逾期天数，回退推导与 `dpd` 天然自洽，误差不会造成金额错误。使用回退值时 Adapter 需在 timeline 标记 `due_date_derived=true`，便于事后核查口播准确性。

### 3.3 回站映射（Facade Callback → 引擎）

判定输入：**单通** `session.completed`（形状同 `attempts.json`）。

| Facade 结果 | `ContactResult` | `disposition` | 真人接通 | Wave-2 |
|---|---|---|---|---|
| `outcome_label=ANSWERED` 或 `reason=NORMAL` | `ANSWERED` | `ai_result.result_label`（可空） | 是 | **取消** |
| `reason=VOICEMAIL` | `VOICEMAIL` | `VOICEMAIL` | 否 | 保留 |
| `reason=CALL_SCREENING` | `VOICEMAIL` | `CALL_SCREENING` | 否 | 保留 |
| `final_failure_reason=BUSY` | `BUSY` | `BUSY` | 否 | 保留 |
| `NO_ANSWER` | `NO_ANSWER` | `NO_ANSWER` | 否 | 保留 |
| `MEDIA_NEGOTIATION_FAILED` | `FAILED` | `MEDIA_NEGOTIATION_FAILED` | 否 | 保留 |
| `TEMP_UNAVAILABLE` / `REQUEST_TIMEOUT` / `FORBIDDEN` / `DECLINE` / `FAILED` | `FAILED` | 原码 | 否 | 保留 |
| `INVALID_NUMBER` | `FAILED` | `INVALID_NUMBER` | 否 | 保留但**标记停拨该号** |
| `MISSING_CALLER_CLI` / `NO_ACTIVE_LINE` / `SESSION_START_FAILED` / `DIAL_ERROR` / `MEDIA_TIMEOUT` | `FAILED` | 原码 | 否 | 保留 + **告警**（配置/平台侧问题） |

**AI 结论落库（只落库，不驱动编排）**

| Facade | 落点 |
|---|---|
| `ai_result.result_label` | `CHANNEL_CALLBACK.disposition` + timeline |
| `ai_result.summary` | timeline |
| `ai_result.promises[]` | 联系记录 / timeline（Phase 1 **不产生** PTP 事件、不改 Tone、不改停催） |
| `media.recording_url` / `script_url` | timeline（`recording_status=ready` 时下载归档） |
| `session_id` | `providerMsgId`，兼幂等键 |

**`ContactResult` 语义提示**：`ANSWERED / VOICEMAIL / NO_ANSWER / BUSY / FAILED` 的 `priority=0`（终态，不参与 `canUpgradeFrom` 升级链），语音回调不会被 DELIVERED→READ 之类升级逻辑覆盖。`VOICEMAIL` 已加入 `collection-common` 枚举；`CALL_SCREENING` **不**单独占枚举值，同落 `VOICEMAIL`，靠 `disposition` 区分。

`CONNECT_AND_STOP` 判定：`contactResult == ANSWERED`。`VOICEMAIL` 与未接通一样保留 Wave-2。

### 3.4 编排规则（Wave-1 / Wave-2 / 互斥 / 停催）

沿用 [渠道编排规格 V1.6](./MOCASA催收系统升级_Phase1_渠道编排规格.md)，AI Call 侧不新增策略：

| 规则 | 定义 |
|---|---|
| 槽位 | S1–S4a：09:15 Wave-1 + 14:30 Wave-2；S4b（D+61~90）：仅 Wave-1 |
| 上限 | ≤2 呼/案/日（S4b ≤1） |
| 触达窗 | 08:00–21:00 PHT |
| `CONNECT_AND_STOP` | **仅真人接通**触发，取消当日 Wave-2；信箱/筛选**不触发** |
| 业务重拨 | 由 plan `*_VOICE_RETRY` step 承载；**不调** Facade `/retry` |
| 还款 / 结清 | `PreFlight` + 事件取消后续 step；Adapter 调 Facade `pause`/`cancel`（在途通不强制挂断，接受最多多打完一通） |
| 冻结 / Override（投诉、争议、需人工） | Guard 阻断 + cancel 当日 AI step；Adapter 尽力撤单，无 API 时记录限制并走运营 SOP |
| D+91 停催 | `CASE_CEASED` → cancel 全部 pending，零主动触达 |
| Tone | Phase 1 固定 `STANDARD`；Facade 无 Tone 字段，FIRM 接线后经 `script` / `business_context` 表达（见 §5） |

### 3.5 幂等、超时与对账

| 项 | 约定 |
|---|---|
| 步骤幂等 | 引擎 `IdempotencyService`，key = `planId:stepOrder:retryCount`（不变） |
| 渠道幂等 | Redis `idempotency:channel:{idempotencyKey}`，TTL 24h |
| Callback 幂等 | **`session_id`** 覆盖写；重复投递不得重复推进或重复外呼 |
| 验签 | `X-Valubo-Signature` = HMAC-SHA256(secret, canonical JSON)；canonical = `json.dumps(payload, separators=(",",":"), sort_keys=True)`；**不得对原始 body 字节验签**；失败返回 `401` |
| 应答 | 处理成功返回 2xx；重活异步化（Facade 退避重试约 30s/60s/120s…，上限约 1h，约 5 次） |
| 超时 | `CALLBACK_TIMEOUT` 60min → step FAILED 但仍 `STEP_COMPLETED` 推进；随后由 Job 查 `GET /sessions/{id}` 补写 timeline |
| 无会话失败 | Facade 不推 `session.completed`（会话创建前失败）→ 依赖 `CALLBACK_TIMEOUT` + `GET /batches/{id}/cases` 兜底 |
| 对账 | 日终用 `GET /batches/{id}` counts 与我方 step 数比对；`batch.completed` 可能重复，按 `batch_id` 幂等 |

### 3.6 批次粒度

**已拍板（D1）**：联调一案一批，上量同波次聚合。不采用缓冲微批。

| 阶段 | 形态 | 规则 |
|---|---|---|
| L1–L3 联调 / 分流前 | **一案一批** | 每个 AI step 独立 `create → upload → start`；`external_case_id` = `{caseId}-{planId}-{stepId}`；cancel / 回调反查与引擎 step 一一对应 |
| 正式量 | **同波次聚合** | 同一 `trigger_time`（Wave-1 或 Wave-2）多案合成 1～N 个 batch；每批 ≤500；再按 Facade 并发上限分片；`client_metadata` 仍带 `planId/stepId/caseId` |
| 明确不做 | 缓冲微批（30–60s 攒批） | 多一层状态机，收益不如先把回调语义做对 |

业务重拨继续用 plan 的 `*_VOICE_RETRY`，**不**用 Facade `/retry`。上量切聚合前，须先通过 L3（Wave-2 取消/保留）验收。

### 3.7 公网 Callback URL 与运维开通

Facade **不会**在建批请求里带回调地址。账户开通时由我方把 **HTTPS URL + Callback secret** 交给 Valubo，对方在推送侧配置。建议测试地址：

```text
https://<test-domain>/webhook/facade/voice
```

**现在缺的（给 Facade 之前必须齐）**

| 项 | 现状 | 谁补 |
|---|---|---|
| `POST /webhook/facade/voice` | **未实现**。现有 `POST /webhook/channel-callback` 是 query 参数 + `X-Callback-Signature`，不能接 Facade JSON | 研发 |
| Callback secret | Nacos `channel.facade` **没有** `callback-secret` | 研发加配置 + 运维注入 Secret |
| `<test-domain>` | 未拍板；本机 `localhost:8888` 对方打不进来 | 运维 |
| 公网 HTTPS 证书 | 对方出站会校验证书；不能用自签给回调入口 | 运维 |
| 路由 | Ingress / LB 把该 path 转到 `collection-admin` | 运维 |
| 推送开通 | 账户级配置，不是 API 字段 | 把 URL+secret 交给 Valubo |
| 来源 IP 白名单 | 手册未给 egress IP | 向 Valubo 要；可选 |

**签名格式（手册 §11.3，8/20 未改）**

```text
1. UTF-8 解析 JSON 为对象（不要用原始 body 字节）
2. canonical = json.dumps(payload, separators=(",", ":"), sort_keys=True)
3. X-Valubo-Signature = lowercase_hex( HMAC_SHA256(callback_secret, canonical_utf8) )
4. 常量时间比较；失败 401；成功任意 2xx
```

联调风险：Python `json.dumps` 默认 `ensure_ascii=True`（中文变 `\uXXXX`）。Java 默认不转义。必须以 Valubo **实推报文**对拍，不要只按文档写死。

`client_metadata` 会原样回传；Adapter 已写入 `case_id` / `plan_id` / `step_id`，Webhook 用它们发 `CHANNEL_CALLBACK`。`batch.completed` 只对账、按 `batch_id` 幂等，不推进 step。无会话失败不推 `session.completed`，继续靠超时 + `GET /batches/{id}/cases`。

**建议顺序**：先实现接收端 → 运维出 HTTPS 与 secret → 交给 Valubo 开通 → 小批次对拍验签。URL 先给出去、接口未上线，对方会记 `callback_status=delivered` 但一直 4xx/超时并退避重试（L1 已出现过）。

### 3.8 手册 2026-08-20 修订影响

对照《FACADE客户接入手册_2026-08-20修订说明》：URL / Bearer / 建批上传启动 / Callback 字段结构 **没有换**。只收紧两处校验。

| 修订 | 对我们 | 要改什么 |
|---|---|---|
| `dial_policy` 再传 `ring_timeout_sec` / `retry` / `predictive` / `terminal_sip_codes` / 顶层 `prepare_mode` → **HTTP 422** | `FacadeAiCallAdapter.buildBatchBody` 已只传 `timezone` / `windows` / `weekdays` | **出站代码不用改**。禁止以后加这些字段；单测保持请求体无 `retry` 键 |
| `include_completed=true` 必须带 `completed_reasons`，首版只允许 `VOICEMAIL`、`CALL_SCREENING`；传 `NORMAL` → 422 | 日常业务重拨本来就不走 Facade `/retry` | 运维补洞脚本按新契约改。不要用 `/retry` 批量重打真人 |
| 手册仍写：信箱 `was_ai_connected=false`；真人可用 `was_ai_connected` | 8/13 生产回传信箱 `was_ai_connected=true` | **不要改回** `was_ai_connected`。继续 §3.3：`reason=NORMAL` 或 `outcome_label=ANSWERED` |
| 信箱 / 筛选案件状态是 `completed` 不是 `failed` | 与 `ContactResult.VOICEMAIL`、Wave-2 保留一致 | Webhook **禁止**把任意 `completed` 映射成 `ANSWERED`；必须看 `reason` |

---

## 4. 需要修改项

按「不改就跑不通 / 跑通但语义错」排序。

### 4.1 P0 — 不改则语义错误

| # | 位置 | 现状 | 需要改成 |
|---|---|---|---|
| M1 | `DefaultAdvancementPolicy` | **完全不读 `contactResult`**，只按步序：非末步恒 `ADVANCE_NEXT` | 按 §3.3 分支：真人接通 → 取消当日 `*_VOICE_RETRY`；信箱/未接 → 保留。当前实现下「接通仍会补呼」，与规格 §7.3 相悖 |
| M2 | `ContactResult` 枚举 | **已加** `VOICEMAIL(0)`（`collection-common`） | Webhook 必须回填合法枚举名 `VOICEMAIL`（信箱/筛选同此值）；**禁止**把信箱映射为 `ANSWERED`。`CALL_SCREENING` 走 `disposition`。`AdvancementPolicy` 仅对 `ANSWERED` 取消 Wave-2 |
| M3 | 真人接通判定 | 早期设计写 `was_ai_connected == true` | 改为 `outcome_label==ANSWERED \|\| reason==NORMAL`（实测依据见 §2.2(1)） |
| M4 | `WebhookController` | 仅 `POST /webhook/channel-callback`（query + `X-Callback-Signature`）。**没有** Facade JSON / `X-Valubo-Signature` | **仍待做**：新增 `POST /webhook/facade/voice`：canonical JSON HMAC（失败 401）、`session_id` 幂等、解析 `line_outcome`/`ai_result` → `CHANNEL_CALLBACK`。原骨架接口保留，两套签名不要混 |
| M5 | AI_CALL Adapter | **出站已落地** `FacadeAiCallAdapter`（create→cases→start） | 保持 `dial_policy` 仅窗口字段（8/20 后多传会 422）。Webhook / 引擎推进仍见 M1–M4 |

### 4.2 P1 — 影响正确性但可测试期规避

| # | 位置 | 需要改 |
|---|---|---|
| M6 | 失败码表 | `MEDIA_NEGOTIATION_FAILED`（406）、`TEMP_UNAVAILABLE`（480）、`REQUEST_TIMEOUT`（408）纳入映射并**允许补呼**；仅 `INVALID_NUMBER` 停拨该号 |
| M7 | 号码归一化 | 新 Pub/Sub 样例 `phone = "9898989898"` 非 E.164。`+63` 归一必须在入库或 Adapter 前置完成，否则整批 `INVALID_E164` |
| M8 | 语言配置 | **不传** `script.language` | 租户默认 Taglish；文本渠道 `en` 不变 |
| M8b | TLS | Facade `https://34.158.34.184` 为 **Valubo 自签名**（CN=`valubo-voice-test`） | Java 默认校验证书会失败；Adapter 须导入该证或测试环境单独 TrustStore，**生产禁止**全局关闭校验 |
| M9 | 拨号窗配置 | `channel.facade.voice.dial-policy.*` 与合规窗对齐，避免 Facade 排队 → 超时误杀 |
| M10 | timeline 字段 | 落 `session_id`、`outcome_label`、`result_label`、`recording_url`、`script_url`、`due_date_derived` |

### 4.3 P2 — 文档与配置债务

| # | 位置 | 需要改 |
|---|---|---|
| M11 | [LTH Voice 对接说明](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) | 标注被本文取代；`disposition` 示例表（ANSWERED/NO_ANSWER/BUSY）已不足 |
| M12 | [渠道模板清单与配置](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) §6 | `*_VOICE_*` 供应商列由 LTH 改 Facade；Voice 模板实为 `script.domain` + `business_context`，无 template_id |
| M13 | [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) §12 开放依赖 #6 | 「disposition 枚举与 LTH 对齐」改为「与 Facade `line_outcome.reason` + `ai_result.result_label` 对齐」 |
| M14 | [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) | 语音用例补信箱、406、Wave-2 保留/取消 |
| M15 | Nacos | 新增 `channel.facade.*`：`base-url`、`api-key`（凭证引用，不明文）、`callback-secret`、`concurrency`、`batch-size`(≤500)、`timeout-minutes` |

### 4.4 与 ingestion 的关系（2026-08-18 已映射文本渠道所需字段）

Topic `intelligent-collection-cases-v1`：嵌套 `borrower`/`device` 已展开；`overdueAmount` → 快照 `totalOutstanding`；`upcomingAmount` 原样入快照；`device.pushToken` → `jpushToken`；`borrower.phone` 做 `+63` 归一；`dueDate` 按消息写入（将由数仓补齐）。

| 项 | 新 Pub/Sub | 现行 ingestion | 对 AI Call 的影响 |
|---|---|---|---|
| 金额 | `overdueAmount` + 拆分 + `upcomingAmount` | 总额写入 `totalOutstanding`；`upcomingAmount` 已落快照；拆分尚未进 `CaseContext` | 总额可用；本金/息/罚息拆分仍待落快照 |
| `dueDate` | 消息将补（最早未结清期） | 已映射；缺则 poison（金融必填） | 首选消息 `dueDate`；缺失按 §3.2 推导仅适用于未走 ingestion 的测试台 |
| `occurredAt` | `"yyyy-MM-dd HH:mm:ss"` 无时区 | 已按 PHT 容错 | 可接 |
| `caseVersion` | hash 字符串 | 仍按 **Long** 解析 | hash 会 poison / 乱序保护失效 → **生产消费器未切 hash** |
| `repaymentEvent` | `isFullCleared`；瘦消息 | 已认 `isFullCleared`，不再认 `STATUS==4` | 结清停催可用 |

**仍开放**：`caseVersion` 类型（hash vs 单调 Long）需单独窗口；本金/息/罚息拆分可先落快照给 Facade，不进 SMS/Push/Email 模板。

---

## 5. 需要进一步讨论项

D1（批次粒度）、D2（`ContactResult.VOICEMAIL`）已于 2026-08-17 拍板，分别见 §3.6、§3.3。

| # | 议题 | 选项 | 倾向 | 影响面 |
|---|---|---|---|---|
| **D3** | `installments`（三期） | A 不传（只报总额）· B 数仓补 `paid/overdue/upcoming` · C Adapter 从 `bills[]` 推 | **Phase 1 A**；`bills[]` 在 v1.4 已有但新订阅未带，待两侧收敛后再做 C | AI 话术质量，非阻断 |
| **D4** | `prior_contacts` / `prior_promises` | A 传 `[]` · B 从 timeline 映射最近 1–3 条 | **Phase 1 A，Phase 2 B**：需先定义 timeline→Facade `result` 枚举映射 | 复呼话术质量 |
| **D5** | PTP 处理 | A 只落库 · B 承诺窗内跳过 AI step · C 完整 PTP 策略 | **A**：与「Phase 1 不做 PTP」一致；实测协商仅 1/208，B 的收益尚无数据支撑。多存字段为 Phase 2 冷启动 | 规格一致性、上线风险 |
| **D6** | FIRM 接线后如何表达 Tone | A `script.domain` 分化 · B `business_context.borrower.background/communication_style` 注入 · C 供应商侧多话术版本 | 待与供应商确认 Facade 是否支持话术变体选择 | FIRM A/B 实验能否做 |
| **D7** | 媒体归档 | 录音/脚本是否落我方存储、保留期、访问审计 | 建议只存 URL + 按需拉取；实测 URL 为 IP 直连 HTTPS（`https://34.158.34.184/...`），需确认证书与长期可用性 | 合规、质检 |
| **D8** | 撤单能力 | Facade `pause`/`cancel` 对「已入队未拨」的实际生效范围与延迟 | 需供应商书面确认；决定 Override 是否必须叠加运营 SOP | 人机互斥有效性 |
| **D9** | 并发与线路质量 | 406 占比 25–31%、BUSY ≈50%，是否要求供应商优化线路/换号段 | 建议作为商务/质量议题单独跟进，不阻塞接入 | 回收效果 |

---

## 6. 接入测试路线（L0–L3）

分层目的：先证「映射对」，再证「链路通」，最后证「编排对」。不一次性接入生产编排。

### L0 — 回放黄金集（不拨号）

- 用 §附录 A 的 6 类 fixture 打 Adapter/Webhook 单测
- 验收：映射表逐条命中；信箱**不**取消 Wave-2；406 归 `FAILED` 且可补呼；`session_id` 重复投递不产生二次推进
- 成本最低，优先完成，可拦住「信箱误判接通」这类致命错误

### L1 — 渠道层冒烟（不经引擎，已落地）

本地 `local` profile：

```text
# 只看将提交的 JSON，不拨号
curl -X POST "http://localhost:8888/mock/send-ai-call?dryRun=true"

# 真拨测试号 +639451373897（会振铃）。先 export FACADE_API_KEY
curl -X POST "http://localhost:8888/mock/send-ai-call?poll=true"
```

配置：`channel.facade.base-url` / `FACADE_API_KEY` / `insecure-tls=true`（自签名）。Adapter 一案一批、不传 `script.language`、`product_type=Quick Loan`。

- 验收：dryRun 预览无 `language`；真拨返回 `DELIVERED` + `batch_id`；`poll=true` 能查到 queued/dialing/终态
- 此层不验证 Wave-2 / Webhook / 引擎推进

### L2 — 单 step 进引擎

- `FacadeAiCallAdapter` 接入 `ChannelGateway`；`POST /webhook/facade/voice` 上线
- 只跑**一个** `AI_CALL` step（暂不排 Wave-2）
- 验收：`dispatch` → `STEP_EXECUTING`；callback → `STEP_COMPLETED`；60min 超时不误杀；timeline 字段齐

### L3 — Wave-1 / Wave-2 编排

- 小日表：09:15 Wave-1 + 14:30 Wave-2
- 按实测预期：绝大多数案下午 **应仍在**；注入/回放一例 `NORMAL` → 下午 step **必须被 cancel**；信箱案下午 **必须还在**
- 另测：还款事件 → 后续 step 取消 + Facade 撤单；冻结 → Guard 阻断
- 验收：无「接通后仍补呼」、无「信箱被判接通」、无调用 Facade `/retry`

### 明确不做

- 不把新订阅直接接入现网 `PubSubCaseConsumer` 后对全量 S1–S4 打 Facade（字段契约与接通口径均未冻结）
- 不用 `eod_merged.json` 作为映射契约
- 不用 Facade `/retry` 或 `include_completed` 替代 Wave-2

---

## 7. 检查清单

**供应商侧（接入前置）**

- [ ] 已获取测试与生产 API Key（服务端保管，凭证引用不明文）
- [ ] 已向供应商提供公网 HTTPS Callback URL + secret，`session.completed` / `batch.completed` 已开通
- [ ] 已确认账户默认线路与默认主叫可用（我方不传 `line_id` / `caller_cli`）
- [ ] 已确认 `pause` / `cancel` 对已入队未拨案件的生效范围（D8）
- [ ] 已确认录音/脚本 URL 的证书与保留期（D7）

**我方实现**

- [ ] `FacadeAiCallAdapter`（M5）：批次生命周期 + E.164 归一 + 金额加和校验 + `due_date` 兜底 + `client_metadata`
- [ ] `POST /webhook/facade/voice`（M4）：canonical JSON HMAC 验签、401、`session_id` 幂等、2xx 快返
- [ ] 真人接通判定改 `reason`/`outcome_label`（M3）
- [ ] `AdvancementPolicy` 实现 `CONNECT_AND_STOP`（M1）
- [ ] 信箱/筛选回填 `ContactResult.VOICEMAIL`（M2，已拍板）；**禁止**落 `ANSWERED`
- [ ] 406/480/408 纳入失败码表且允许补呼（M6）
- [ ] Nacos `channel.facade.*` 配置齐（M15）
- [ ] timeline 落 `session_id` / `outcome_label` / `result_label` / 媒体 URL / `due_date_derived`（M10）

**测试**

- [ ] L0 fixture 回放通过
- [ ] L1 20–30 案一案一批真拨通过
- [ ] L2 单 step 链路通过
- [ ] L3 Wave-2 取消/保留行为通过
- [ ] 报表口径分离：线路接通 vs **真人接通**（分子仅 `ANSWERED_HUMAN`）

---

## 附录 A：回放 fixture 黄金集

均取自 `AI collection/AI call测试/eod_cases-2026.8.13/eod_cases/<caseId>/attempts.json`（单通形状）。

| # | 场景 | 样本 caseId | 关键字段 | 期望映射 |
|---|---|---|---|---|
| F1 | 真人接通 + 部分还款计划 | `case-20260813-684200`（r2） | `reason=NORMAL`、`result_label=partial_payment_plan`、`promises=[₱500]` | `ANSWERED`，**取消 Wave-2**，承诺落库 |
| F2 | 同案首轮未接 | `case-20260813-684200`（r1） | `final_failure_reason=NO_ANSWER` | `NO_ANSWER`，保留 Wave-2 |
| F3 | 语音信箱 | `case-20260813-637337` | `reason=VOICEMAIL`、`was_ai_connected=true` | `ContactResult.VOICEMAIL`，**保留 Wave-2** |
| F4 | 来电筛选助理 | `case-20260813-683295` | `reason=CALL_SCREENING`、`was_ai_connected=true` | `ContactResult.VOICEMAIL` + `disposition=CALL_SCREENING`，保留 Wave-2 |
| F5 | 媒体协商失败 | `case-20260813-684493` | `reason=MEDIA_NEGOTIATION_FAILED`、`sip_code=406` | `FAILED`，保留 Wave-2，**不停拨** |
| F6 | 忙线 | `case-20260813-683295`（r2） | `outcome_label=BUSY` | `BUSY`，保留 Wave-2 |

补充断言：

- 同一 `session_id` 重复投递 → 幂等，不重复推进
- `media` 为空对象时不得抛异常（F5 场景 `media: {}`）
- `attempt_count` 不参与业务轮次判断

---

## 附录 B：已确认作废的旧约定

| 旧约定 | 新约定 | 依据 |
|---|---|---|
| 机器轨 AI 走 LTH SIP | 走 Valubo Facade | 本文 §1 |
| 真人接通 = `was_ai_connected == true` | `outcome_label==ANSWERED \|\| reason==NORMAL` | 实测 §2.2(1) |
| 信箱 = 接通（计入分子） | 信箱/筛选计线路接通，**不计**真人接通；落 `ContactResult.VOICEMAIL`，不取消补呼 | §2.2(1)、§3.3、§3.4 |
| 联调即大 batch 聚合 | 联调一案一批，上量再同波次聚合（≤500） | §3.6 |
| 业务重拨可用供应商 `/retry` | 由 plan `*_VOICE_RETRY` step 表达 | §1、§3.4 |
| disposition 仅 `ANSWERED/NO_ANSWER/BUSY` | 原样存 `ai_result.result_label`（含 `partial_payment_plan` / `refused_to_discuss` / `incomplete`） | §2.2(4) |
| AI 语言随快照 `borrower.language`（`en`） | **不传** `script.language`，用租户 Taglish；文本渠道保持 `en` | §3.2 |
| `remainingAmount` 可对客 | 对客金额唯一来源 `overdueAmount` | 数仓对齐 v1.4 |
| 回调形状参考 `eod_merged.json` | 以单通 `attempts.json` / `session.completed` 为准 | §2.2(5) |

---

> MOCASA Phase 1 — AI Call (Valubo Facade) Integration Spec v1.0 — 2026-08-17
