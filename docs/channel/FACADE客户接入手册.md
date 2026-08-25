# Facade 批次外呼客户接入手册

> **文档状态**：与现行生产 API 对齐（内部维护；大变动后再交付客户）  
> **创建日期**：2026-06-20  
> **最近修订**：2026-08-20（`dial_policy` 再传旧字段 422；`include_completed` 必须带 `VOICEMAIL`/`CALL_SCREENING`）  
> **Base URL**：`https://{host}/api/v1/facade`  
> **适用对象**：通过我方 Facade 模块发起批次外呼的客户系统（催收管理系统等）

---

## 1. 概述

Facade API 用于客户以「批次」为单位提交外呼任务。接入前由**我方提供** API Key、线路 ID（如需固定线路）、账户默认主叫等；贵方提供 Callback 接收地址与签名密钥（双方约定后由我方开通推送）。客户侧流程：

1. 创建 batch。
2. 上传该 batch 下的案件。
3. 启动 batch。
4. 通过 **Callback（主路径）** 或查询接口获取通话结果。

**职责边界（重要）**：

| 层 | 职责 |
|---|---|
| **客户系统** | 业务重拨（忙线 / 未接 / 拒接 / 信箱后再打的频次与合规窗口）、案件联系记录 |
| **Facade** | 单次外呼执行；仅对**技术故障**做短间隔透明重试；终态结果经 Callback 推给客户 |

### 1.1 核心流程

```text
POST /batches
  → 创建批次，绑定话术、拨号策略、可选线路、可选主叫号码池

POST /batches/{batch_id}/cases
  → 上传一个或多个案件，绑定被叫号码和业务上下文

POST /batches/{batch_id}/start
  → 开始拨打，Facade 按拨号策略和并发上限调度

GET /batches/{batch_id}
GET /sessions/{session_id}
  → 查询批次和单通信息

Webhook session.completed / batch.completed
  → 结果直接 POST 到客户 Callback URL（见 §11）
```

### 1.2 名词说明

| 名词             | 说明                                       |
| ---------------- | ------------------------------------------ |
| batch            | 一批待拨案件                               |
| case             | batch 下的一条案件                         |
| session          | 一次实际通话                               |
| line_id          | 线路 ID（由我方提供；创建批次时可选传入） |
| caller_cli       | 外显主叫号码                               |
| caller_pool      | 批次级主叫号码池                           |
| business_context | 案件业务上下文，用于 AI 对话策略和结果判断 |

---

## 2. 鉴权

### 2.1 REST 鉴权

所有请求均需携带 API Key：

```http
Authorization: Bearer sk_live_xxx
Content-Type: application/json
```

API Key **由我方提供**。请妥善保管，仅用于服务端调用。

### 2.2 统一响应格式

成功：

```json
{
  "success": true,
  "data": {}
}
```

失败：

```json
{
  "success": false,
  "error": {
    "code": "INVALID_BATCH_STATUS",
    "message": "Batch has already started"
  }
}
```

---

## 3. 接入前准备

### 3.1 API Key

**由我方提供。** 须保存在服务端，不得写入前端页面或移动端安装包。

不提供 / 无效时：所有 Facade API 返回鉴权失败（如 `INVALID_API_KEY`）。

### 3.2 line_id

创建批次时可传的线路 ID，**由我方提供**（贵方需要固定某条线路时）。客户无需自行枚举线路。

| 情况 | 行为 |
| ---- | ---- |
| 传入我方提供的有效 `line_id` | 该批次使用指定线路 |
| **不传** `line_id` | 使用我方为贵方账户配置的**默认线路** |
| 传入无效 / 未启用的 ID | 创建批次失败（`INVALID_LINE_ID`） |
| 不传且账户无可用默认线路 | 开始拨打失败（`NO_ACTIVE_LINE`） |

### 3.3 主叫号码（外显 CLI）

拨号时主叫号码按以下优先级选取：

```text
案件 caller_cli
  > 批次 caller_pool
  > 我方为贵方账户配置的默认主叫
```

| 情况 | 行为 |
| ---- | ---- |
| 案件传了 `caller_cli` | 使用该号码 |
| 案件未传，批次传了 `caller_pool` | 从池中选取 |
| 案件与批次均未传 | 使用我方提供的账户默认主叫 |
| 以上均无可用主叫 | 该案件拨号失败，`MISSING_CALLER_CLI` |

主叫须为 E.164（见 §13）。可用号码段**由我方提供**；请勿使用未经我方确认的号码。

### 3.4 Callback（结果直达客户）

通话 / 批次终态由我方 **POST** 到贵方接收地址。接入前请向我方提供：

| 项 | 说明 | 不提供时 |
| -- | ---- | -------- |
| Callback URL | 公网 **HTTPS**，接收 POST JSON | 无法推送结果；请用查询接口兜底，或联系我方开通 |
| Callback secret | 双方约定的 HMAC 密钥（验签用，见 §11.3） | 无法完成验签联调 |

**事件订阅**：我方已默认开通 `session.completed` 与 `batch.completed` 两类推送，贵方按 §11 接收即可，一般无需再申请。若只需其中一类或后续新增事件类型，请与我方确认。

开通后由我方完成推送侧配置。客户须按 **§11** 实现验签、幂等与 `2xx` 应答。

---

## 4. 创建批次

### 4.1 接口

```http
POST /api/v1/facade/batches
```

### 4.2 请求示例

```json
{
  "external_batch_id": "20260620-PH-001",
  "line_id": "550e8400-e29b-41d4-a716-446655440000",
  "script": {
    "domain": "collection",
    "language": "zh"
  },
  "caller_pool": ["+639912345678", "+639998887777"],
  "dial_policy": {
    "timezone": "Asia/Manila",
    "windows": [
      { "start_time": "09:00", "end_time": "12:00" },
      { "start_time": "14:00", "end_time": "20:00" }
    ],
    "weekdays": [1, 2, 3, 4, 5]
  }
}
```

成功时 HTTP **201**，响应见 §4.5。

### 4.3 字段说明

| 字段                     | 类型        | 必填 | 说明                                     |
| ------------------------ | ----------- | ---- | ---------------------------------------- |
| `external_batch_id`      | string      | 是   | 客户侧批次唯一 ID（同账户下不可重复）    |
| `line_id`                | string UUID | 否   | 线路 ID，**由我方提供**；不传则用账户默认线路（见 §3.2） |
| `script.domain`          | string      | 是   | `collection` 或 `sales`                  |
| `script.language`        | string      | 否   | 如 `fil` / `en` / `zh` / `es`；不传则用我方为贵方配置的默认语言 |
| `caller_pool`            | string[]    | 否   | 批次级主叫号码池，E.164；不传则回落到案件 CLI 或账户默认主叫（见 §3.3） |
| `dial_policy`            | object      | 是   | 拨号策略（见 §4.4）                      |

