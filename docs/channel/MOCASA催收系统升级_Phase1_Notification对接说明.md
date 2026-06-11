# MOCASA Phase 1 — 通知中心对接说明（SMS + App Push）

> **版本**: v1.0 · **日期**: 2026-06-05  
> **定位**: 催收 **SMS** 与 **App Push** 经内部 **Notification 通知中心** 发送；collection-channel **不直连** QH/Hiway/BORI/FCM。  
> **API SSOT**: [notification-send-api.md](../../../AI%20collection/相关资料/notification-send-api.md)  
> **上级文档**: [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md)、[渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) §3.5  
> **代码目标**: `NotificationSmsAdapter`、`NotificationPushAdapter`（`ChannelType.SMS` / `PUSH`）  
> **语音外呼**: 仍走 LTH，见 [LTH Voice](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md)

---

## 目录

- [§0 公共约定](#0-公共约定)
- [§1 SMS（同步）](#1-sms同步)
- [§2 App Push（异步入队）](#2-app-push异步入队)
- [§3 Push fallback SMS](#3-push-fallback-sms)
- [§4 配置与代码迁移](#4-配置与代码迁移)
- [§5 联调检查清单](#5-联调检查清单)
- [附录 A：通知中心 SMS 路由参考](#附录-a通知中心-sms-路由参考运维非引擎实现)
- [附录 B：待跨团队定稿事项](#附录-b待跨团队定稿事项)

---

## 0. 公共约定

### 0.1 环境与 Base URL

| 环境 | Base URL |
|------|----------|
| 测试 | `https://service-test.mocasa.com/notification` |
| 生产 | `https://notification.mocasa.com` |

### 0.2 鉴权（所有发送接口必填）

| 字段 | 说明 |
|------|------|
| `appCode` | 调用方应用编码，由通知中心配置 |
| `dateTime` | 当前毫秒时间戳 **字符串** |
| `sign` | `MD5(appCode + appKey + dateTime)`，三字段直接拼接，无分隔符 |

```text
sign = md5("mocasa" + "<appKey>" + "1718000000000")
```

### 0.3 统一响应

```json
{ "code": 0, "msg": "success", "data": {} }
```

| code | 含义 | collection-channel 处理 |
|------|------|-------------------------|
| `0` | 接口处理成功 | 继续看 `data`（SMS 同步）或视为受理（Push 异步） |
| `51` | 无鉴权 | FAILED，非 retryable |
| `81` | 参数错误 | FAILED，非 retryable |
| `1000` | 签名错误 | FAILED，非 retryable（配置问题） |
| `2001` | 无可用账号 | FAILED，非 retryable，告警 |
| `2003` | appCode 不存在 | FAILED，非 retryable |
| `3001` | 供应商无效 | FAILED，非 retryable |

### 0.4 Nacos 配置

```yaml
channel:
  notification:
    base-url: ${NOTIFICATION_BASE_URL:https://service-test.mocasa.com/notification}
    app-code: ${NOTIFICATION_APP_CODE:mocasa}      # 催收引擎专用，须与通知中心登记一致
    app-key: ${NOTIFICATION_APP_KEY:}              # 密钥不进仓库
    sms-content-type: collection                   # 固定，对应后台 contentType
```

> **appCode 说明**：通知中心后台催收账号为 `mocasa`（`contentType=collection`）。API 文档示例中的 `mocasa_app` 为其他业务，**催收引擎使用 `mocasa`**（待运维签发正式 `appKey`，见附录 B）。

### 0.5 架构

```
StepCommand(SMS|PUSH)
  → NotificationSmsAdapter / NotificationPushAdapter
  → POST {base-url}/v1/sms/send 或 /v1/app_notification/send
  → 通知中心（账号路由、第三方协议、t_history）
  → StepResult
```

运营商路由（QH/Hiway/BORI weight）、JPush 账号选择均在 **通知中心内部** 完成，collection-channel **不实现** `OperatorResolver` 或供应商 Adapter。

---

## 1. SMS（同步）

### 1.1 接口

| 类型 | URL | Phase 1 |
|------|-----|---------|
| 同步 | `POST /v1/sms/send` | **默认** |
| 异步 | `POST /v1/sms/queue/send` | 不使用 |

### 1.2 StepCommand → 请求体

| StepCommand | 通知中心字段 |
|-------------|--------------|
| `targetAddress` | `mobile` |
| `metadata.sms_body` | `content`（1–1000 字，须已报备） |
| 固定 | `contentType = collection` |
| 鉴权 | `appCode`, `dateTime`, `sign` |

**请求示例**

```json
{
  "appCode": "mocasa",
  "dateTime": "1718000000000",
  "sign": "<md5_hex>",
  "mobile": "09171234567",
  "content": "Hi Juan, your MOCASA payment of PHP 1,500 is due. Pay: https://...",
  "contentType": "collection"
}
```

### 1.3 响应 → StepResult

同步成功示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "requestSuccess": true,
    "channel": "QHSms",
    "requestId": "sms-provider-request-id"
  }
}
```

| 条件 | success | contactResult | retryable | providerMsgId |
|------|---------|---------------|-----------|---------------|
| `code=0` 且 `data.requestSuccess=true` | true | DELIVERED | false | `data.requestId` |
| `code=0` 且 `requestSuccess=false` | false | FAILED | 视供应商错误 | — |
| 超时 / HTTP 5xx | false | FAILED | true | — |
| `code` 1000/2001/3001/81 | false | FAILED | false | — |

**metadata 回写建议**：`notification_channel`（如 `QHSms`）、`sms_content_type=collection`。

> **注意**：`code=0` 表示通知中心接口处理完成，**不等于**第三方一定送达；Phase 1 仍以 `requestSuccess=true` 作为步骤完成依据（与引擎同步渠道模型一致）。送达回执由通知中心写 `t_history`，**不**回调 collection-engine。

### 1.4 引擎行为

SMS 为 **同步渠道**：`ChannelGateway.dispatch` 成功 → `STEP_COMPLETED`（总规格 §6）。

---

## 2. App Push（异步入队）

### 2.1 接口与供应商

| 类型 | URL | 说明 |
|------|-----|------|
| 异步 | `POST /v1/app_notification/send` | **唯一**；无同步 Push 接口 |

通知中心消费队列后按 `jpush` 内容类型路由，调用 **JPush**（非 FCM HTTP v1）。

### 2.2 画像依赖

| 字段 | 来源 | 说明 |
|------|------|------|
| `userProfile.device.jpushToken` | ingestion / `t_user_equipment` | **JPush Registration ID**（非 FCM token） |

`StepResolver` 将 `jpushToken` 填入 `StepCommand.targetAddress`。

### 2.3 StepCommand → 请求体

| StepCommand / snapshot | 通知中心字段 |
|------------------------|--------------|
| `targetAddress` | `token`（多设备：英文逗号分隔，见附录 B） |
| `metadata.title` | `title` |
| `metadata.body` | `body` |
| `caseContext.repaymentUrl` 等 | `data`（**JSON object 字符串，value 必须为 string**） |
| 鉴权 | `appCode`, `dateTime`, `sign` |

**`data` 示例**（Phase 1 建议 schema，须与 App 对齐）：

```json
{
  "scene": "collection",
  "case_id": "1001",
  "deep_link": "https://app.mocasa.com/repay?bill=xxx",
  "script_slot": "S1_PUSH_STANDARD"
}
```

序列化后作为请求体中的 `data` 字符串传入。

**请求示例**

```json
{
  "appCode": "mocasa",
  "dateTime": "1718000000000",
  "sign": "<md5_hex>",
  "token": "jpush-registration-id",
  "title": "Payment reminder",
  "body": "Your payment is due tomorrow.",
  "data": "{\"scene\":\"collection\",\"case_id\":\"1001\",\"deep_link\":\"https://...\"}"
}
```

### 2.4 响应 → StepResult（入队即完成）

```json
{ "code": 0, "msg": "success" }
```

| 条件 | success | contactResult | retryable | 说明 |
|------|---------|---------------|-----------|------|
| `code=0` | true | DELIVERED | false | **入队成功 ≡ 渠道受理成功**（类比 SendGrid 202） |
| `code=81` 等参数错误 | false | FAILED | false | 可触发 §3 fallback（若 token 相关） |
| 超时 / HTTP 5xx | false | FAILED | true | — |

**Phase 1 明确不做**：

- 在同一次 `dispatch` 内等待 JPush 投递结果
- 因 JPush 最终失败而自动 fallback SMS（无效 token 多在队列消费后发现）

若业务后续需要，见附录 B（同步 token 校验 / webhook 补偿，Phase 2）。

### 2.5 无互动 / Webhook

Phase 1 **不**接入 Push 打开/点击用于条件 Email（编排 §3.5 已裁剪）。通知中心 API 文档 **未包含** 对 collection-engine 的 Webhook；timeline  enrichment 若需要，依赖通知中心 history 查询 API（待定，附录 B）。

---

## 3. Push fallback SMS

编排 §3.5 不变：同槽 Push 失败或无 token → **PushAdapter 内**改 SMS，对引擎仍是一次 `dispatch`。

```
NotificationPushAdapter.send()
  ├─ jpushToken 为空 → NotificationSmsAdapter（同槽 fallback）
  ├─ POST /v1/app_notification/send 参数错误（code=81）→ fallback（可选，按 subcode）
  └─ code=0 入队成功 → DELIVERED，不 fallback
```

| 项 | 说明 |
|----|------|
| fallback 正文 | `metadata.sms_body` 或 `metadata.fallback_sms_body` |
| idempotencyKey | `{base}:sms_fallback` |
| SMS 接口 | 同 §1，`contentType=collection` |
| 合规 | 仍计 **一次** Push 槽位触达 |

---

## 4. 配置与代码迁移

### 4.1 包结构（建议）

```
collection-channel/
  adapter/NotificationSmsAdapter.java
  adapter/NotificationPushAdapter.java
  adapter/NotificationClient.java          # 签名、HTTP、响应解析
  adapter/SendGridEmailAdapter.java
  adapter/LthVoiceAdapter.java
```

### 4.2 迁移对照

| 现状（初版） | 目标 |
|--------------|------|
| `LthSmsAdapter` | `NotificationSmsAdapter` |
| `FcmPushAdapter` + `channel.fcm.*` | `NotificationPushAdapter` + `channel.notification.*` |
| `SmsDispatchAdapter` / QH/Hiway/BORI | **删除**，路由由通知中心负责 |
| `ChannelProperties.lth.sms` | 废弃；`lth` 仅保留 `voice` |

### 4.3 幂等

通知中心 API **无** `idempotencyKey` 字段。collection-channel 侧：

- Redis `idempotency:channel:{idempotencyKey}`（Gateway 已有）
- 日志关联 `planId`、`stepOrder`、`requestId`（SMS 同步返回）

合规（静默时段、日上限）在 `ComplianceExecutionGuard`，**调用通知中心之前**执行。

---

## 5. 联调检查清单

**SMS**

- [ ] `contentType=collection`，Globe/Dito/Smart 各测一条
- [ ] `requestSuccess=true` 时 `STEP_COMPLETED`，`providerMsgId=requestId`
- [ ] `requestSuccess=false` / `2001` 步骤 FAILED，日志含 `notification_channel`
- [ ] 签名错误 `1000` 可快速定位配置

**Push**

- [ ] 有 `jpushToken`：入队 `code=0` → DELIVERED + STEP_COMPLETED
- [ ] 无 token：仅 fallback SMS，无二次 plan step
- [ ] `data` 非法 JSON → 入队前失败或 `code=81`
- [ ] App 可解析 `data.deep_link`

**通用**

- [ ] 测试/生产 `base-url`、`app-code`、`app-key` 分离
- [ ] Push fallback SMS 走 `/v1/sms/send` 同步接口

---

## 附录 A：通知中心 SMS 路由参考（运维，非引擎实现）

催收 `appCode=mocasa`、`contentType=collection` 在通知中心后台的配置（与现网一致）：

| ID | Account Name | Operator | Weight | 底层供应商文档 |
|----|--------------|----------|--------|----------------|
| 340 | QHSmsNotice | other, globe, dito | 3 | [QH SMS](../../../AI%20collection/相关资料/QH%20SMS%20接口.md) |
| 341 | HiWaySmsOther | dito, other, globe | 2 | [HiwayIO API](../../../AI%20collection/相关资料/HiwayIO-API%201.5.2.docx) |
| 339 | bori Mocasa-MKT-002 | smart | 1 | [BORI HTTP](../../../AI%20collection/相关资料/【BORI】HTTP%20对接开发文档1.0.docx) |
| — | 新 Smart 线路（测试中） | smart | TBD | 同上 |

**collection-channel 只需传 `contentType=collection`**；运营商识别与 weight 分流由通知中心完成。账号变更、新 Smart 线路灰度均在通知中心后台操作，**无需**发版催收引擎。

---

## 附录 B：待跨团队定稿事项

> 开发前建议通知中心 + App 团队 30min 对齐；定稿后更新本节并关闭对应 checklist。

| # | 议题 | Phase 1 建议 | 状态 |
|---|------|--------------|------|
| 1 | 催收引擎 `appCode` / `appKey` 正式值 | `appCode=mocasa`；测试/生产独立 key | ⏳ 待运维签发 |
| 2 | Push 入队即完成 | 产品接受：无法区分「入队但 JPush 最终失败」 | ✅ 文档已采纳 |
| 3 | 无效 JPush token | 只记失败、**不**自动 fallback SMS；无效 token 队列消费后发现 | ✅ Phase 1 |
| 4 | `data` 透传 schema | `scene`、`case_id`、`deep_link`、`script_slot`（string only） | ⏳ 待 App 确认 |
| 5 | 多设备 token | 是否逗号拼接 `token` 字段 | ⏳ 待 App / 通知中心 |
| 6 | history 对账 | SMS 存 `requestId`；是否暴露 history 查询 API | ⏳ 待通知中心 |
| 7 | 超时与重试 SLA | 5xx/超时 → retryable | ✅ 与 SMS 同步一致 |
| 8 | Smart 新线路 | 通知中心后台配置，引擎无感知 | ✅ 已确认 |
| 9 | SMS 文案报备 | 变更走通知中心/运营流程 | ✅ 已确认 |
| 10 | 同步 token 校验 API | 若 Phase 2 要强 fallback，需通知中心提供 | Phase 2 |

---

> MOCASA Collection — Phase 1 Notification Center Integration v1.0
