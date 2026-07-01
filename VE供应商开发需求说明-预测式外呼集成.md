# VE 供应商开发需求说明 — 外呼集成

催收系统与 VE 供应商的技术对接规范。

---

## 目录

1. [概述](#1-概述)
2. [架构与流程](#2-架构与流程)
3. [状态机](#3-状态机)
4. [数据约定](#4-数据约定)
5. [CDR 数据结构](#5-cdr-数据结构)
6. [HTTP API](#6-http-api)
7. [Webhook](#7-webhook)
8. [SIP / RTP / RTCP](#8-sip--rtp--rtcp)
9. [pjsua2 实现指南](#9-pjsua2-实现指南)
10. [开发清单与验收](#10-开发清单与验收)

---

## 1. 概述

我方通过 HTTP 下发外呼批次（话术、策略、号码），VE 负责入库、调度与 SIP 外呼。通话结果经 **Webhook** 回传（同一 `webhook_url`，两种事件）：


| 事件                   | 时机                    |
| -------------------- | --------------------- |
| `call.completed`     | 单通 ENDED，即时推送 CDR     |
| `campaign.completed` | 整批全部 ENDED，推送 summary |


Webhook **必达**（at-least-once + 失败重试）；我方按 `session_id` / `campaign_id` 去重落库。

### 1.1 接口一览


| 接口                           | 方法        | 功能描述                                 |
| ---------------------------- | --------- | ------------------------------------ |
| `/v1/tenant/config`          | PUT / GET | 租户配置：Webhook、LTH、VE 网络参数、默认主叫/语言     |
| `/v1/campaigns/start-dial`   | POST      | 启动外呼批次：话术、拨号策略、号码、话术变量；接单即 `RUNNING` |
| Webhook `call.completed`     | POST      | 单通结束必达推送（载荷同 §5 CDR）                 |
| Webhook `campaign.completed` | POST      | 整批完结必达推送                             |


### 1.2 三方分工


| 方            | 职责                                           |
| ------------ | -------------------------------------------- |
| **催收系统（我方）** | 发送外呼批次（HTTP）；接收 Webhook、落 CDR；不执行调度、不发 SIP   |
| **VE（贵方）**   | 外呼调度、SIP/RTP 外呼、AI 对话、振铃超时、录音转写、Webhook 必达投递 |
| **LTH（线路商）** | SIP 落地、媒体桥接（与 VE 联调）                         |


---

## 2. 架构与流程

### 2.1 拓扑

```text
催收系统 ──POST start-dial（话术+策略+号码，一次下发）──► VE
         ◄──Webhook call.completed / campaign.completed（必达）
                              │
                    VE 内部：调度 + INVITE/CANCEL/BYE
                              │
                              ▼
                            LTH ──► 被叫
```

### 2.2 日流程

```text
PUT  /v1/tenant/config
POST /v1/campaigns/start-dial             → RUNNING（接单即启动调度）

并行运行直至 COMPLETED：
  · Webhook call.completed     → 按 session_id 即时落库
  · Webhook campaign.completed → 标记批次完结，核对 summary
```

### 2.3 `start-dial` 请求结构

一次 HTTP 请求携带本批外呼所需的全部字段：


| 类别   | 字段                   | 说明                                                          |
| ---- | -------------------- | ----------------------------------------------------------- |
| 批次标识 | `campaign_id`        | 批次去重、`campaign.completed` 主键                                |
| 话术   | `script_id`、`locale` | 批次级；VE 选用 AI 模板                                             |
| 拨号策略 | `dial_policy`        | 时段、工作日、振铃超时                                                 |
| 单通   | `calls[]`            | `session_id`、号码、`vars`、可选 `caller_cli` / `earliest_dial_at` |


VE 接单入库后直接进入 `RUNNING`，按 `dial_policy` 窗口执行外呼。

---

## 3. 状态机

### 3.1 Campaign

```text
                 start-dial（仅一次）
                        │
                        ▼
                 ┌─────────────┐
          ┌──────│   RUNNING   │  外呼进行中
   入库失败│      └──────┬──────┘
          │             │ 本批全部 session → ENDED
          │             ▼
          │      ┌─────────────┐
          └──────│  COMPLETED  │  推送 campaign.completed
                 └─────────────┘
```

- `start-dial` 同步响应 `ACCEPTED` 表示 **批次已入库且调度已启动**（非「全部已拨完」）。
- 部分 `calls[]` 校验失败时：其余 session 正常入库并调度；失败项列入 `rejected_session_ids`。
- 整批拒绝（如 `CAMPAIGN_ALREADY_STARTED` / `CAMPAIGN_ALREADY_COMPLETED`）时批次不入库。

### 3.2 Session

```text
PENDING ──调度──► DIALING ──180──► RINGING
                    │              │
            4xx/6xx │    超时 CANCEL
                    │              │
                    ▼              ▼
                  ENDED ◄────── ENDED
                    ▲
RINGING ──200OK──► ACTIVE ──AI结束/被叫挂/BYE──► ENDED
                      │
              VE资源不足 BYE (ABANDON)
```

- 验收通过的 `calls[]` 入库后为 **PENDING**，由 VE 在 `dial_policy` 窗口内调度发起 INVITE。
- `earliest_dial_at`（可选）：未到时间保持 PENDING，不参与调度。

`ENDED` 时：推送 `call.completed`（必达）。

---

## 4. 数据约定


| 项   | 约定                                             |
| --- | ---------------------------------------------- |
| 协议  | HTTPS + JSON                                   |
| 时间  | ISO 8601 **UTC+8**：`YYYY-MM-DDTHH:mm:ss+08:00` |
| 号码  | **以LTH格式为准**                                   |


### 4.1 关联 ID


| 字段            | 生成方 | 用途                                   |
| ------------- | --- | ------------------------------------ |
| `campaign_id` | 我方  | 批次 ID；`campaign.completed` 去重        |
| `session_id`  | 我方  | **单通唯一键**；CDR 主键；`call.completed` 去重 |


鉴权、Webhook 签名等联调细节双方另行约定。

---

## 5. CDR 数据结构

Webhook `call.completed` 载荷 = `event` + `timestamp` + **本结构全部字段**（见 §7）。

```json
{
  "session_id": "550e8400-e29b-41d4-a716-446655440001",    // 我方单通 ID，落库主键
  "campaign_id": "20260610-PH",                          // 我方批次 ID

  "parties": {
    "callee_e164": "+639171234567",                        // 被叫
    "caller_cli": "+639912345678"                          // 外显主叫
  },

  "outcome": {
    "reason": "NO_ANSWER",                                 // 终态原因，见下表
    "ptp": null,                                           // 还款承诺（Promise To Pay）；未达成 null，见下表
    "was_ringing": true,                                   // 是否曾 180/183
    "was_answered": false,                                 // 是否 200 OK + ACK
    "was_ai_connected": false                              // AI 是否开口
  },

  "timeline": {
    "dialed_at": "2026-06-10T14:30:01+08:00",              // INVITE 发出
    "ringing_at": "2026-06-10T14:30:03+08:00",             // 180；未振铃 null
    "answered_at": null,                                   // 200 OK；未接通 null
    "ai_started_at": null,                                   // AI 开口；未对话 null
    "ended_at": "2026-06-10T14:30:50+08:00"                // 拆线
  },

  "metrics": {
    "ring_duration_sec": 47,                                 // ringing_at → ended_at
    "talk_duration_sec": 0,                                  // ai_started_at → ended_at
    "total_duration_sec": 49                                 // dialed_at → ended_at
  },

  "media": {
    "recording_url": null,                                   // 录音 URL；接通后有值
    "transcript_url": null                                   // 转写 URL
  },

  "ai": {
    "script_id": "collection_v3",                          // 回显 start-dial 话术 ID
    "locale": "fil-PH",                                    // 回显语言
    "vars": {                                              // 回显 start-dial calls[].vars
      "borrower_name": "Maria Santos",
      "amount_due": "1500.00"
    }
  }
}
```

**outcome.reason**


| reason          | 含义                                                  |
| --------------- | --------------------------------------------------- |
| `NORMAL`        | AI 道别挂机                                             |
| `CALLEE_HANGUP` | 被叫先挂                                                |
| `NO_ANSWER`     | 振铃超时（VE 按 `dial_policy.ring_timeout_sec` 自动 CANCEL） |
| `BUSY`          | 被叫忙                                                 |
| `REJECTED`      | 拒接/不可及                                              |
| `ABANDON`       | 已接通但 VE 因内部资源限制主动挂机（如 AI 容量不足）                      |
| `FAILED`        | 线路/内部错误                                             |


**outcome.ptp**（VE 从 AI 对话抽取；未接通或未承诺时为 `null`）


| 字段              | 类型     | 说明                              |
| --------------- | ------ | ------------------------------- |
| `repay_date`    | string | 承诺还款日，`+08:00`                  |
| `repay_amount`  | string | 承诺金额（十进制字符串，与 `amount_due` 同精度） |
| `repay_channel` | string | 还款渠道，如 `gcash`、`bank`；未知可省略     |


示例（借款人承诺还款）：

```json
"ptp": {
  "repay_date": "2026-06-15T00:00:00+08:00",
  "repay_amount": "1500.00",
  "repay_channel": "gcash"
}
```

---

## 6. HTTP API

### 6.1 `PUT /v1/tenant/config`

```json
{
  "webhook_url": "https://collection.example.com/webhooks/ve",  // 必填
  "webhook_secret": "whsec_xxxxxxxxxxxxxxxx",                    // 必填；验签方式联调约定

  "lth": {
    "sip_host": "198.51.100.20",    // LTH SIP 服务器
    "sip_port": 5060                 // 默认 5060
    // 联调前须将 sip.public_ip 提交 LTH 加 SIP/RTP 白名单（见 §9.0）
  },
  "sip": {
    "public_ip": "203.0.113.10",   // VE 公网 IP；SDP c= 行；LTH 白名单
    "listen_port": 5060,            // VE SIP 端口，默认 5060
    "transport": "udp"
  },
  "rtp": {
    "port_min": 10000,               // RTP 端口池起始（含）
    "port_max": 20000                // RTP 端口池结束（不含）
  },
  "defaults": {
    "caller_cli": "+639912345678",  // start-dial 省略主叫时的默认值
    "locale": "fil-PH"
  }
}
```

---

### 6.2 `POST /v1/campaigns/start-dial`

一次请求下发本批全部信息；VE 入库后进入 `RUNNING` 并开始外呼。每个 `campaign_id` 仅可调用一次——重复时，RUNNING 返回 `CAMPAIGN_ALREADY_STARTED`，COMPLETED 返回 `CAMPAIGN_ALREADY_COMPLETED`。补拨须使用新 `campaign_id`。

**请求**

```json
{
  "campaign_id": "20260610-PH",         // 本批唯一 ID
  "script_id": "collection_v3",         // VE 话术模板
  "locale": "fil-PH",                   // AI 语言；可省略，用 tenant defaults.locale

  "dial_policy": {
    "timezone": "Asia/Shanghai",        // UTC+8
    "windows": [                        // 每日可外呼时段（本地 HH:mm）
      { "start_time": "09:00", "end_time": "12:00" },
      { "start_time": "14:00", "end_time": "20:00" }
    ],
    "weekdays": [1, 2, 3, 4, 5],      // 1=周一 … 7=周日
    "ring_timeout_sec": 45              // 振铃超时秒数，VE 自动 CANCEL
  },

  "calls": [
    {
      "session_id": "550e8400-e29b-41d4-a716-446655440001",
      "callee_e164": "+639171234567",
      "caller_cli": "+639912345678",   // 可省略，用 tenant defaults.caller_cli
      "vars": {
        "borrower_name": "Maria Santos",
        "amount_due": "1500.00"
      }
    },
    {
      "session_id": "550e8400-e29b-41d4-a716-446655440002",
      "callee_e164": "+639181234567",
      "earliest_dial_at": "2026-06-10T10:00:00+08:00",  // 可选；最早拨号时间
      "vars": {
        "borrower_name": "Juan Dela Cruz",
        "amount_due": "2300.50"
      }
    }
  ]
}
```

`**dial_policy` 字段**


| 字段                 | 类型      | 必填  | 说明                                            |
| ------------------ | ------- | --- | --------------------------------------------- |
| `timezone`         | string  | 是   | IANA 时区，如 `Asia/Shanghai`（UTC+8）              |
| `windows`          | array   | 是   | 每日可外呼时段，`start_time` / `end_time` 为本地 `HH:mm` |
| `weekdays`         | array   | 是   | 可外呼星期，`1`=周一 … `7`=周日                         |
| `ring_timeout_sec` | integer | 是   | 单通振铃超时（秒），超时后 VE 发 CANCEL                     |


外呼并发（同时振铃路数、AI 对话上限等）由 VE 按实际资源自行控制，不在本接口下发。

**响应**

```json
{
  "campaign_id": "20260610-PH",
  "state": "ACCEPTED",               // ACCEPTED | REJECTED（整批拒绝时）
  "accepted": 1,
  "rejected": 1,
  "rejected_session_ids": [          // rejected > 0 时必填
    "550e8400-e29b-41d4-a716-446655440002"
  ],
  "errors": [                        // 逐条失败明细
    {
      "session_id": "550e8400-e29b-41d4-a716-446655440002",
      "error_code": "INVALID_VARS",
      "message": "amount_due missing"
    }
  ]
}
```

`rejected_session_ids` 列出本批 **未入库** 的 `session_id`，与 `errors[].session_id` 一一对应。`accepted > 0` 时 VE 须已启动调度（`RUNNING`），即使存在部分拒绝。

`start-dial` 在 `dial_policy.windows` 外调用时，VE **接单后等待窗口开启再拨**（不另返回下次开窗时间）。


| 整批拒绝                         | 含义                                            |
| ---------------------------- | --------------------------------------------- |
| `CAMPAIGN_ALREADY_STARTED`   | 同 `campaign_id` 重复 `start-dial`，批次处于 RUNNING  |
| `CAMPAIGN_ALREADY_COMPLETED` | 同 `campaign_id` 重复 `start-dial`，批次已 COMPLETED |
| `EMPTY_CALLS`                | `calls` 为空                                    |
| `INVALID_DIAL_POLICY`        | `dial_policy` 格式非法，或时区/窗口/工作日/振铃超时参数无效        |
| `INVALID_SCRIPT`             | `script_id` 不存在                               |



| 逐条拒绝                | 含义                    |
| ------------------- | --------------------- |
| `INVALID_E164`      | 号码格式错误                |
| `INVALID_VARS`      | vars 缺必填或格式错误         |
| `DUPLICATE_SESSION` | calls 内 session_id 重复 |


**VE 内部调度**

```text
RUNNING 期间，在 dial_policy.windows 内：
  while 仍有外呼容量 and 有待拨 PENDING:
    发 INVITE
单通 ENDED → call.completed（必达）
全部 ENDED → COMPLETED + campaign.completed（必达）
```

---

## 7. Webhook

通话结果的**唯一**回传通道，须 at-least-once 投递并失败重试。载荷与 §5 对齐，按 `session_id` / `campaign_id` 去重。


| 事件                   | 触发         | 去重键           |
| -------------------- | ---------- | ------------- |
| `call.completed`     | 单通 ENDED   | `session_id`  |
| `campaign.completed` | 整批全部 ENDED | `campaign_id` |


### 7.1 `call.completed`

= `event` + `timestamp` + **§5 CDR 全部字段**。

```json
{
  "event": "call.completed",
  "timestamp": "2026-06-10T14:30:50+08:00",              // 推送时间 UTC+8

  "session_id": "550e8400-e29b-41d4-a716-446655440001",
  "campaign_id": "20260610-PH",
  "parties": { "callee_e164": "+639171234567", "caller_cli": "+639912345678" },
  "outcome": { "reason": "NO_ANSWER", "ptp": null, "was_ringing": true, "was_answered": false, "was_ai_connected": false },
  "timeline": { "dialed_at": "2026-06-10T14:30:01+08:00", "ringing_at": "2026-06-10T14:30:03+08:00", "answered_at": null, "ai_started_at": null, "ended_at": "2026-06-10T14:30:50+08:00" },
  "metrics": { "ring_duration_sec": 47, "talk_duration_sec": 0, "total_duration_sec": 49 },
  "media": { "recording_url": null, "transcript_url": null },
  "ai": { "script_id": "collection_v3", "locale": "fil-PH", "vars": { "borrower_name": "Maria Santos", "amount_due": "1500.00" } }
}
```

> 各字段含义见 §5 注释。

### 7.2 `campaign.completed`

```json
{
  "event": "campaign.completed",
  "timestamp": "2026-06-10T18:00:00+08:00",

  "campaign_id": "20260610-PH",

  "summary": {                           // 整批统计
    "connected": 185,
    "no_answer": 520,
    "busy": 80,
    "rejected": 45,
    "abandon": 12,
    "failed": 8,
    "other": 150
  },
  "duration_sec": 14400
}
```

---

## 8. SIP / RTP / RTCP

> VE ↔ LTH 联调；我方不参与。


| 用途      | 协议  | 端口                                |
| ------- | --- | --------------------------------- |
| SIP 信令  | UDP | 默认 **5060**（`sip.listen_port` 可改） |
| RTP 媒体  | UDP | **10000–19999**，每通动态分配            |
| RTCP 控制 | UDP | **RTP 端口 + 1**（与 RTP 成对，见下）       |


### 8.1 信令与媒体流程

```text
被叫 ◄──PSTN──► LTH ◄──RTP/PCMA──► VE ◄──PCM──► AI

信令：INVITE → 180 → 200 OK → ACK → RTP 双向 → BYE
      振铃超时：CANCEL → 487
编解码：PCMA（PT=8），无转码
SDP Offer：c=VE_PUBLIC_IP，m=audio <RTP端口> RTP/AVP 8
```


| 报文              | 方向       | 场景                                   |
| --------------- | -------- | ------------------------------------ |
| INVITE          | VE → LTH | 外呼；Offer SDP 含 VE 公网 IP + RTP 端口     |
| 180 / 183       | LTH → VE | 振铃                                   |
| 200 OK          | LTH → VE | 接听 + Answer SDP                      |
| ACK             | VE → LTH | 200 OK 之后（pjsua2 自动）                 |
| CANCEL          | VE → LTH | 振铃超时（`dial_policy.ring_timeout_sec`） |
| 487             | LTH → VE | 取消确认                                 |
| BYE             | 双向       | 挂机                                   |
| 4xx / 5xx / 6xx | LTH → VE | 拒接 / 错误                              |


---

## 9. pjsua2 实现指南（Python）

以下为 VE 侧参考实现（Python 3 + `pjsua2`）。催收仅发 HTTP，批次入库、调度、INVITE、AI、Webhook 均由 VE 执行。

### 9.0 模块总览

pjsua2 集成的 5 个核心模块（配置、Webhook 客户端等辅助模块从略）：


| 模块       | 文件                                    | 作用                                                                                        |
| -------- | ------------------------------------- | ----------------------------------------------------------------------------------------- |
| **协议栈**  | `pjsua/stack.py`                      | 进程内唯一的 `Endpoint`：初始化 SIP 传输、RTP 端口池、PCMA 编解码；在专用线程循环 `libHandleEvents()`，驱动全部 SIP 定时器与回调 |
| **账户**   | `pjsua/account.py`                    | 定义 VE 的 SIP 身份（UAC）；拒接意外入局，避免占用外呼资源                                                       |
| **外呼**   | `pjsua/call.py` + `pjsua/outbound.py` | `VeCall` 接收信令/媒体回调；`place_call()` 组装 INVITE（含 PAI）并 `makeCall`                            |
| **会话管理** | `call_manager.py`                     | `session_id` ↔ `VeCall` 映射；SIP 状态 → 业务状态机；回调线程与工作线程解耦；振铃超时；Webhook 入队                     |
| **外呼调度** | `scheduler.py`                        | 在 `dial_policy` 窗口内按 VE 容量策略循环 `place_call`                                               |


**线程约束（必须遵守）**：

- `libHandleEvents()` 只在 **pjsua2 专用线程** 中循环调用。
- `onCallState` / `onCallMediaState` 等回调运行在 pjsua2 线程，**禁止** 做 HTTP、写库、AI 推理等耗时操作——仅将事件入队，由 `CallManager.worker_loop` 在工作线程处理。

```text
ve/
├── config.py              # TenantConfig（略）
├── pjsua/
│   ├── stack.py           # ← 9.1
│   ├── account.py         # ← 9.2
│   ├── call.py            # ← 9.3
│   └── outbound.py        # ← 9.3
├── call_manager.py        # ← 9.4
└── scheduler.py           # ← 9.5
```

### 9.1 协议栈 `pjsua/stack.py`

**模块作用**：整个 VE 进程的 SIP/RTP 运行时入口。负责创建全局 `Endpoint`、绑定 UDP 5060、配置 RTP 端口池与公网 IP（写入 SDP `c=` 行）、设置 PCMA 为唯一音频编解码，并启动事件循环线程。所有 `Account` / `Call` 都依赖此栈已 `libStart()`。

```python
# pjsua/stack.py
import threading
import pjsua2 as pj
from config import TenantConfig


class PjStack:
    """
    全局唯一 Endpoint 封装。
    生命周期：start() → 业务运行 → stop()。
    """

    _ep: pj.Endpoint | None = None
    _running = False

    @classmethod
    def start(cls, cfg: TenantConfig) -> pj.Endpoint:
        # ── 1. 创建并初始化 Endpoint ──────────────────────────────
        ep = pj.Endpoint()
        ep.libCreate()  # 分配底层 PJSIP 资源，尚未开始收发

        ep_cfg = pj.EpConfig()
        # maxCalls 按 VE 容量规划设置，须覆盖峰值外呼并发
        ep_cfg.uaConfig.maxCalls = 512

        # ── 2. 媒体配置：决定 Offer SDP 中的 IP / 端口 / 采样率 ───
        med = pj.MediaConfig()
        med.clockRate = 8000                      # PCMA 固定 8 kHz
        med.port = cfg.rtp_port_min               # RTP 端口池起始（偶数端口）
        med.portRange = cfg.rtp_port_max - cfg.rtp_port_min  # 池大小；RTCP= RTP+1 自动
        med.publicAddress = cfg.ve_public_ip      # 写入 SDP c=IN IP4 <此地址>
        ep_cfg.medConfig = med

        ep.libInit(ep_cfg)  # 应用配置，仍无网络活动

        # ── 3. SIP 传输：VE 监听 UDP 5060，对外 Via/Contact 用公网 IP ──
        tp = pj.TransportConfig()
        tp.port = cfg.sip_listen_port             # 通常 5060
        tp.publicAddress = cfg.ve_public_ip       # NAT 场景下修正 Via 源地址
        ep.transportCreate(pj.PJSIP_TRANSPORT_UDP, tp)

        # ── 4. 编解码：仅 PCMA/8000，与 LTH 约定一致 ───────────────
        ep.codecSetPriority("PCMA/8000", 255)     # 最高优先级
        ep.codecSetPriority("PCMU/8000", 0)       # 禁用 G.711 μ-law

        ep.libStart()  # 开始收发 SIP；此后可 create Account / makeCall

        cls._ep, cls._running = ep, True
        # 专用线程驱动定时器与回调；主线程不得调用 libHandleEvents
        threading.Thread(
            target=cls._event_loop, daemon=True, name="pjsua2"
        ).start()
        return ep

    @classmethod
    def _event_loop(cls) -> None:
        """阻塞式事件泵：超时 50ms，兼顾响应性与 CPU 占用。"""
        while cls._running and cls._ep:
            cls._ep.libHandleEvents(50)

    @classmethod
    def stop(cls) -> None:
        cls._running = False
        if cls._ep:
            cls._ep.libDestroy()  # 挂断所有通话并释放端口
            cls._ep = None
```

### 9.2 账户 `pjsua/account.py`

**模块作用**：在协议栈上注册 VE 的 SIP 身份。本方案 VE 仅作主叫（UAC），LTH 采用 IP 白名单时通常 **无需 REGISTER**。若 LTH 意外向 VE 发 INVITE（入局），统一回 **486 Busy Here**，避免占用外呼资源。

```python
# pjsua/account.py
import pjsua2 as pj
from config import TenantConfig


class VeAccount(pj.Account):
    """VE SIP 账户；本方案只出站、不入站。"""

    def __init__(self, cfg: TenantConfig):
        super().__init__()
        acfg = pj.AccountConfig()
        # idUri 出现在默认 From；实际外呼时 outbound 会覆盖 From 为 caller_cli
        acfg.idUri = f"sip:ve@{cfg.ve_public_ip}"
        # LTH 白名单模式：不注册；若 LTH 要求 Digest，改为 True 并填 regUri/cred
        acfg.regConfig.registerOnAccAdd = False
        self.create(acfg)

    def onIncomingCall(self, prm: pj.OnIncomingCallParam) -> None:
        """
        入局回调。本方案无入局外呼业务，必须快速拒接。
        prm.callId 由协议栈分配，须用其构造 Call 对象才能 answer。
        """
        call = pj.Call(self, prm.callId)
        op = pj.CallOpParam(True)
        op.statusCode = pj.PJSIP_SC_BUSY_HERE  # SIP 486
        call.answer(op)
```

### 9.3 外呼 `pjsua/call.py` + `pjsua/outbound.py`

**模块作用**：`VeCall` 是单路外呼的 SIP 句柄，在 pjsua2 回调中把信令/媒体事件转发给 `CallManager`（绝不阻塞回调线程）。`place_call()` 是外呼唯一入口：注册会话 → 设置 SIP 头（From、P-Asserted-Identity）→ `makeCall` 发出 INVITE → 启动振铃超时定时器。

```python
# pjsua/call.py
import pjsua2 as pj
from call_manager import CallManager


class VeCall(pj.Call):
    """
    单路外呼实例。session_id 与催收 HTTP 下发的 ID 一一对应，
    贯穿 SIP Call-ID、RTP 端口、Webhook 全链路。
    """

    def __init__(self, account: pj.Account, session_id: str):
        super().__init__(account)
        self.session_id = session_id

    def onCallState(self, prm: pj.OnCallStateParam) -> None:
        """
        信令状态变化：CALLING → EARLY(180) → CONFIRMED(200) → DISCONNECTED。
        运行在 pjsua2 线程：只做 getInfo + 入队，禁止 I/O。
        """
        ci = self.getInfo()
        CallManager.enqueue_state(self.session_id, ci)

    def onCallMediaState(self, prm: pj.OnCallMediaStateParam) -> None:
        """
        媒体就绪：200 OK 交换 SDP 后，音频流 ACTIVE。
        此时可拿到 AudioMedia，用于桥接 AI TTS/ASR。
        """
        ci = self.getInfo()
        for idx, mi in enumerate(ci.media):
            if (
                mi.type == pj.PJMEDIA_TYPE_AUDIO
                and mi.status == pj.PJSUA_CALL_MEDIA_ACTIVE
            ):
                aud = pj.AudioMedia.typecastFromMedia(self.getMedia(idx))
                CallManager.enqueue_media(self.session_id, aud)

    def hangup_call(self) -> None:
        """
        统一挂机入口：
        - EARLY（振铃中）→ 协议栈发 CANCEL
        - CONFIRMED（已接通）→ 协议栈发 BYE
        """
        op = pj.CallOpParam()
        pj.Call.hangup(self, op)
```

```python
# pjsua/outbound.py
import pjsua2 as pj
from config import TenantConfig
from pjsua.call import VeCall
from call_manager import CallManager


def place_call(
    account: pj.Account,
    session_id: str,
    callee_e164: str,
    caller_cli: str,
    cfg: TenantConfig,
    ring_timeout_sec: int,
    vars: dict | None = None,
) -> VeCall:
    """
    向 LTH 发起 INVITE。
    参数来源：session_id / callee / caller_cli / vars ← start-dial calls[]；cfg ← tenant/config。
    """
    call = VeCall(account, session_id)
    # 必须先注册到 CallManager，否则 onCallState 找不到 session
    CallManager.register_call(session_id, call, vars=vars)

    prm = pj.CallOpParam(True)   # True = 使用默认通话参数
    prm.opt.audioCount = 1       # Offer 中带 1 路音频 m= 行
    prm.opt.videoCount = 0       # 纯语音，无视频

    # LTH 通常校验 PAI 与主叫号码；From 用 VE 公网 IP，PAI 用 LTH 域
    headers = [
        ("From", f"<sip:{caller_cli}@{cfg.ve_public_ip}>"),
        ("P-Asserted-Identity", f"<sip:{caller_cli}@{cfg.lth_sip_host}>"),
    ]
    for name, value in headers:
        h = pj.SipHeader()
        h.hName, h.hValue = name, value
        prm.txOption.headers.append(h)

    # Request-URI 指向 LTH；被叫号码 E.164 格式
    dest_uri = f"sip:{callee_e164}@{cfg.lth_sip_host}:{cfg.lth_sip_port}"
    call.makeCall(dest_uri, prm)  # ← 异步发出 INVITE；结果经 onCallState 回调

    # 振铃超时：取自 start-dial dial_policy.ring_timeout_sec
    CallManager.schedule_ring_timeout(session_id, timeout_sec=ring_timeout_sec)
    return call
```

### 9.4 会话管理 `call_manager.py`

**模块作用**：业务层中枢。维护 `session_id → VeCall` 内存表；将 pjsua2 回调线程与工作线程解耦（`queue`）；把 SIP 状态映射为 `DIALING / RINGING / ACTIVE` 并记录 `timeline`；通话结束时组装 `call.completed` Webhook；提供振铃超时与 AI 资源检查的挂接点。

```python
# call_manager.py
import queue
import threading
from datetime import datetime, timezone, timedelta
import pjsua2 as pj

UTC8 = timezone(timedelta(hours=8))

# session_id → { "call": VeCall, "state": str, "timeline": dict, "vars": dict }
_sessions: dict[str, dict] = {}
# 事件队列：(kind, session_id, payload)；kind ∈ {"state", "media"}
_event_q: queue.Queue = queue.Queue()


def now_utc8() -> str:
    return datetime.now(UTC8).strftime("%Y-%m-%dT%H:%M:%S+08:00")


def register_call(session_id: str, call: pj.Call, vars: dict | None = None) -> None:
    """place_call 在 makeCall 之前调用，确保首条 onCallState 可查到会话。"""
    _sessions[session_id] = {
        "call": call,
        "state": "DIALING",
        "timeline": {},
        "vars": vars or {},   # 来自 start-dial calls[].vars，CDR ai.vars 回显
    }


def enqueue_state(session_id: str, ci: pj.CallInfo) -> None:
    """由 VeCall.onCallState 调用（pjsua2 线程）。"""
    _event_q.put(("state", session_id, ci))


def enqueue_media(session_id: str, aud: pj.AudioMedia) -> None:
    """由 VeCall.onCallMediaState 调用（pjsua2 线程）。"""
    _event_q.put(("media", session_id, aud))


def _map_sip_to_reason(ci: pj.CallInfo) -> str:
    """
    将 SIP 响应码映射为 Webhook outcome.reason。
    催收侧不解析 SIP，只认 reason 枚举。
    """
    code = ci.lastStatusCode
    if code == 486:
        return "BUSY"
    if code in (403, 404, 480, 603):
        return "REJECTED"
    if code == 487:
        return "NO_ANSWER"   # CANCEL 或振铃超时
    if ci.state == pj.PJSIP_INV_STATE_DISCONNECTED and code // 100 >= 4:
        return "FAILED"
    return "NORMAL"          # 正常挂机（BYE）


def _on_state(session_id: str, ci: pj.CallInfo) -> None:
    """工作线程：处理信令状态，更新 timeline 与业务 state。"""
    sess = _sessions.get(session_id)
    if not sess:
        return

    st = ci.state
    if st == pj.PJSIP_INV_STATE_CALLING:
        sess["timeline"]["dialed_at"] = now_utc8()
        sess["state"] = "DIALING"
    elif st == pj.PJSIP_INV_STATE_EARLY and ci.lastStatusCode in (180, 183):
        sess["timeline"]["ringing_at"] = now_utc8()
        sess["state"] = "RINGING"
    elif st == pj.PJSIP_INV_STATE_CONFIRMED:
        # 200 OK 已收到，pjsua2 自动发 ACK；RTP 即将或已经 ACTIVE
        sess["timeline"]["answered_at"] = now_utc8()
        sess["state"] = "ACTIVE"
    elif st == pj.PJSIP_INV_STATE_DISCONNECTED:
        sess["timeline"]["ended_at"] = now_utc8()
        reason = _map_sip_to_reason(ci)
        ptp = None  # TODO: ai_engine.extract_ptp(session_id)
        webhook_enqueue("call.completed", {
            "session_id": session_id,
            "outcome": {
                "reason": reason,
                "ptp": ptp,
                "was_ringing": "ringing_at" in sess["timeline"],
                "was_answered": "answered_at" in sess["timeline"],
                "was_ai_connected": "ai_started_at" in sess["timeline"],
            },
            "timeline": sess["timeline"],
        })
        _sessions.pop(session_id, None)  # 释放 VeCall 与 RTP 端口


def _on_media(session_id: str, aud: pj.AudioMedia) -> None:
    """工作线程：音频 ACTIVE 后桥接 AI；VE 资源不足则立即挂机（ABANDON）。"""
    sess = _sessions.get(session_id)
    if not sess:
        return
    # TODO: if not ai_pool.can_accept(): sess["call"].hangup_call(); reason=ABANDON
    # TODO: ai_bridge.attach(session_id, aud)  # 双向 PCM ↔ AI
    sess["timeline"]["ai_started_at"] = now_utc8()
    sess["state"] = "ACTIVE"


def worker_loop() -> None:
    """应用启动时单独线程运行；与 pjsua2 事件线程完全解耦。"""
    while True:
        kind, session_id, payload = _event_q.get()
        if kind == "state":
            _on_state(session_id, payload)
        elif kind == "media":
            _on_media(session_id, payload)


def schedule_ring_timeout(session_id: str, timeout_sec: int) -> None:
    """非阻塞定时器；超时且仍在 DIALING/RINGING 则 CANCEL。"""
    def _fire():
        sess = _sessions.get(session_id)
        if sess and sess["state"] in ("DIALING", "RINGING"):
            sess["call"].hangup_call()
    threading.Timer(timeout_sec, _fire).start()


def webhook_enqueue(event: str, payload: dict) -> None:
    # TODO: 投递到 webhook_client（异步 HTTP + 指数退避重试，必达）
    pass
```

### 9.5 外呼调度 `scheduler.py`

**模块作用**：VE 内部的拨号引擎（与催收 HTTP 解耦）。`start-dial` 入库后 campaign 即 `RUNNING`；每个 tick 检查拨号窗口与 VE 内部容量，从 PENDING 队列取 session 并 `place_call`。

```python
# scheduler.py
from pjsua.outbound import place_call


def scheduler_tick(campaign, account, tenant_cfg) -> None:
    """
    单次调度心跳（可由 asyncio / APScheduler / 独立线程周期性调用）。

    campaign：内存批次对象，含 dial_policy（windows 等）与 pending sessions。
    仅在拨号窗口内、且 VE 判定仍有外呼容量时发起新 INVITE。
    """
    if not campaign.in_dial_window():
        return

    while campaign.can_place_more_calls() and campaign.has_pending():
        sess = campaign.next_pending_session()  # 跳过未到 earliest_dial_at 的 session
        place_call(
            account=account,
            session_id=sess["session_id"],
            callee_e164=sess["callee_e164"],
            caller_cli=sess.get("caller_cli") or tenant_cfg.default_caller_cli,
            cfg=tenant_cfg,
            ring_timeout_sec=campaign.dial_policy["ring_timeout_sec"],
            vars=sess.get("vars"),
        )
```

### 9.6 抓包：INVITE 长什么样

`makeCall(dest_uri, prm)` 发出后，Wireshark 可见（Offer SDP 由 pjsua2 按 `MediaConfig` 自动生成）：

```text
INVITE sip:+639171234567@198.51.100.20 SIP/2.0
Via: SIP/2.0/UDP 203.0.113.10:5060;branch=...
From: <sip:+639912345678@203.0.113.10>;tag=...
To: <sip:+639171234567@198.51.100.20>
Call-ID: ...
CSeq: 1 INVITE
P-Asserted-Identity: <sip:+639912345678@198.51.100.20>
Content-Type: application/sdp

v=0
c=IN IP4 203.0.113.10
m=audio 10042 RTP/AVP 8       ← RTP 端口；RTCP 自动为 10043
a=rtpmap:8 PCMA/8000
a=sendrecv
```

### 9.7 信令时序（VE 须覆盖的分支）

```text
place_call() → INVITE
  → 180 Ringing        onCallState(EARLY)     记 ringing_at
  → 200 OK + SDP       onCallState(CONFIRMED) pjsua2 自动 ACK；记 answered_at
  → onCallMediaState   RTP ACTIVE             挂 AI；记 ai_started_at
  → BYE                DISCONNECTED           Webhook call.completed

异常：
  4xx/6xx → DISCONNECTED → outcome.reason = BUSY / REJECTED / FAILED
  振铃超时 → hangup_call() → CANCEL → 487 → NO_ANSWER
  VE 资源不足 → hangup_call() → ABANDON
```

---

## 10. 开发清单与验收


| #   | 交付                                                 |
| --- | -------------------------------------------------- |
| 1   | `PUT/GET tenant/config`（webhook 必填）                |
| 2   | `POST start-dial`（每 `campaign_id` 仅可调用一次）          |
| 3   | 外呼调度 + 振铃超时自动 CANCEL                               |
| 4   | Webhook 必达：`call.completed` + `campaign.completed` |
| 5   | pjsua2：INVITE/CANCEL/BYE + RTP/RTCP/PCMA           |
| 6   | 录音转写 URL；`outcome.ptp` 从对话抽取                       |