### 4.4 dial_policy

客户侧**只需**配置拨打窗口。振铃超时、并发上限、技术故障短重试、终态 SIP 码等由**平台环境变量**控制，不要写进 `dial_policy`。

| 字段                   | 类型     | 必填 | 说明                          |
| ---------------------- | -------- | ---- | ----------------------------- |
| `timezone`             | string   | 是   | IANA 时区。菲律宾用 `Asia/Manila`；中国大陆用 `Asia/Shanghai` |
| `windows`              | array    | 是   | 每日允许拨打窗口              |
| `windows[].start_time` | string   | 是   | 本地时间，格式 `HH:mm`        |
| `windows[].end_time`   | string   | 是   | 本地时间，格式 `HH:mm`        |
| `weekdays`             | number[] | 是   | 1=周一，7=周日                |

**再传已取消字段会失败（HTTP 422）**：旧文档里的 `ring_timeout_sec`、`retry`、`predictive`、`terminal_sip_codes`，以及已删除的顶层 `prepare_mode`，传入即 `VALIDATION_ERROR`。振铃超时、技术短重试、并发由平台环境变量控制；`retry.system.max_attempts=0` **不能**关掉 406 / 5xx 短重试。

说明：Facade **不做**忙线/未接/拒接等业务重拨；此类结果一次上报终态，由贵方系统决定是否再拨（见 §6.5）。

### 4.5 响应示例

```json
{
  "success": true,
  "data": {
    "batch_id": "8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01",
    "external_batch_id": "20260620-PH-001",
    "status": "created"
  }
}
```

---

## 5. 上传案件

### 5.1 接口

```http
POST /api/v1/facade/batches/{batch_id}/cases
```

同一 batch 可在 start 前多次上传案件；**每次最多 500 条**。批次进入 `running` 等非 `created` 状态后不再接受新增。

### 5.2 请求示例

```json
{
  "cases": [
    {
      "external_case_id": "case-001",
      "callee_e164": "+639171234567",
      "caller_cli": "+639912345678",
      "earliest_dial_at": "2026-06-20T10:00:00+08:00",
      "business_context": {
        "borrower": {
          "name": "Ana Reyes",
          "background": "BPO agent in Quezon City; temporary cash shortage after family expenses.",
          "communication_style": "Polite, cooperative; prefers Taglish."
        },
        "debt": {
          "product_type": "personal_loan",
          "currency": "PHP",
          "overdue_amount": 5775,
          "principal": 5000,
          "interest": 500,
          "penalty": 275,
          "days_past_due": 8,
          "due_date": "2026-07-16"
        },
        "prior_contacts": [],
        "prior_promises": [],
        "client_metadata": {
          "portfolio": "PH-JULY",
          "risk_level": "medium"
        }
      }
    }
  ]
}
```

### 5.3 案件字段说明

| 字段               | 类型   | 必填 | 说明                                 |
| ------------------ | ------ | ---- | ------------------------------------ |
| `external_case_id` | string | 是   | 客户侧案件唯一 ID，同一 batch 下唯一 |
| `callee_e164`      | string | 是   | 被叫号码，E.164 格式                 |
| `caller_cli`       | string | 否   | 案件级主叫，优先级最高；不传则见 §3.3 |
| `earliest_dial_at` | string | 否   | 最早拨号时间，ISO 8601；不传则按批次窗口尽快拨打 |
| `business_context` | object | 是   | 业务上下文，详见 §5.4              |

### 5.4 business_context 顶层结构

| 字段                  | 类型   | 必填 | 说明                                           |
| --------------------- | ------ | ---- | ---------------------------------------------- |
| `borrower`            | object | 是   | 借款人信息（至少含 `name`）                    |
| `debt`                | object | 是   | 欠款核心信息（催收目标金额、逾期天数等）       |
| `installments`        | object | 否   | 分期信息（仅多期贷款；单期不传）               |
| `prior_contacts`      | array  | 否   | 历史沟通记录，首次联系可不传或传 `[]`          |
| `prior_promises`      | array  | 否   | 历史还款承诺，无则可不传或传 `[]`              |
| `negotiation_options` | string | 否   | 可接受的协商方案描述                           |
| `brand_name`          | string | 否   | 覆盖默认品牌名；不传则用我方为贵方配置的品牌名 |
| `client_metadata`     | object | 否   | 客户自用管理信息，不影响 AI 对话，结果回传时原样返回 |

#### `borrower`（必填）

| 字段                  | 类型   | 必填 | 说明 |
| --------------------- | ------ | ---- | ---- |
| `name`                | string | 是   | 借款人姓名；AI 开场核身会使用 |
| `background`          | string | 否   | 背景简述（职业、还款习惯、逾期原因等），帮助 AI 选策略；建议填写 |
| `communication_style` | string | 否   | 沟通风格偏好（如 `Polite, prefers Taglish`）；建议填写 |

#### `debt`（必填）

| 字段             | 类型   | 必填 | 说明 |
| ---------------- | ------ | ---- | ---- |
| `product_type`   | string | 是   | 产品类型：`personal_loan` / `credit_line` / `other` |
| `currency`       | string | 是   | 币种，ISO 4217，如 `PHP` / `USD` / `IDR` |
| `overdue_amount` | number | 是   | **催收目标金额**（当前应还逾期总额，含本金+利息+罚息）；AI 围绕此金额催收 |
| `days_past_due`  | number | 是   | 逾期天数（非负） |
| `due_date`       | string | 是   | 应还日期，`YYYY-MM-DD` |
| `principal`      | number | 否   | 逾期本金；与 `interest`、`penalty` 三者**同时提供**时，之和须等于 `overdue_amount` |
| `interest`       | number | 否   | 逾期利息（利息预扣产品可填 `0`） |
| `penalty`        | number | 否   | 逾期罚息 / 滞纳金 |

说明：`overdue_amount` 须为贵方系统算好的最终应还总额。不提供金额构成时，AI 只报总额、不主动拆解；三者齐全时，借款人追问金额构成可解释本金/利息/罚息。

