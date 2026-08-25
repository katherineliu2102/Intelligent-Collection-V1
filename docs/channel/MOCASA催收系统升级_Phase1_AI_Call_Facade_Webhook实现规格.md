# MOCASA Phase 1 — Facade Webhook 实现规格

> **版本**: v0.3（**可开发**）  
> **日期**: 2026-08-24  
> **入站口径 SSOT**：[Facade 回调入站交接](./MOCASA催收系统升级_Phase1_AI_Call_Facade回调入站交接.md)  
> **本文角色**：把交接落成现码可执行的约定；只在「交接空白 / 现码做不到交接所写」时补丁，不另开一套映射。  
> **关联**：手册 §11（验签算法，补交接 §6 索取第 1 条）；[Facade 接入说明](./MOCASA催收系统升级_Phase1_AI_Call_Facade接入说明.md)

相对 v0.2：路径、结果枚举、`disposition`、`providerMsgId`、身份反查回退 **改跟交接**。引擎「改读 result」不再作为开工前提。

---

## 0. 与交接的对齐（实现时按此表）

| 项 | 交接 | 本文 |
|---|---|---|
| 新入口 | `POST /webhook/facade-callback` | **照做**。不改 `/webhook/channel-callback` |
| 账户级 URL | 控制台登记一次 | **照做**。不是下单字段；不要走网关现有 API Key 验签 |
| 身份 | `client_metadata` 优先；否则 `external_case_id`（案件）+ 批次号找唯一 `EXECUTING`；多条拒绝 | **照做** |
| 结果映射 | 见交接 §3（信箱 → `SENT_NO_RESPONSE`；真人 → `was_ai_connected && reason=NORMAL`） | **照做** |
| 事件 `disposition` | 留空或与 `result` 同值；**禁止**塞 Facade 原生词 | **照做**（原生词只进审计 JSON） |
| 事件 `providerMsgId` | Facade `batchId` | **照做**。`session_id` 只作投递幂等与审计 |
| 幂等 | 同一 `session_id` 只发一次 `CHANNEL_CALLBACK` | **照做** |
| 迟到回调 | 引擎吸收；审计必须留痕 | **照做** |
| 验签算法 | 交接当时未附正文 | **用手册 §11.3 补齐**（不是第二套口径） |
| 出站 `plan_id`/`step_id` | 交接假定 Adapter 已写入 | **现码缺口，必须补**（否则交接 §2 优先级 1 落空） |
| `external_case_id` | 交接：现为 `case_id` | **改成纯 `case_id`**（去掉 `mocasa-smoke-…-时间戳`） |

不在本期：CONNECT_AND_STOP、录音下载、超时后自动查 Facade、网关免认证洞、Facade `/retry`。

---

## 1. `planId` 是什么

不是入案 JSONL 字段。是引擎插入 `t_contact_plan` 后的自增主键；`stepId` 是 `t_contact_plan_step.id`（不是 `step_order`）。

`CHANNEL_CALLBACK` 的 `planId`/`stepId` 与现有 `/webhook/channel-callback` query **同一对库表 ID**。禁止把 JSONL `caseId` 填进 `planId`。

---

## 2. HTTP

```http
POST /webhook/facade-callback
Content-Type: application/json
X-Valubo-Signature: <hmac-sha256 小写 hex>
```

对外登记：`https://<pilot 外网入口>/webhook/facade-callback`。

| 项 | 约定 |
|---|---|
| 成功 | 2xx，`{"ok":true}` |
| 验签失败 | **401**，审计 `signatureValid=false`，不发事件 |
| 身份无法唯一确定 | **200** + 告警，不发事件（避免对方重试 1h；交接 §7.7 的「拒绝」指不猜、不发事件） |
| 密钥 | `channel.facade.callback-secret`（独立于 `collection.webhook.hmac-secret`） |
| local/test | `collection.webhook.signature-required=false` 时可跳过 Facade 验签；pilot/生产 fail-closed |

验签（手册 §11.3）：

```text
payload   = JSON.parse(UTF-8 body)
canonical = json.dumps(payload, separators=(",", ":"), sort_keys=True)  # 含 ensure_ascii 默认
expected  = lowercase_hex(HMAC_SHA256(callback_secret, utf8(canonical)))
```

**不要对原始 body 字节验签。** 联调应用 Valubo 实推报文校准 Unicode / 数字形态后再开生产验签。

---

## 3. 流水线

```text
POST
  → 验签失败：写审计（signatureValid=false）→ 401，不发事件
  → session.completed：
        若该 session_id 已有「验签通过且 plan_id+step_id 已解析」的审计 → 200，不再发
        否则解析身份 → 映射 ContactResult → 写审计 → CHANNEL_CALLBACK → 200
        身份无法唯一确定：写审计（plan/step 空）→ 200，不发事件（同 session 之后带上 metadata 的重试仍可发）
  → batch.completed：只记账 → 200
  → 其它 event：记账忽略 → 200
```

不在请求内下载 `media`。

---

## 4. 身份反查（交接 §2）

