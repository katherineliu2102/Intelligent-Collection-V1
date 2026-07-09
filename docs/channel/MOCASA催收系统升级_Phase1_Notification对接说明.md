# MOCASA Phase 1 — 通知中心对接说明（SMS + App Push）

> **版本**: v1.1  
> **日期**: 2026-06-11  
> **范围**: 仅覆盖菲律宾市场  
> **模块**: `collection-channel`  
> **关联文档**: [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md)、[渠道编排规格 §3.5](./MOCASA催收系统升级_Phase1_渠道编排规格.md#35-phase-1-实现范围)、[notification-send-api.md](./reference/notification-send-api.md)、[LTH Voice](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md)

---

## 目录

- [§0 公共约定](#0-公共约定)
- [§1 SMS（同步）](#1-sms同步)
- [§2 App Push（异步入队）](#2-app-push异步入队)
- [§3 Push fallback SMS](#3-push-fallback-sms)
- [§4 配置与代码迁移](#4-配置与代码迁移)
- [§5 联调检查清单](#5-联调检查清单)
- [§6 ContextSnapshot 字段映射](#6-contextsnapshot-字段映射)
- [§7 Phase 1 简易对账（无 Outbound 回调）](#7-phase-1-简易对账无-outbound-回调)
- [§8 供应商路由配置说明](#8-供应商路由配置说明)
- [§9 StepResult 映射（草案）](#9-stepresult-映射草案)
- [§10 状态回调（Phase 2 必做）](#10-状态回调phase-2-必做)
- [附录 A：通知中心 SMS 路由参考](#附录-a通知中心-sms-路由参考运维非引擎实现)
- [附录 B：待跨团队定稿事项](#附录-b待跨团队定稿事项)

---

## 0. 公共约定

### 0.1 环境与 Base URL


| 环境  | Base URL                                       |
| --- | ---------------------------------------------- |
| 测试  | `https://service-test.mocasa.com/notification` |
| 生产  | `https://notification.mocasa.com`              |


### 0.2 鉴权（所有发送接口必填）


| 字段         | 说明                                              |
| ---------- | ----------------------------------------------- |
| `appCode`  | 调用方应用编码，由通知中心配置                                 |
| `dateTime` | 当前毫秒时间戳 **字符串**                                 |
| `sign`     | `MD5(appCode + appKey + dateTime)`，三字段直接拼接，无分隔符 |


```text
sign = md5("mocasa" + "<appKey>" + "1718000000000")
```

### 0.3 统一响应

```json
{ "code": 0, "msg": "success", "data": {} }
```


| code   | 含义          | collection-channel 处理            |
| ------ | ----------- | -------------------------------- |
| `0`    | 接口处理成功      | 继续看 `data`（SMS 同步）或视为受理（Push 异步） |
| `51`   | 无鉴权         | FAILED，非 retryable               |
| `81`   | 参数错误        | FAILED，非 retryable               |
| `1000` | 签名错误        | FAILED，非 retryable（配置问题）         |
| `2001` | 无可用账号       | FAILED，非 retryable，告警            |
| `2003` | appCode 不存在 | FAILED，非 retryable               |
| `3001` | 供应商无效       | FAILED，非 retryable               |


### 0.4 Nacos 配置

```yaml
channel:
  notification:
    base-url: ${NOTIFICATION_BASE_URL:https://service-test.mocasa.com/notification}
    app-code: ${NOTIFICATION_APP_CODE:mocasa}      # 催收引擎专用，须与通知中心登记一致
    app-key: <运维下发>              # Nacos channel.notification.app-key，勿进仓库
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


| 类型  | URL                       | Phase 1 |
| --- | ------------------------- | ------- |
| 同步  | `POST /v1/sms/send`       | **默认**  |
| 异步  | `POST /v1/sms/queue/send` | 不使用     |


### 1.2 StepCommand → 请求体


| StepCommand         | 通知中心字段                        |
| ------------------- | ----------------------------- |
| `targetAddress`     | `mobile`                      |
| `metadata.sms_body` | `content`（1–1000 字，须已报备）      |
| 固定                  | `contentType = collection`    |
| 鉴权                  | `appCode`, `dateTime`, `sign` |


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


| 条件                                    | success | contactResult | retryable | providerMsgId    |
| ------------------------------------- | ------- | ------------- | --------- | ---------------- |
| `code=0` 且 `data.requestSuccess=true` | true    | DELIVERED     | false     | `data.requestId` |
| `code=0` 且 `requestSuccess=false`     | false   | FAILED        | 视供应商错误    | —                |
| 超时 / HTTP 5xx                         | false   | FAILED        | true      | —                |
| `code` 1000/2001/3001/81              | false   | FAILED        | false     | —                |


**metadata 回写建议**：`notification_channel`（如 `QHSms`）、`sms_content_type=collection`。

> **注意**：`code=0` 表示通知中心接口处理完成，**不等于**第三方一定送达；Phase 1 仍以 `requestSuccess=true` 作为步骤完成依据（与引擎同步渠道模型一致）。送达回执（`delivered`）由通知中心写 `t_history`，**Phase 1 回调仅丰富 timeline，Phase 2 升级为完成步骤**。

### 1.4 引擎行为

SMS 为 **同步渠道**：`ChannelGateway.dispatch` 成功 → `STEP_COMPLETED`（总规格 §6）。

---

## 2. App Push（异步入队）

### 2.1 接口与供应商


| 类型  | URL | Phase 1 催收 |
| --- | --- | --- |
| 异步入队 | `POST /v1/app_notification/send`（同 `/queue/send`） | **默认**；`code=0` 无 `data` |
| 同步发送 | `POST /v1/app_notification/sync/send` | **不使用**；可拿 `requestId` / 同步 JPush 错误，但仍不保证用户收到 |

通知中心消费队列后按 `content_type=jpush` 选账号，调用 **极光 JPush**（底层 FCM 封装，催收不传 FCM token）。

> **送达语义**：异步接口 **不回传** 用户是否收到、是否卸载。极光后续 webhook 只更新通知中心 `t_history`，Phase 1 **不**回调催收（见 §10）。

### 2.2 画像依赖


| 字段                              | 来源                             | 说明                                     |
| ------------------------------- | ------------------------------ | -------------------------------------- |
| `userProfile.device.jpushToken` | **`case_push` 消息体** → ingestion → 快照（2026-07 确认） | **JPush Registration ID**（非 FCM token） |


`StepResolver` 将 `jpushToken` 填入 `StepCommand.targetAddress`。

### 2.3 StepCommand → 请求体


| StepCommand / snapshot       | 通知中心字段                                       |
| ---------------------------- | -------------------------------------------- |
| `targetAddress`              | `token`（多设备：以最新登录设备为准，单 token）                    |
| `metadata.title`             | `title`                                      |
| `metadata.body`              | `body`                                       |
| `caseContext.repaymentUrl` 等 | `data`（**JSON object 字符串，value 必须为 string**） |
| 鉴权                           | `appCode`, `dateTime`, `sign`                |


`**data` 示例**（Phase 1 建议 schema，须与 App 对齐）：

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


| 条件              | success | contactResult | retryable | 说明                                 |
| --------------- | ------- | ------------- | --------- | ---------------------------------- |
| `code=0`        | true    | DELIVERED     | false     | **入队成功 ≡ 渠道受理成功**（类比 SendGrid 202） |
| `code=81` 等参数错误 | false   | FAILED        | false     | 可触发 §3 fallback（若 token 相关）        |
| 超时 / HTTP 5xx   | false   | FAILED        | true      | —                                  |


**Phase 1 明确不做**：

- 在同一次 `dispatch` 内等待 JPush 投递结果
- 因 JPush 最终失败而自动 fallback SMS（无效 token 多在队列消费后发现）

若业务后续需要，见附录 B（同步 token 校验 / webhook 补偿，Phase 2）。

### 2.5 无互动 / 点击回传

Phase 1 **不**接入 Push 打开/点击用于条件 Email（编排 §3.5 已裁剪）。极光点击数据当前未回传催收；李辉待确认买点能力（Phase 2）。

Outbound 状态回调（delivered / failed）见 **§10**；Phase 1 不接入。

---

## 3. Push fallback SMS

编排 §3.5 不变：同槽 Push 失败或无 token → **PushAdapter 内**改 SMS，对引擎仍是一次 `dispatch`。

```
NotificationPushAdapter.send()
  ├─ jpushToken 为空 → NotificationSmsAdapter（同槽 fallback）
  ├─ POST /v1/app_notification/send 参数错误（code=81）→ fallback（可选，按 subcode）
  └─ code=0 入队成功 → DELIVERED，不 fallback
```


| 项              | 说明                                                 |
| -------------- | -------------------------------------------------- |
| fallback 正文    | `metadata.sms_body` 或 `metadata.fallback_sms_body` |
| idempotencyKey | `{base}:sms_fallback`                              |
| SMS 接口         | 同 §1，`contentType=collection`                      |
| 合规             | 仍计 **一次** Push 槽位触达                                |


---

## 4. 配置、重试与代码迁移

### 4.1 失败重试策略（渠道侧）

通知中心**不负责自动重试**。为应对瞬时网络抖动或 5xx 错误，催收渠道侧需实现短重试：

- **网络/5xx 错误**：在 `NotificationSmsAdapter` / `NotificationPushAdapter` 内部使用 Spring `@Retryable` 实现短重试（如重试 3 次，间隔 1s/2s/3s）。
- **业务错误（如 2001 无可用账号、81 参数错误）**：**不重试**，直接返回 `success=false`，由引擎记录失败。
- **引擎级兜底**：若 Adapter 重试耗尽仍抛出 5xx，返回 `retryable=true`，由引擎 `StepExecutionOrchestrator` 延迟重新调度（见总规格 §8）。
- **主备渠道**：当前催收账号未配置主备，依赖通知中心后台的 weight 权重分发。

### 4.2 NotificationClient HTTP 约定（实现参考）

| 项 | 建议值 | 说明 |
| --- | --- | --- |
| Method / Content-Type | `POST` / `application/json` | |
| SMS 读超时 | 10–15s | 同步等供应商 |
| Push 异步入队超时 | 5s | 仅等入队 |
| 鉴权 `dateTime` | 毫秒时间戳字符串 | `sign=MD5(appCode+appKey+dateTime)` 小写 hex；时钟建议 ±5 分钟内 |
| SMS `mobile` | `0917…` 或 `63917…` | 通知中心按 `t_app.country` 归一化 |
| 幂等 | 无 API 字段 | 重复 POST 会重复发送；靠催收 Redis `idempotencyKey` |

### 4.3 包结构（建议）

```
collection-channel/
  adapter/NotificationSmsAdapter.java
  adapter/NotificationPushAdapter.java
  adapter/NotificationClient.java          # 签名、HTTP、响应解析
  adapter/SendGridEmailAdapter.java
  adapter/LthVoiceAdapter.java
```

### 4.4 迁移对照


| 现状（初版）                               | 目标                                                   |
| ------------------------------------ | ---------------------------------------------------- |
| `LthSmsAdapter`                      | `NotificationSmsAdapter`                             |
| `FcmPushAdapter` + `channel.fcm.*`   | `NotificationPushAdapter` + `channel.notification.*` |
| `SmsDispatchAdapter` / QH/Hiway/BORI | **删除**，路由由通知中心负责                                     |
| `ChannelProperties.lth.sms`          | 废弃；`lth` 仅保留 `voice`                                 |


### 4.5 幂等

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

### 5.1 SMS 测试案例（可直接 curl / Postman）

> 两个端点（核对自通知中心 `SmsController`）：**测试** `POST /v1/sms/testSend`（`@NotAuth` 免签名、可 `accountName` 指定通道）；**生产** `POST /v1/sms/send`（需 `appCode+dateTime+sign`）。

**阶段 1 · 测试接口（mobile=123456，免签名）**

```json
POST {base-url}/v1/sms/testSend
{ "appCode": "mocasa", "mobile": "123456",
  "content": "[MOCASA TEST] Payment due. Pay only via SKYPAYLOANS.",
  "contentType": "collection", "accountName": "<可空，指定测试通道>" }
```

| 用例 | 输入 | 预期 |
|------|------|------|
| TC-SMS-TEST-01 正常测试发送 | mobile=123456 | `code=0`、`data.requestSuccess=true`、`requestId` 非空；`t_history` 落 `label=collection` |
| TC-SMS-TEST-02 指定测试通道 | `accountName`=待测账号 | 命中 `smsTestRouter`，`data.channel`=该账号 channelCode（可绕 weight 定向测线） |
| TC-SMS-TEST-03 缺参校验 | content/mobile 空 | `@NotBlank` 校验失败 / `WRONG_MOBILE`，不落成功记录 |
| TC-SMS-TEST-04 内容类型隔离 | contentType=otp vs collection | 路由账号不同，确认 collection 走催收专用账号 |

**阶段 2 · 生产接口（mobile=9451374358 / 9451373897，需签名）**

```json
POST {base-url}/v1/sms/send
{ "appCode": "mocasa", "dateTime": "1749693900000", "sign": "<md5_hex>",
  "mobile": "9451374358",
  "content": "[MOCASA] Payment due. Pay via SKYPAYLOANS app.",
  "contentType": "collection" }
```

| 用例 | 输入 | 预期 |
|------|------|------|
| TC-SMS-PROD-01 真号送达(号A) | mobile=9451374358 | `code=0`/`requestSuccess=true`、`requestId`；真机收到；`checkSmsInfoDto` 补区号→`639451374358` |
| TC-SMS-PROD-02 真号送达(号B·验路由) | mobile=9451373897 | 同上；不同运营商号段 → `data.channel` 路由到不同供应商 |
| TC-SMS-PROD-03 错误签名 | sign 故意错 | `code=1000` INVALID_SIGN |
| TC-SMS-PROD-04 appCode 不存在 | appCode 改错 | `code=2003` |
| TC-SMS-PROD-05 端到端走适配器 | `single-step=SMS`+联调 caseId(号A) | `NotificationSmsAdapter`→`DELIVERED`、`providerMsgId=requestId`；观测页可见步骤+timeline |

> 适配器对号码做「去前导 `+`」；9451374358/9451373897 无 `+`，原样透传由通知中心按 PH 补 `63`。

---

## 6. ContextSnapshot 字段映射

全链 SSOT 见 **[ContextSnapshot 字段透传说明](./MOCASA催收系统升级_Phase1_ContextSnapshot字段透传说明.md)**。本节为 Notification Adapter 速查。

### 6.1 SMS

| 快照 | StepCommand | API 字段 |
|------|-------------|----------|
| `basic.primaryPhone` | `targetAddress` | `mobile` |
| Resolver 渲染 | `metadata.sms_body` | `content` |
| — | — | `contentType=collection` |

### 6.2 Push

| 快照字段 | 上游来源 | StepCommand | API 字段 |
|----------|----------|-------------|----------|
| `userProfile.device.jpushToken` | **`case_push` 消息体** → ingestion（§2.2；可选降级读 `t_user_device_token`） | `targetAddress` | `token` |
| Resolver 渲染 | — | `metadata.title` | `title` |
| Resolver 渲染 | — | `metadata.body` | `body` |
| `caseContext.repaymentUrl` 等 | ingestion / 信贷结账链路 | `metadata.pushData`（JSON 字符串） | `data` |

`pushData` 内字段：`scene`（固定 `collection`）、`case_id`、`deep_link`（来自 `repaymentUrl`）、`script_slot`（来自 `metadata.scriptSlot`）；**value 均为 string**。

**无 `jpushToken` 则无法完成 App Push 入队**（仅能 fallback SMS）。全链上游说明见 [字段透传说明 §2.1](./MOCASA催收系统升级_Phase1_ContextSnapshot字段透传说明.md#21-jpushtoken-上游来源push-硬依赖)。

Push fallback：`jpushToken` 为空或入队前 `code=81` 时，Adapter 用 `metadata.fallback_sms_body` 或 `metadata.sms_body` 调 §1 SMS 接口。

### 6.3 待跨团队确认（影响本节定稿）

见附录 B #4（`data` schema）。

---

## 7. Phase 1 简易对账（无 Outbound 回调）

> **定位**：胡欢侧 Outbound 回调暂未就绪时的催收侧最小方案。步骤完成不依赖 delivered；对账靠 `requestId` + 数仓。

| 能力 | Phase 1 做法 | 落库 / 日志 |
| --- | --- | --- |
| SMS 步骤完成 | `code=0` 且 `data.requestSuccess=true` | `providerMsgId=requestId`；`metadata.notification_channel` |
| Push 步骤完成 | `code=0`（入队成功） | **无** `providerMsgId`；日志打 `planId/stepOrder/token` |
| 用户真实收到（delivered） | **不驱动** `STEP_COMPLETED` | 通知中心 `t_history`；数仓事后分析 |
| 客诉排障 | SMS：用 `requestId` + `mobile` 找通知中心 | Push：暂无 `requestId`，靠数仓 / Phase 2 回调 |
| timeline 丰富 | 不接 Webhook | Phase 2 回调后升级（见 §10） |

**Adapter 日志必打字段**：`planId`、`stepOrder`、`channelType`、`mobile` 或 `token`、`requestId`（SMS）、`notification_channel`、`appCode`、`dateTime`。

---

## 8. 供应商路由配置说明

催收 **不传** 供应商/channel 参数；路由在 `common-notification` 内完成（`RouterService`）。

### 8.1 配置数据模型

```
t_app (code=mocasa, secret_key, country)
  └─ t_app_account (content_type, operator, weight, sender, account_name)
       └─ t_account (account_no, api_key, …)
            └─ t_channel (code=QHSms/HiWaySms/bori, host)
```

Nacos：`biz.smartPrefix` / `globePrefix` / `ditoPrefix`（号段 → 运营商）。

### 8.2 SMS（`contentType=collection`）算法

1. 查 `app_id + content_type=collection` 且 `is_valid=1` 的账号列表
2. 按手机号（去 `63`）匹配运营商，过滤 `t_app_account.operator`
3. 多账号时按 **weight + 手机号 hash** 选一家（同号相对稳定）
4. **collection 不走** OTP 专用的「近 10 分钟轮转」逻辑
5. 失败 **不** 自动换供应商；无内置重试

### 8.3 Push（`content_type=jpush`）

- 不按运营商；按 weight 选 JPush 应用账号
- 催收请求体无 `contentType` 字段（Push 固定 jpush 账号）

### 8.4 运维改配置 vs 催收自配

| 方式 | 说明 |
| --- | --- |
| 通知中心 CRM / Dashboard | `t_app_account` 改 weight、operator、启停；徐颖待开权限 |
| 联调强制线路 | `POST /v1/sms/testSend` + `accountName`（非催收生产路径） |
| 催收引擎 | **无**路由配置 API；只传 `contentType=collection` |

后续若支持「催收自配」，预期在通知中心侧改 `t_app_account` 或 CRM，**非** Adapter 实现选路。

---

## 9. StepResult 映射（草案）

> **状态**：供 `NotificationSmsAdapter` / `NotificationPushAdapter` 实现参考；与 [引擎渠道执行契约对齐（已定稿正本）](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md) 对齐（4 项已于 2026-06-11 定稿）。`errorCode` 仅落 timeline，引擎不解析。

### 9.1 SMS

| 通知中心情形 | success | contactResult | retryable | errorCode（建议） |
| --- | --- | --- | --- | --- |
| `code=0` 且 `requestSuccess=true` | true | DELIVERED | false | — |
| `code=0` 且 `requestSuccess=false` | false | FAILED | false | `NOTIFICATION_SMS_REJECTED` |
| HTTP 超时 / 5xx（Adapter 短重试后） | false | FAILED | **true** | `NOTIFICATION_TIMEOUT` |
| `code=2001` | false | FAILED | false | `NOTIFICATION_NO_ACCOUNT` |
| `code=81` | false | FAILED | false | `NOTIFICATION_PARAM_ERROR` |
| `code=1000` | false | FAILED | false | `NOTIFICATION_INVALID_SIGN` |
| `code=3001` | false | FAILED | false | `NOTIFICATION_INVALID_PROVIDER` |

回填：`providerMsgId=data.requestId`；`metadata.notification_channel=data.channel`。

> `contactResult=DELIVERED` 表示 **受理成功（sent）**，非运营商 delivered 回执。

### 9.2 Push（异步 `/send`）

| 情形 | success | contactResult | retryable | errorCode（建议） |
| --- | --- | --- | --- | --- |
| `code=0` | true | DELIVERED | false | — |
| `code=81` 等参数错误 | false | FAILED | false | `NOTIFICATION_PARAM_ERROR`（可 fallback SMS） |
| HTTP 超时 / 5xx | false | FAILED | **true** | `NOTIFICATION_TIMEOUT` |
| `code=2001/1000/3001` | false | FAILED | false | 同 SMS 前缀 |

`providerMsgId`：**留空**（无异步 `requestId`）。入队后 JPush 失败 / 用户卸载：**不**改 `StepResult`（Phase 1）。

### 9.3 Push fallback SMS

fallback 成功后按 **§9.1 SMS** 映射；`metadata.fallback_sms=true`。

---

## 10. 状态回调（Phase 2 必做）

> **Phase 1**：**不实现** Outbound 回调，按 §7 简易对账跑通步骤。  
> **Phase 2**：**必须实现**——通知中心在收到供应商/极光 inbound webhook 并更新 `t_history` 后，回调催收系统。

### 10.1 现状（`common-notification` 代码）

| 方向 | 路径 | 行为 |
| --- | --- | --- |
| Inbound（供应商 → 通知中心） | `/v1/sms/webhook/{channelCode}` | 更新 `t_history.status`（sent/delivered/failed） |
| Inbound（极光 → 通知中心） | `/v1/app_notification/webhook/jpush` | 送达/未送达/点击 → 更新 history |
| **Outbound（通知中心 → 催收）** | **不存在** | `t_app` 无 `callback_url`；更新 history 后 **不**通知业务方 |

### 10.2 Phase 2 目标（与会议、胡欢待办对齐）

| 阶段 | 催收行为 | 是否改 `STEP_COMPLETED` |
| --- | --- | --- |
| **Phase 2a（推荐先做）** | collection-admin 接 Webhook，按 `requestId` 幂等升级 `t_contact_timeline`（delivered/failed/read） | **否**（与 SendGrid Event 一致） |
| **Phase 2b（可选）** | 编排消费 delivered 做条件分支 / 效果统计 | 视产品；可能改观察期或条件 Email |

### 10.3 待通知中心（胡欢）与催收联合定稿

| # | 议题 |
| --- | --- |
| 1 | 回调 URL 配置方式（`t_app.callback_url` 或按 `content_type`） |
| 2 | payload：`requestId`、`appCode`、`notificationType`、`status`（sent/delivered/failed/read）、`mobile`/`token`、`channel`、`timestamp` |
| 3 | 鉴权（签名 / IP 白名单）与重试 |
| 4 | 幂等键：`requestId + status` |
| 5 | Push 是否补充 `requestId`（同步 send 或异步入队后生成内部 ID） |

### 10.4 催收侧改造范围（Phase 2a 预估）

| 模块 | 改动 |
| --- | --- |
| `collection-admin` | 新增 `POST /webhook/notification`（或等价），验签、更新 timeline |
| `collection-channel` | Phase 1 **无需**改 dispatch；可选在 SMS 响应时预写 timeline 占位 |
| 文档 | 关闭附录 B #11；升级 §7「简易对账」为双通道 |

---

## 附录 A：通知中心 SMS 路由参考（运维，非引擎实现）

催收 `appCode=mocasa`、`contentType=collection` 在通知中心后台的配置（与现网一致）：


| ID  | Account Name        | Operator           | Weight | 底层供应商文档                                                                |
| --- | ------------------- | ------------------ | ------ | ---------------------------------------------------------------------- |
| 340 | QHSmsNotice         | other, globe, dito | 3      | [QH SMS](../../../AI%20collection/相关资料/QH%20SMS%20接口.md)               |
| 341 | HiWaySmsOther       | dito, other, globe | 2      | [HiwayIO API](../../../AI%20collection/相关资料/HiwayIO-API%201.5.2.docx)  |
| 339 | bori Mocasa-MKT-002 | smart              | 1      | [BORI HTTP](../../../AI%20collection/相关资料/【BORI】HTTP%20对接开发文档1.0.docx) |
| —   | 新 Smart 线路（测试中）     | smart              | TBD    | 同上                                                                     |


**collection-channel 只需传 `contentType=collection`**；运营商识别与 weight 分流由通知中心完成。账号变更、新 Smart 线路灰度均在通知中心后台操作，**无需**发版催收引擎。

---

## 附录 B：待跨团队定稿事项

> 开发前建议通知中心 + App 团队 30min 对齐；定稿后更新本节并关闭对应 checklist。


| #   | 议题                            | Phase 1 建议                                               | 状态             |
| --- | ----------------------------- | -------------------------------------------------------- | -------------- |
| 1   | 催收引擎 `appCode` / `appKey` 正式值 | `appCode=mocasa`；测试/生产独立 key                             | ⏳ 待运维签发        |
| 2   | Push 入队即完成                    | 产品接受：无法区分「入队但 JPush 最终失败」                                | ✅ 文档已采纳        |
| 3   | 无效 JPush token                | 只记失败、**不**自动 fallback SMS；无效 token 队列消费后发现               | ✅ Phase 1      |
| 4   | `data` 透传 schema              | `scene`、`case_id`、`deep_link`、`script_slot`（string only） | ⏳ 待 App 确认     |
| 5   | 多设备 token                     | 确认以最新登录设备为准，单 token 下发                             | ✅ 已确认          |
| 6   | history 对账                    | SMS 存 `requestId`；暂无 API，依赖数仓事后分析                    | ✅ 已确认        |
| 7   | 超时与重试 SLA                     | 5xx/超时 → retryable                                       | ✅ 与 SMS 同步一致   |
| 8   | Smart 新线路                     | 通知中心后台配置，引擎无感知                                           | ✅ 已确认          |
| 9   | SMS 文案报备                      | 变更走通知中心/运营流程                                             | ✅ 已确认          |
| 10  | 同步 token 校验 API               | 若 Phase 2 要强 fallback，需通知中心提供                            | Phase 2        |
| 11  | **Outbound 状态回调催收**           | Phase 1 不实现；Phase 2 **必做**（先 timeline，再评估步骤语义）        | ⏳ 胡欢待出接口     |


---

> MOCASA Collection — Phase 1 Notification Center Integration v1.1