#### `installments`（可选）

仅多期贷款填写。一旦传入，下列四个字段均必填，且均为非负整数；须满足 `paid + overdue + upcoming == total`。

| 字段       | 类型    | 说明                         |
| ---------- | ------- | ---------------------------- |
| `total`    | integer | 贷款总期数                   |
| `paid`     | integer | 已结清期数                   |
| `overdue`  | integer | 当前逾期期数（催收中）       |
| `upcoming` | integer | 尚未到期期数                 |

示例（3 期：已还 1、逾期 1、未到期 1）：

```json
"installments": {
  "total": 3,
  "paid": 1,
  "overdue": 1,
  "upcoming": 1
}
```

#### `prior_contacts`（可选）

此前与该借款人的沟通记录；AI 据此调整策略（如对违约承诺者语气更坚定）。

- **首次联系**：不传，或传 `[]`
- **再次联系**：建议最近 **1–3 条**，无需完整历史

数组元素：

| 字段      | 类型   | 说明 |
| --------- | ------ | ---- |
| `date`    | string | 沟通日期，`YYYY-MM-DD` |
| `result`  | string | 沟通结果，见下表 |
| `summary` | string | 一句话摘要（建议填写） |

`result` 取值：

| 值                   | 含义 |
| -------------------- | ---- |
| `promise_obtained`   | 做出了还款承诺 |
| `no_answer`          | 未接听 |
| `refused`            | 明确拒绝还款 |
| `vague_commitment`   | 模糊承诺（如「过几天再说」） |
| `callback_requested` | 要求回电 |

```json
"prior_contacts": [
  {
    "date": "2026-07-20",
    "result": "promise_obtained",
    "summary": "Promised ₱2000 by 7/22, but did not pay."
  },
  {
    "date": "2026-07-22",
    "result": "no_answer",
    "summary": "No answer after 45s ringing."
  }
]
```

#### `prior_promises`（可选）

借款人此前做出的还款承诺；AI 可在通话中引用违约承诺。

- **无历史承诺**：不传，或传 `[]`
- **有历史承诺**：通常提供最近 1–2 条（尤其是未兑现的）

数组元素：

| 字段            | 类型   | 说明 |
| --------------- | ------ | ---- |
| `amount`        | number | 承诺还款金额 |
| `promised_date` | string | 承诺还款日期，`YYYY-MM-DD` |
| `status`        | string | 承诺状态，见下表 |

`status` 取值：

| 值          | 含义 |
| ----------- | ---- |
| `pending`   | 仍在有效期内，尚未到期 |
| `fulfilled` | 已兑现 |
| `broken`    | 已违约（过了承诺日仍未还） |

```json
"prior_promises": [
  {
    "amount": 2000,
    "promised_date": "2026-07-22",
    "status": "broken"
  }
]
```

#### `negotiation_options`（可选）

| 项     | 说明 |
| ------ | ---- |
| 类型   | string |
| 何时填 | 希望 AI 有权在通话中提供协商方案（分期、折扣等）时 |
| 不填则 | AI 只推全额还款，不主动协商 |

用一句话描述可接受的让步范围，例如：

```json
"negotiation_options": "Installment up to 3 months; 10% discount for same-day full payment."
```

#### `brand_name`（可选）

| 项     | 说明 |
| ------ | ---- |
| 类型   | string |
| 何时填 | 多品牌运营，需在本案使用非默认品牌名时 |
| 不填则 | 使用我方为贵方配置的默认品牌名 |

```json
"brand_name": "QuickCash"
```

#### `client_metadata`（可选）

| 项     | 说明 |
| ------ | ---- |
| 类型   | object |
| 用途   | 贵方自用管理信息（资产包、风险等级、内部 ID 等） |
| AI     | **不影响对话**——AI 不会读取此字段 |
| 回传   | 通话结果 Callback 中原样返回，便于对账 |

内部结构由贵方自定义，我方不校验内容。示例：

```json
"client_metadata": {
  "portfolio": "PH-JULY",
  "risk_level": "medium",
  "loan_id": "LN-20260725-001"
}
```

#### 校验规则摘要

提交案件时，以下情况会导致该案件被拒绝（错误码多为 `INVALID_BUSINESS_CONTEXT`）：

| 校验项       | 规则 |
| ------------ | ---- |
| 借款人姓名   | `borrower.name` 必须存在且非空 |
| 欠款信息     | `debt` 必须存在；`overdue_amount`、`days_past_due`、`currency`、`due_date`、`product_type` 均必填 |
| 金额非负     | `overdue_amount`、`days_past_due` ≥ 0 |
| 金额构成加和 | 若同时提供 `principal`、`interest`、`penalty`，三者之和须等于 `overdue_amount` |
| 分期加和     | 若提供 `installments`，`paid + overdue + upcoming` 须等于 `total` |
| 数组格式     | `prior_contacts`、`prior_promises` 若提供须为数组 |
| 元数据格式   | `client_metadata` 若提供须为对象 |

不填不影响提交、但建议填写：`borrower.background`、`borrower.communication_style`、金额构成字段——有助于对话质量。

### 5.5 响应示例

```json
{
  "success": true,
  "data": {
    "batch_id": "8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01",
    "accepted": 1,
    "rejected": 0,
    "items": [
      {
        "external_case_id": "case-001",
        "status": "accepted"
      }
    ],
    "errors": []
  }
}
```

部分失败示例：

```json
{
  "success": true,
  "data": {
    "accepted": 1,
    "rejected": 1,
    "items": [{ "external_case_id": "case-001", "status": "accepted" }],
    "errors": [
      {
        "external_case_id": "case-002",
        "error_code": "INVALID_E164",
        "message": "callee_e164 must be in E.164 format"
      }
    ]
  }
}
```

---

## 6. 开始拨打

### 6.1 接口

```http
POST /api/v1/facade/batches/{batch_id}/start
```

请求体可为空对象 `{}`（无必填字段；未知字段忽略）。仅当批次状态为 `created` 时可调用；无已接收案件时返回 `NO_CASES`。

### 6.2 行为

- batch 状态从 `created` 变为 `running`。
- 批次内 `accepted` 案件进入拨号队列（`queued`）。
- Facade 按 `dial_policy.windows`、`weekdays`、`earliest_dial_at` 与并发上限调度。
- 若 start 时不在拨号窗口内，接单后等待下一个窗口再拨。

### 6.3 响应示例