| 优先级 | 来源 | 行为 |
|---|---|---|
| 1 | `client_metadata.plan_id` + `step_id`（数字或数字字符串） | 直接用 |
| 2 | `client_metadata.case_id` 或 `external_case_id`（纯案件号） | 查该案活跃计划下 **唯一** `status=EXECUTING` 且 `channel=AI_CALL` 的步骤；命中 1 条则用；0 或多条 → 告警、不发事件 |
| 3 | 事件/载荷里的 Facade `batch_id` | 与 timeline 已存的 `providerMsgId`（出站 `delivered(batchId)`）交叉，仍须唯一。**本期不单独实现**：步骤表无 batch 字段；一案一批靠优先级 1（Resolver 已写 `plan_id`/`step_id`）或优先级 2。聚合批次后再补 timeline 查询 |

不要用手机号反查。不要新增 Facade 请求字段；`plan_id`/`step_id` 只放已有的 `client_metadata`。

---

## 5. 结果映射（交接 §3，入站完成）

引擎先读 `disposition`、没有才读 `result`；无法 `valueOf` 现码兜底 **`FAILED`**（§7）。因此：

- 事件 `result` **只能**是 `ContactResult` 枚举名。
- 事件 `disposition` **留空**（与 `result` 同值也可以）。`VOICEMAIL`、`NORMAL`、`MEDIA_NEGOTIATION_FAILED`、`promise_to_pay` 等 **只进审计 JSON**。

判定只看当次 `session.completed`：

| 条件（按行先匹配先赢） | `ContactResult` |
|---|---|
| `reason=VOICEMAIL` 且 `was_answered=true` | `SENT_NO_RESPONSE` |
| `reason=CALL_SCREENING` 且 `was_answered=true` | `SENT_NO_RESPONSE` |
| `was_ai_connected=true` **且** `reason=NORMAL` | `ANSWERED` |
| `reason=NO_ANSWER` 或 `final_failure_reason=NO_ANSWER` | `NO_ANSWER` |
| `BUSY` | `BUSY` |
| `DECLINE`（拒接） | `REJECTED` |
| `MEDIA_NEGOTIATION_FAILED` 等失败码 / 未知 | `FAILED` + 告警 |

信箱不计真人接通（交接口径）。`ContactResult.VOICEMAIL` 本迭代 **不用**（交接明确 `SENT_NO_RESPONSE`）。

`CHANNEL_CALLBACK` 字段：

| 字段 | 取值 |
|---|---|
| `planId` / `stepId` | §4，缺一不发事件 |
| `caseId` | 有则带 |
| `result` | 上表枚举名 |
| `disposition` | 空 |
| `providerMsgId` | Facade `batch_id`（无则 `external_batch_id`） |

---

## 6. 出站补丁（现码缺口，与入站同一迭代）

交接假定 `FacadeAiCallAdapter#clientMetadata` 已有三键。现码：Resolver 只写 `case_id`，Adapter 有则抄、没有就空。

`DefaultStepResolver` 对 `AI_CALL` 必须写：

```text
plan_id, step_id, case_id
borrower_name, overdue_amount, dpd, due_date   // 否则真 dispatch 口播/金额会失败
```

`external_case_id` = `String(caseId)`，缺则去 `+` 的号码（交接）。不要时间戳。

---

## 7. 引擎

本期 **不改**「先读 disposition」的顺序（交接用空 disposition 迁就）。

防御：`mapCallbackToResult` 非法枚举由 `ANSWERED` 改为 **`FAILED`**（落实交接「未知不要落接通」；单测 ④-D18 改期望）。不是开工阻断，与入站一起改掉。

CONNECT_AND_STOP / `DefaultAdvancementPolicy` **本期不做**。

---

## 8. 配置

```yaml
channel:
  facade:
    callback-secret: ${FACADE_CALLBACK_SECRET:}  # Nacos/环境注入；勿用空环境变量冲掉 Nacos
collection:
  webhook:
    signature-required: true   # local 现为 false
```

`ChannelProperties.Facade.callbackSecret` 新增。pilot 除现有 webhook HMAC 外，应具备 Facade callback-secret。

---

## 9. 验收（交接 §7）

1. 合法签名 + 真人（`was_ai_connected` + `NORMAL`）→ `ANSWERED`，步骤完成。  
2. 合法签名 + 信箱 → `SENT_NO_RESPONSE`，不得 `ANSWERED`。  
3. 未知 reason → `FAILED` + 告警，不落 `ANSWERED`。  
4. 坏签名 → 401，无事件，审计 `signature_valid=false`。  
5. 同一 `session_id` 重复 → 只有一次 `CHANNEL_CALLBACK`。  
6. 超时后迟到回调 → 引擎不改终态，审计有记录。  
7. metadata 缺且 `external_case_id` 能唯一命中 EXECUTING AI_CALL → 能反查；多条则不发事件并告警。

---

## 10. 实现切分

| 步 | 内容 |
|---|---|
| 1 | Resolver + Adapter 出站补丁 |
| 2 | `POST /webhook/facade-callback`（验签、审计、身份、映射、session 幂等） |
| 3 | 引擎非法兜底 `FAILED` |
| 4 | 单测覆盖 §9 |
| 后 | 公网 URL 交给 Valubo；CONNECT_AND_STOP；对账 Job |