```json
{
  "success": true,
  "data": {
    "batch_id": "8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01",
    "external_batch_id": "20260620-PH-001",
    "status": "running",
    "queued": 120
  }
}
```

### 6.4 批次控制（暂停 / 恢复 / 取消）

```http
POST /api/v1/facade/batches/{batch_id}/pause    // running → paused，在途通话不中断，仅停止新派发
POST /api/v1/facade/batches/{batch_id}/resume   // paused → running
POST /api/v1/facade/batches/{batch_id}/cancel   // created/running/paused → cancelled
```

- `cancel` 后：待拨案件（`accepted`/`queued`）立即置为 `cancelled`；在途通话不强制挂断，结束后收尾为 `cancelled`，且不再重拨。
- `cancelled` 批次不可恢复、不可重试。

### 6.5 手工重试（可选）

同一批次内将终态案件重新入队（客户主动触发）。批次仍在 `running` / `paused` 时即可调用，不必等整批结束。  
**不等同于** Facade 自动业务重拨；跨天、合规窗口等日常再拨也可由贵方新建批次/案件编排。

```http
POST /api/v1/facade/batches/{batch_id}/retry
POST /api/v1/facade/batches/{batch_id}/cases/{case_id}/retry
```

#### 请求体字段

| 字段 | 类型 | 默认 | 适用接口 | 说明 |
| ---- | ---- | ---- | -------- | ---- |
| `failure_reasons` | string[] \| null | `null`（全部 `failed`） | 仅 batch `/retry` | 只重拨**案件** `status=failed` 且 `failure_reason` 命中列表的案件；可选值见下方枚举及 §9.5 |
| `include_completed` | bool | `false` | 两者 | `true` 时额外纳入**案件** `status=completed`（案件状态见 §9.2）。`true` 时必须同时传非空 `completed_reasons`，否则 **422** |
| `completed_reasons` | string[] | 无默认 | 两者 | 仅当 `include_completed=true` 必填；首版只允许 `VOICEMAIL`、`CALL_SCREENING`。按该案**当前** `session_id` 对应记录的 `line_outcome.reason` 过滤（§9.4）。`include_completed=false` 时忽略本字段 |
| `reset_retry_state` | bool | `false` | 两者 | `true` 时清零该案技术故障自动重试计数（`retry_state`） |

说明：上表 `status` 指**案件状态**（§9.2 的 `failed` / `completed`），不是批次状态（§9.1）。`failure_reasons` 与案件字段 `failure_reason`、Callback 的 `final_failure_reason` 同一套取值。

#### 示例

只重拨未接：

```json
{ "failure_reasons": ["NO_ANSWER"], "reset_retry_state": false }
```

重拨未接 + 全部信箱/筛选助理（不含已真人谈完的）：

```json
{
  "failure_reasons": ["NO_ANSWER", "BUSY"],
  "include_completed": true,
  "completed_reasons": ["VOICEMAIL", "CALL_SCREENING"],
  "reset_retry_state": false
}
```

单案重拨已完成的信箱：

```json
{
  "include_completed": true,
  "completed_reasons": ["VOICEMAIL"]
}
```

#### `failure_reasons` 枚举（`status=failed`）

| 值 | 含义 |
| -- | ---- |
| `BUSY` | 忙线（SIP 486） |
| `NO_ANSWER` | 未接 / 振铃超时（含 SIP 487） |
| `FORBIDDEN` | 禁止呼叫（SIP 403） |
| `DECLINE` | 被叫拒接（SIP 603） |
| `TEMP_UNAVAILABLE` | 临时不可达（SIP 480） |
| `REQUEST_TIMEOUT` | 请求超时（SIP 408） |
| `INVALID_NUMBER` | 空号（SIP 404/604） |
| `FAILED` | 其它未映射 4xx 等 |
| `MEDIA_NEGOTIATION_FAILED` | 媒体协商失败（SIP 406；若技术重试已耗尽） |
| `SIP_SERVER_ERROR` | 对端/运营商 5xx（若技术重试已耗尽） |
| `MISSING_CALLER_CLI` / `NO_CALLER` | 无可用主叫 |
| `NO_ACTIVE_LINE` | 无可用线路 |
| `SESSION_START_FAILED` / `DIAL_ERROR` / `MEDIA_TIMEOUT` 等 | 会话准备 / 拨号 / 媒体超时等技术故障耗尽后 |

未列出的失败码仍可原样传入 `failure_reasons` 过滤。

#### `completed_reasons` 枚举（`line_outcome.reason`，`status=completed`）

| 值 | 含义 |
| -- | ---- |
| `VOICEMAIL` | 语音信箱（已留言模板） |
| `CALL_SCREENING` | 来电筛选助理（已留言模板） |

首版不允许用 `NORMAL` 做批量重拨（已谈完的真人不要走 `/retry`）。其它偶发 `line_outcome.reason` 见 §9.4，本期接口不接受。

#### 行为说明

- 命中案件：`failed` / `completed` → `queued`，清除 `failure_reason`、`ended_at`；历史 session 记录保留，再次拨打产生新 `session_id` 与新的 `session.completed`。
- 受拨打窗口约束（`next_dial_at`）。
- 单案接口：`completed` 必须 `include_completed=true`；若同时传了 `completed_reasons` 且不匹配则 `409`。
- 批次若已 `completed`，会拉回 `running`，结束后会**再次**推送 `batch.completed`——请按 `batch_id` 幂等。
- `cancelled` 批次不可重试。

---

## 7. 查询批次

### 7.1 查询批次概要

```http
GET /api/v1/facade/batches/{batch_id}
```

响应示例：

```json
{
  "success": true,
  "data": {
    "batch_id": "8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01",
    "external_batch_id": "20260620-PH-001",
    "status": "running",
    "line_id": "550e8400-e29b-41d4-a716-446655440000",
    "script": { "domain": "collection", "language": "zh" },
    "caller_pool": ["+639912345678"],
    "dial_policy": {
      "timezone": "Asia/Manila",
      "windows": [{ "start_time": "09:00", "end_time": "20:00" }],
      "weekdays": [1, 2, 3, 4, 5]
    },
    "counts": {
      "total": 120,
      "accepted": 0,
      "queued": 80,
      "dialing": 5,
      "in_progress": 2,
      "completed": 32,
      "failed": 0,
      "cancelled": 0,
      "retry_waiting": 1
    },
    "created_at": "2026-06-20T09:00:00+08:00",
    "started_at": "2026-06-20T09:05:00+08:00",
    "completed_at": null
  }
}
```

`counts.dialing` 为 `starting` + `scheduled` + `dialing` 的汇总；`retry_waiting` 为已有尝试次数且仍在 `queued` 等待技术重试的案件数。

### 7.2 查询批次案件

```http
GET /api/v1/facade/batches/{batch_id}/cases?offset=0&limit=50
```

响应示例：

```json
{
  "success": true,
  "data": {
    "batch_id": "8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01",
    "total": 120,
    "offset": 0,
    "limit": 50,
    "items": [
      {
        "case_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        "external_case_id": "case-001",
        "callee_e164": "+639171234567",
        "caller_cli": null,
        "resolved_caller_cli": "+639912345678",
        "status": "completed",
        "session_id": "550e8400-e29b-41d4-a716-446655440001",
        "failure_reason": null,
        "attempt_count": 1,
        "next_dial_at": null,
        "retry_state": {},
        "earliest_dial_at": null,
        "ended_at": "2026-06-20T10:01:05+00:00"
      }
    ]
  }
}
```

单案重试接口路径中的 `{case_id}` 使用上表 `case_id`（Facade 内部 UUID），不是 `external_case_id`。  
`attempt_count` 含平台技术短重试（见 §4.4），不是贵方业务重拨次数。

---

## 8. 查询单通

### 8.1 接口

```http
GET /api/v1/facade/sessions/{session_id}
```

### 8.2 响应示例

```json
{
  "success": true,
  "data": {
    "session_id": "550e8400-e29b-41d4-a716-446655440001",
    "batch_id": "8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01",
    "external_batch_id": "20260620-PH-001",
    "external_case_id": "case-001",
    "status": "completed",
    "callee_e164": "+639171234567",
    "caller_cli": "+639912345678",
    "dial_timeline": {
      "dialed_at": "2026-06-20T10:00:01+08:00",
      "ringing_at": "2026-06-20T10:00:03+08:00",
      "answered_at": "2026-06-20T10:00:12+08:00",
      "ended_at": "2026-06-20T10:01:05+08:00"
    },
    "line_outcome": {
      "reason": "NORMAL",
      "was_ringing": true,
      "was_answered": true,
      "was_ai_connected": true
    },
    "integration_result": {
      "ai_result": {
        "result_label": "promise_to_pay",
        "summary": "客户承诺在周五前还款。",
        "promises": [
          {
            "amount": 500.0,
            "currency": "PHP",
            "promised_date": "2026-06-25",
            "status": "logged"
          }
        ]
      },
      "media": {
        "recording_url": "https://{host}/media/recordings/550e8400-e29b-41d4-a716-446655440001.wav",
        "script_url": "https://{host}/media/scripts/550e8400-e29b-41d4-a716-446655440001.json",
        "recording_status": "ready"
      }
    },
    "callback_status": "delivered"
  }
}
```

说明：

- `status` 为**案件状态**（见 §9.2）。
- AI 结论与媒体 URL 通常在 `integration_result` 内；完整对话文本以下载 `script_url` 为准。
- Callback 载荷（§11）将 `ai_result` / `media` 提至顶层，字段语义一致；联调以 Callback 为主，本接口作对账兜底。
- 不存在时返回 `SESSION_NOT_FOUND`。

---

## 9. 状态与结果码

### 9.1 Batch 状态

| 状态        | 含义                   |
| ----------- | ---------------------- |
| `created`   | 已创建，可继续上传案件 |
| `running`   | 已开始拨打             |
| `paused`    | 已暂停，不派发新通话（在途不中断） |
| `completed` | 批次内案件全部结束     |
| `cancelled` | 批次被取消             |

### 9.2 Case 状态（查询 / counts 可见）

| 状态          | 含义 |
| ------------- | ---- |
| `accepted`    | 已接收，等待 start |
| `queued`      | 在拨号队列中（含技术重试等待；可用 `attempt_count > 0` 区分） |
| `starting`    | 正在创建会话 / 准备媒体 |
| `scheduled`   | 等待拨号窗口或 `earliest_dial_at` |
| `dialing`     | 已发起 SIP INVITE / 振铃中 |
| `in_progress` | 已接通，AI 对话或信箱留言进行中 |
| `completed`   | 终态：接通并正常收口（含 VOICEMAIL / CALL_SCREENING） |
| `failed`      | 终态：未接通或技术失败耗尽 |
| `cancelled`   | 终态：批次/案件取消 |

> 批次 `counts.dialing` 会把 `starting` + `scheduled` + `dialing` 汇总展示，便于看板。

### 9.3 如何读 `line_outcome` 与 `failure_reason`

两者**不是两套互斥码表**，而是挂在不同层级上的结果字段：

| | `line_outcome.reason` | `failure_reason` / Callback `final_failure_reason` |
| --- | --- | --- |
| 层级 | **本通会话**（session） | **案件终态**（case） |
| 何时有值 | 有会话记录时通常都有 | 仅案件 `status=failed`；`completed` 时为 `null` |
| 主要用途 | 看「这通电话线路上发生了什么」；`include_completed` 的 `completed_reasons` 过滤也用它 | 看「案件为何失败」；§6.5 `failure_reasons` 过滤用它 |

**推荐读法：**

1. 先看案件 `status`（§9.2）
2. `completed` → 只看 `line_outcome.reason`（下表接通侧取值）+ `ai_result`；`final_failure_reason` 必为 `null`
3. `failed` → 以 `failure_reason` / `final_failure_reason` 为准（§9.5）；若有会话，当次 `line_outcome.reason` **通常与之同值**（不是另一套码）

### 9.4 line_outcome（会话侧）

挂在会话 / Callback 的 `line_outcome` 对象上。除 `reason` 外还有接通标志位。

#### 案件 `completed` 时的 `reason`（接通侧）

| reason | 含义 | 客户侧建议 |
| ------ | ---- | ---------- |
| `NORMAL` | 真人对话后正常结束 | 读 `ai_result` |
| `VOICEMAIL` | 语音信箱，已播留言模板并挂断 | 业务上可再联系；**不计真人接通** |
| `CALL_SCREENING` | 来电筛选助理，已留言并挂断 | 同上 |

§6.5 `completed_reasons` 首版只接受 `VOICEMAIL`、`CALL_SCREENING`。`NORMAL`（真人谈完）不能走批量重拨；传入会 422。

#### 案件 `failed` 时的 `reason`

未接通或技术失败耗尽时，会话上也会写 `line_outcome.reason`，取值与 §9.5 **同一套失败码**（如 `BUSY`、`NO_ANSWER`），请直接查 §9.5，勿当作与 `failure_reason` 不同的枚举。

#### 标志位与其它字段

| 字段 | 含义 |
| ---- | ---- |
| `was_ringing` | 是否进入振铃 |
| `was_answered` | 线路是否接通（**含信箱 / 筛选助理**） |
| `was_ai_connected` | 是否进入真人多轮对话（信箱 / 筛选助理为 `false`） |
| `sip_code` | 可选。未接通失败时常见（如 486 / 406 / 487）；接通收口可能没有 |

### 9.5 failure_reason（案件 `failed` 时）

案件终态为 `failed` 时写入 `failure_reason`；Callback 中对应 `final_failure_reason`（成功终态为 `null`）。与 §6.5 `failure_reasons` 过滤同一套取值：

| 值 | 含义 | 客户侧建议 |
| -- | ---- | ---------- |
| `BUSY` | 忙线（SIP 486） | 业务重拨由贵方决定（§6.5 或新建批次） |
| `NO_ANSWER` | 未接 / 振铃超时（含 SIP 487） | 同上 |
| `FORBIDDEN` | 禁止呼叫（SIP 403） | 同上 |
| `DECLINE` | 被叫拒接（SIP 603） | 同上 |
| `TEMP_UNAVAILABLE` | 临时不可达（SIP 480） | 同上 |
| `REQUEST_TIMEOUT` | 请求超时（SIP 408） | 同上 |
| `INVALID_NUMBER` | 空号（SIP 404/604） | 勿再拨该号 |
| `FAILED` | 其它未映射 4xx 等 | 按需排查 |
| `MEDIA_NEGOTIATION_FAILED` | 媒体协商失败（SIP 406） | Facade 可能已做技术重试；仍失败可联系我方 |
| `SIP_SERVER_ERROR` | 对端/运营商 5xx | 同上 |
| `MISSING_CALLER_CLI` / `NO_CALLER` | 无可用主叫 | 联系我方 |
| `NO_ACTIVE_LINE` | 无可用线路 | 联系我方 |
| `SESSION_START_FAILED` / `DIAL_ERROR` / `MEDIA_TIMEOUT` 等 | 会话准备 / 拨号 / 媒体等技术故障耗尽后 | 联系我方 |

手工重拨时把上表值放入 `failure_reasons` 即可过滤；未列出的码也可原样传入过滤。

---

## 10. 语音信箱 / 来电筛选

部分接通并不会进入真人催收对话。系统在**接通之后**根据对方首段/后续转写做文本判定（关键字 + LLM，**不是**接通前声学 AMD）：命中语音信箱或来电筛选助理后，按约定播留言模板并结束该通。此类案件**状态为 `completed`**（不是 `failed`），Callback 仍会推送 `session.completed`。

载荷约定：

| 字段 | VOICEMAIL / CALL_SCREENING |
| ---- | -------------------------- |
| `line_outcome.reason` | `VOICEMAIL` 或 `CALL_SCREENING` |
| `was_answered` | `true`（线路已接通） |
| `was_ai_connected` | `false`（未进入真人多轮） |
| `ai_result.result_label` | 通常为 `null` |
| `ai_result.summary` | 可能有一句说明（如 `Call reached voicemail; voicemail message left.`），也可能为 `null` |
| `ai_result.promises` | `[]` |
| `media.recording_url` / `script_url` | 接通案通常有（见 §11.5） |

若需对信箱 / 筛选助理再联系：同批用 §6.5（`include_completed` + `completed_reasons`），或新建批次。

**接通率分子请用「真人接通」**：`was_answered && reason ∉ {VOICEMAIL, CALL_SCREENING}`（或以 `was_ai_connected == true` 为准，按贵方报表定义）。勿把信箱计入真人接通。

---

## 11. Callback 接收端开发规范

> 生产环境：我方将结果 **直接 POST** 到贵方提供的 HTTPS URL（开通方式见 §3.4）。

### 11.1 事件类型与契约

| 事件 | 触发时机 |
| ---- | -------- |
| `session.completed` | 案件到达终态（`completed` / `failed`）且存在会话时，**推送一次**（至少一次语义） |
| `batch.completed` | 批次全部结束 |

约定：

1. **只推最终结果**：技术故障自动重试的中间尝试不推 webhook；载荷含 `attempt_count`、`final_failure_reason`（成功为 `null`），`session_id` 为最后一次会话。
2. **无会话的最终失败不推 `session.completed`**：会话创建前即失败时，请以 `batch.completed` + 案件查询兜底。
3. **`batch.completed` 可能重复**：批次被重新拉起后再完成会再推，请按 `batch_id` 幂等。
4. 我方投递失败会按退避重试（约 30s、60s、120s…，上限约 1h，默认最多约 5 次）。贵方必须在处理成功后返回 **HTTP 2xx**。

### 11.2 HTTP 约定

| 项 | 要求 |
| -- | ---- |
| 方法 | `POST` |
| URL | 贵方提供的公网 **HTTPS**（路径自定） |
| 请求头 | `Content-Type: application/json` |
| 签名头 | `X-Valubo-Signature: <hex>`（HMAC-SHA256） |
| 成功响应 | **任意 2xx**（建议 `200` + 简短 JSON，如 `{"ok":true}`） |
| 失败响应 | `4xx`/`5xx` 会触发我方重试；**验签失败请返回 `401`** |
| 超时 | 请尽快返回 2xx（常见要求在数十秒内）；重活请异步化 |

### 11.3 签名算法（必须按此实现）

验签密钥为双方约定的 **Callback secret**（见 §3.4）。  
**不要对原始 HTTP body 字节直接验签。** 正确步骤：

1. 将 body 解析为 JSON 对象（UTF-8）。
2. 用**规范序列化**重算字符串：  
   `json.dumps(payload, separators=(",", ":"), sort_keys=True)`  
   （无多余空格；键按字典序排序。）
3. 计算：  
   `HMAC_SHA256(key=callback_secret, msg=canonical_utf8_bytes)` → **小写 hex**。
4. 与请求头 `X-Valubo-Signature` 做**常量时间比较**（如 `hmac.compare_digest`）。

Python 参考：

```python
import hashlib
import hmac
import json

def verify_signature(secret: str, payload: dict, provided: str) -> bool:
    body = json.dumps(payload, separators=(",", ":"), sort_keys=True)
    expected = hmac.new(secret.encode(), body.encode(), hashlib.sha256).hexdigest()
    return hmac.compare_digest(provided or "", expected)
```

伪代码（其它语言）：

```text
canonical = JSON.stringify(sortKeys(payload), compact=true)  // 无空格
expected  = hex(hmac_sha256(secret, utf8(canonical)))
accept if constant_time_equal(expected, header["X-Valubo-Signature"])
```

### 11.4 幂等与处理建议

| 事件 | 幂等键 | 建议动作 |
| ---- | ------ | -------- |
| `session.completed` | `session_id`（辅以 `external_case_id`） | Upsert 联系结果；下载 `media` URL；写入贵方案件联系记录 |
| `batch.completed` | `batch_id` | 更新批次汇总；触发对账任务 |

- 重复投递时：**覆盖写**同键记录即可，勿重复扣减额度或重复触发外呼。
- 验签通过后再落库；先 ACK 再异步拉录音亦可，但须保证最终一致性。
- `client_metadata`：若创建案件时写入 `business_context.client_metadata`，会原样出现在 `session.completed` 中，便于回写贵方主键。

### 11.5 session.completed 字段与示例

| 字段 | 说明 |
| ---- | ---- |
| `event` | 固定 `session.completed` |
| `timestamp` | UTC ISO8601 |
| `external_batch_id` / `external_case_id` | 客户侧 ID |
| `session_id` | 最后一次会话 UUID |
| `attempt_count` | 该案总尝试次数（含首拨 **以及** 平台对技术故障的短重试；不是贵方业务重拨次数） |
| `final_failure_reason` | 失败终态原因；成功为 `null`（见 §9.5） |
| `parties` | `callee_e164`、`caller_cli`（**实际外显**，为解析后的主叫；格式以账户配置为准，不一定带 `+`） |
| `dial_timeline` | 拨号时间线；至少常有 `dialed_at`，`ringing_at` / `answered_at` / `ended_at` 在观测到时才有 |
| `line_outcome` | 见 §9.3 / §9.4（失败时可能带 `sip_code`） |
| `ai_result` | `result_label` / `summary` / `promises`（精简；全文见 `script_url`） |
| `media` | 见下；接通场景尽量齐，我方可能短暂等待媒体就绪后再投递 |
| `client_metadata` | 可选，原样回传 |

`media` 常见字段：

| 字段 | 说明 |
| ---- | ---- |
| `recording_url` | `https://{host}/media/recordings/{session_id}.wav` |
| `script_url` | `https://{host}/media/scripts/{session_id}.json` |
| `recording_status` | 如 `ready`（媒体已可下载） |

```json
{
  "event": "session.completed",
  "timestamp": "2026-06-20T02:01:08+00:00",
  "external_batch_id": "20260620-PH-001",
  "external_case_id": "case-001",
  "session_id": "550e8400-e29b-41d4-a716-446655440001",
  "attempt_count": 1,
  "final_failure_reason": null,
  "parties": {
    "callee_e164": "+639171234567",
    "caller_cli": "+639912345678"
  },
  "dial_timeline": {
    "dialed_at": "2026-06-20T02:00:01+00:00",
    "ringing_at": "2026-06-20T02:00:03+00:00",
    "answered_at": "2026-06-20T02:00:12+00:00",
    "ended_at": "2026-06-20T02:01:05+00:00"
  },
  "line_outcome": {
    "reason": "NORMAL",
    "was_ringing": true,
    "was_answered": true,
    "was_ai_connected": true
  },
  "ai_result": {
    "result_label": "promise_to_pay",
    "summary": "客户承诺在周五前还款。",
    "promises": [
      {
        "amount": 500.0,
        "currency": "PHP",
        "promised_date": "2026-06-25",
        "status": "logged"
      }
    ]
  },
  "media": {
    "recording_url": "https://{host}/media/recordings/550e8400-e29b-41d4-a716-446655440001.wav",
    "script_url": "https://{host}/media/scripts/550e8400-e29b-41d4-a716-446655440001.json",
    "recording_status": "ready"
  },
  "client_metadata": {
    "portfolio": "PH-JULY",
    "risk_level": "medium",
    "loan_id": "LN-20260725-001"
  }
}
```

信箱示例（节选）：

```json
{
  "event": "session.completed",
  "line_outcome": {
    "reason": "VOICEMAIL",
    "was_ringing": true,
    "was_answered": true,
    "was_ai_connected": false
  },
  "ai_result": {
    "result_label": null,
    "summary": "Call reached voicemail; voicemail message left.",
    "promises": []
  },
  "final_failure_reason": null
}
```

未接通失败示例（节选）：

```json
{
  "event": "session.completed",
  "attempt_count": 1,
  "final_failure_reason": "BUSY",
  "line_outcome": {
    "reason": "BUSY",
    "sip_code": 486,
    "was_ringing": true,
    "was_answered": false,
    "was_ai_connected": false
  },
  "ai_result": {
    "result_label": null,
    "summary": null,
    "promises": []
  }
}
```

### 11.6 batch.completed 示例

```json
{
  "event": "batch.completed",
  "timestamp": "2026-06-20T10:00:00+00:00",
  "external_batch_id": "20260620-PH-001",
  "batch_id": "8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01",
  "status": "completed",
  "line_id": "550e8400-e29b-41d4-a716-446655440000",
  "started_at": "2026-06-20T01:05:00+00:00",
  "completed_at": "2026-06-20T10:00:00+00:00",
  "summary": {
    "total": 120,
    "completed": 114,
    "failed": 6,
    "promise_to_pay": 12,
    "follow_up_required": 9
  }
}
```

`summary` 固定含 `total` / `completed` / `failed`；其余键为各案件 `ai_result.result_label` 的计数聚合。线路维度（忙线、未接、信箱等）请以逐案 `session.completed` 或案件查询为准。

### 11.7 联调检查清单

1. 暴露公网 HTTPS POST 端点，并将 URL + secret 提供给我方开通（§3.4）。
2. 解析 JSON → 按 §11.3 验签 → 失败返回 `401`。
3. 按 `session_id` / `batch_id` 幂等落库。
4. 处理成功后返回 `2xx`。
5. 用双方约定的 secret 对拍一次真实小批次，确认签名与字段。

---

## 12. 常见错误码

接口级（HTTP 响应 `error.code`）：

| code                       | 场景                                          |
| -------------------------- | --------------------------------------------- |
| `INVALID_API_KEY`          | API Key 缺失、无效或已吊销                    |
| `DUPLICATE_BATCH`          | 同一账户下 `external_batch_id` 已存在         |
| `BATCH_NOT_FOUND`          | 批次不存在或不属于当前账户                    |
| `CASE_NOT_FOUND`           | 案件不存在或不属于该批次                      |
| `SESSION_NOT_FOUND`        | 会话不存在或不属于当前账户                    |
| `INVALID_BATCH_STATUS`     | 当前批次状态不允许该操作                      |
| `INVALID_CASE_STATUS`      | 当前案件状态不允许重试等操作                  |
| `INVALID_LINE_ID`          | 指定线路无效或不可用                          |
| `NO_ACTIVE_LINE`           | 未指定线路且无可用默认线路（常见于 start）    |
| `NO_CASES`                 | start 时没有 `accepted` 案件可拨              |
| `INVALID_E164`             | 电话号码不是 E.164（上传案件明细错误）        |
| `DUPLICATE_CASE`           | 同一 batch 下 `external_case_id` 重复（上传明细） |
| `INVALID_BUSINESS_CONTEXT` | `business_context` 不符合校验规则（上传明细） |
| `VALIDATION_ERROR`         | 请求体 JSON / 字段类型不合 schema（**HTTP 422**） |
| `HTTP_ERROR`               | 其它 HTTP 层错误（少见）                          |

请求体格式错误（如缺必填字段、`dial_policy` 缺窗口字段、`cases` 为空、再传已取消的 `ring_timeout_sec` / `retry`、或 `include_completed=true` 却不带 `completed_reasons`）返回 **HTTP 422** + `VALIDATION_ERROR`（见 §4.4、§6.5）。

案件终态失败原因见 §9.5（`failure_reason` / Callback 的 `final_failure_reason`），与上表接口错误码不同。

---

## 13. 号码格式

电话号码使用 E.164 格式：

```text
+国家码 + 本地号码，不含前导 0
```

示例：

| 国家/地区 | 示例             |
| --------- | ---------------- |
| 菲律宾    | `+639171234567`  |
| 中国大陆  | `+8613800138000` |

所有 `callee_e164`、`caller_cli`、`caller_pool[]` 都应为 E.164。

---

## 14. 客户接入建议

1. 向我方索取 API Key；如需固定线路，索取 `line_id`；确认账户默认主叫与默认语言。
2. 向我方提供 Callback URL + secret，并完成 §11 联调。
3. `external_batch_id` 使用可追踪的业务批次号，例如 `20260620-PH-COLLECTION-A`。
4. `external_case_id` 在同一 batch 下保持唯一。
5. `business_context.client_metadata` 可放客户侧标签（会在 Callback 回传），但不要放敏感凭据。
6. **优先接好 Callback（§11）**：结果直达贵方 URL；查询接口作对账兜底。
7. **业务重拨由贵方系统负责**（BUSY / NO_ANSWER / DECLINE / VOICEMAIL 等）。同批再拨可用 §6.5；跨天/新策略也可新建批次。
8. 需要固定线路时传入我方提供的 `line_id`；不传则用默认线路。
9. 需要指定外显主叫时，在案件传 `caller_cli` 或在批次传 `caller_pool`；均不传则用账户默认主叫。
10. 希望延后拨打单个案件时，使用 `earliest_dial_at`；不传则按批次窗口尽快拨打。
11. 报表区分线路接通与真人接通；信箱 / 筛选助理计入前者、不计入后者（见 §10）。

---

## 15. 完整调用示例

### 15.1 创建批次

```bash
curl -X POST "https://{host}/api/v1/facade/batches" \
  -H "Authorization: Bearer sk_live_xxx" \
  -H "Content-Type: application/json" \
  -d '{
    "external_batch_id": "20260620-PH-001",
    "line_id": "550e8400-e29b-41d4-a716-446655440000",
    "script": {
      "domain": "collection",
      "language": "zh"
    },
    "caller_pool": ["+639912345678"],
    "dial_policy": {
      "timezone": "Asia/Manila",
      "windows": [{ "start_time": "09:00", "end_time": "20:00" }],
      "weekdays": [1, 2, 3, 4, 5, 6]
    }
  }'
```

### 15.2 上传案件

```bash
curl -X POST "https://{host}/api/v1/facade/batches/8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01/cases" \
  -H "Authorization: Bearer sk_live_xxx" \
  -H "Content-Type: application/json" \
  -d '{
    "cases": [
      {
        "external_case_id": "case-001",
        "callee_e164": "+639171234567",
        "business_context": {
          "borrower": { "name": "Ana Reyes" },
          "debt": {
            "product_type": "personal_loan",
            "currency": "PHP",
            "overdue_amount": 5775,
            "days_past_due": 8,
            "due_date": "2026-07-16"
          }
        }
      }
    ]
  }'
```

### 15.3 开始拨打

```bash
curl -X POST "https://{host}/api/v1/facade/batches/8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01/start" \
  -H "Authorization: Bearer sk_live_xxx" \
  -H "Content-Type: application/json" \
  -d '{}'
```

### 15.4 查询批次

```bash
curl "https://{host}/api/v1/facade/batches/8c93c3f1-f3ef-4f3c-8f7d-2a8d2c2c9f01" \
  -H "Authorization: Bearer sk_live_xxx"
```

---

## 16. 集成检查清单

- [ ] 已从我方获得 API Key。
- [ ] 已向我方提供公网 HTTPS Callback URL + secret（两类事件默认已开通，见 §3.4）。
- [ ] 已按 §11.3 实现验签（canonical JSON + HMAC-SHA256），联调通过。
- [ ] 已按 `session_id` / `batch_id` 幂等处理，重复投递不产生副作用。
- [ ] 已正确处理 `VOICEMAIL` / `CALL_SCREENING`（接通但非真人）。
- [ ] 业务重拨策略在贵方落地：同批用 §6.5（含 `include_completed` / `completed_reasons`），或新建批次。
- [ ] 如需固定线路，已从我方获得 `line_id`；不传则确认默认线路可用。
- [ ] 如需指定主叫，已确认 `caller_pool` 或案件级 `caller_cli`；否则确认账户默认主叫可用。
- [ ] `business_context` 符合 §5.4 格式与校验要求。
- [ ] 能下载并保存 `recording_url` / `script_url`。
- [ ] 查询接口可对账：终态案件与 Callback 一致。
