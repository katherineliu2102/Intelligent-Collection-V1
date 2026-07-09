# Notification 内部发送接口文档

本文档只整理通知中心对内使用的发送接口，包含 SMS、Viber、WhatsApp、App Notification。

不包含测试发送接口和第三方通道 webhook 回调接口。

## 域名

| 环境 | Base URL |
| --- | --- |
| 测试服 | `https://service-test.mocasa.com/notification` |
| 生产 | `https://notification.mocasa.com` |

下文接口路径均以 Base URL 为前缀。

## 公共约定

### 请求方式

- Method: `POST`
- Content-Type: `application/json`
- Body: JSON

### 公共鉴权字段

所有发送接口均需要签名鉴权，请求体必须包含以下公共字段。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `appCode` | string | 是 | 调用方应用编码，由通知中心配置。 |
| `dateTime` | string | 是 | 当前毫秒时间戳字符串。 |
| `sign` | string | 是 | `MD5(appCode + appKey + dateTime)`，直接拼接字符串，不包含加号或分隔符。`appKey` 由通知中心配置。 |

签名示例：

```text
appCode = mocasa_app
appKey = secret_key
dateTime = 1718000000000
sign = md5("mocasa_appsecret_key1718000000000")
```

### 统一响应格式

```json
{
  "code": 0,
  "msg": "success",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | number | 返回码，`0` 表示接口处理成功。 |
| `msg` | string | 返回信息。 |
| `data` | object | 成功时可能返回的数据。异步发送通常无 `data`。 |
| `subcode` | string | 参数或业务错误的补充字段，非固定返回。 |
| `verbose` | string | 明细信息，非固定返回。 |

常见错误码：

| code | msg | 说明 |
| --- | --- | --- |
| `51` | `no permission` | 接口需要鉴权但请求体没有鉴权对象。 |
| `81` | `parameter error` | 参数校验失败，`subcode` 通常为失败字段名。 |
| `1000` | `invalid sign` | 签名错误。 |
| `2001` | `no valid account` | 未找到可用发送账号。 |
| `2003` | `app not exist` | `appCode` 不存在。 |
| `2004` | `invalid country` | App 配置的国家无效。 |
| `3001` | `invalid provider` | 账号配置的供应商不存在或未接入。 |

### 同步与异步发送差异

- SMS、Viber、WhatsApp 的同步发送接口会立即请求第三方供应商并写入发送历史，成功响应的 `data` 中会返回 `requestSuccess`、`channel`、`requestId`。
- 同步发送的 `code = 0` 表示通知中心接口已完成处理，不一定代表第三方发送成功。应继续查看 `data.requestSuccess`。
- 异步发送接口只负责把任务写入 Pub/Sub 队列，`code = 0` 仅表示入队成功，不返回第三方 `requestId`。
- 异步任务后续由通知中心订阅消费并发送，发送结果以历史记录和第三方回调更新为准。

同步发送成功响应示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "requestSuccess": true,
    "channel": "JPush",
    "requestId": "1234567890"
  }
}
```

异步入队成功响应示例：

```json
{
  "code": 0,
  "msg": "success"
}
```

## SMS

### 接口

| 类型 | URL |
| --- | --- |
| 同步发送 | `POST /v1/sms/send` |
| 异步发送 | `POST /v1/sms/queue/send` |

测试服完整示例：

```text
POST https://service-test.mocasa.com/notification/v1/sms/send
POST https://service-test.mocasa.com/notification/v1/sms/queue/send
```

生产完整示例：

```text
POST https://notification.mocasa.com/v1/sms/send
POST https://notification.mocasa.com/v1/sms/queue/send
```

### 请求字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `appCode` | string | 是 | 公共鉴权字段。 |
| `dateTime` | string | 是 | 公共鉴权字段。 |
| `sign` | string | 是 | 公共鉴权字段。 |
| `mobile` | string | 是 | 手机号。支持带国家区号或不带国家区号；通知中心会按 App 国家配置补齐区号。 |
| `content` | string | 是 | 短信内容，长度 `1-1000`。内容需按供应商要求报备。 |
| `contentType` | string | 是 | 短信内容类型，用于路由账号。常用值：`otp`、`notify`、`market`、`collection`。 |

### 请求示例

```json
{
  "appCode": "mocasa_app",
  "dateTime": "1718000000000",
  "sign": "md5_hex_value",
  "mobile": "09171234567",
  "content": "Your verification code is 123456.",
  "contentType": "otp"
}
```

### 响应示例

同步发送：

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

异步发送：

```json
{
  "code": 0,
  "msg": "success"
}
```

### 注意事项

- SMS 会校验手机号不能为空，并按 App 国家配置处理国家区号。
- `contentType` 会影响账号路由，必须与通知中心账号配置一致。
- 同步发送会立即写入 `t_history`；历史中的 `label` 字段记录 `contentType`。

## Viber

### 接口

| 类型 | URL |
| --- | --- |
| 同步发送 | `POST /v1/viber/send` |
| 异步发送 | `POST /v1/viber/queue/send` |

测试服完整示例：

```text
POST https://service-test.mocasa.com/notification/v1/viber/send
POST https://service-test.mocasa.com/notification/v1/viber/queue/send
```

生产完整示例：

```text
POST https://notification.mocasa.com/v1/viber/send
POST https://notification.mocasa.com/v1/viber/queue/send
```

### 请求字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `appCode` | string | 是 | 公共鉴权字段。 |
| `dateTime` | string | 是 | 公共鉴权字段。 |
| `sign` | string | 是 | 公共鉴权字段。 |
| `mobile` | string | 业务必填 | 手机号。会按供应商适配逻辑处理格式。 |
| `templateType` | string | 视模板而定 | 模板类型，主要用于部分 Viber 供应商模板发送。 |
| `template` | string | 文本发送时否，模板发送时是 | 模板编码。提供该字段时按模板消息发送。 |
| `content` | string | 模板发送时否，文本发送时是 | 文本消息内容。未提供 `template` 时按文本消息发送。 |
| `params` | object | 否 | 模板参数。系统会按 key 排序后取 value 传给供应商。 |

### 模板发送请求示例

```json
{
  "appCode": "mocasa_app",
  "dateTime": "1718000000000",
  "sign": "md5_hex_value",
  "mobile": "639171234567",
  "templateType": "text",
  "template": "loan_due_reminder",
  "params": {
    "1": "Juan",
    "2": "2026-06-10",
    "3": "1000"
  }
}
```

### 文本发送请求示例

```json
{
  "appCode": "mocasa_app",
  "dateTime": "1718000000000",
  "sign": "md5_hex_value",
  "mobile": "639171234567",
  "content": "Your loan is due tomorrow."
}
```

### 响应示例

同步发送：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "requestSuccess": true,
    "channel": "paasoo_viber",
    "requestId": "viber-provider-request-id"
  }
}
```

异步发送：

```json
{
  "code": 0,
  "msg": "success"
}
```

### 注意事项

- Viber 当前代码层没有对 `mobile`、`template`、`content` 做统一 `@NotBlank` 校验，但发送时业务上必须提供可用手机号，并提供 `template` 或 `content`。
- 同步发送使用内容类型 `viber` 路由账号。
- 发送成功或回调命中用户后，服务会维护手机号的 Viber 标签。
- `params` 会按 key 字典序排序后传值，建议使用 `"1"`、`"2"`、`"3"` 这类稳定 key，避免参数顺序不符合模板。

## WhatsApp

### 接口

| 类型 | URL |
| --- | --- |
| 同步发送 | `POST /v1/whatsapp/send` |
| 异步发送 | `POST /v1/whatsapp/queue/send` |

测试服完整示例：

```text
POST https://service-test.mocasa.com/notification/v1/whatsapp/send
POST https://service-test.mocasa.com/notification/v1/whatsapp/queue/send
```

生产完整示例：

```text
POST https://notification.mocasa.com/v1/whatsapp/send
POST https://notification.mocasa.com/v1/whatsapp/queue/send
```

### 请求字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `appCode` | string | 是 | 公共鉴权字段。 |
| `dateTime` | string | 是 | 公共鉴权字段。 |
| `sign` | string | 是 | 公共鉴权字段。 |
| `mobile` | string | 业务必填 | 手机号。会按供应商适配逻辑处理格式。 |
| `template` | string | 业务必填 | WhatsApp 模板名称。 |
| `params` | object | 否 | 模板 body 参数。系统会按 key 排序后取 value 传给供应商。 |

### 请求示例

```json
{
  "appCode": "mocasa_app",
  "dateTime": "1718000000000",
  "sign": "md5_hex_value",
  "mobile": "639171234567",
  "template": "loan_due_reminder",
  "params": {
    "1": "Juan",
    "2": "2026-06-10",
    "3": "1000"
  }
}
```

### 响应示例

同步发送：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "requestSuccess": true,
    "channel": "nxcloud_whatsapp",
    "requestId": "whatsapp-provider-request-id"
  }
}
```

异步发送：

```json
{
  "code": 0,
  "msg": "success"
}
```

### 注意事项

- WhatsApp 当前代码层没有对 `mobile`、`template` 做统一 `@NotBlank` 校验，但发送时业务上必须提供可用手机号和模板名称。
- 当前 WhatsApp 发送按模板消息处理，供应商请求中的语言固定为 `en`。
- 同步发送使用内容类型 `whatsapp` 路由账号。
- 如果模板名称以 `otp` 开头，系统会额外组装 OTP URL button 参数。
- `params` 会按 key 字典序排序后传值，建议使用 `"1"`、`"2"`、`"3"` 这类稳定 key，避免参数顺序不符合模板。

## App Notification

### 接口

| 类型 | URL |
| --- | --- |
| 异步发送 | `POST /v1/app_notification/send` |

测试服完整示例：

```text
POST https://service-test.mocasa.com/notification/v1/app_notification/send
```

生产完整示例：

```text
POST https://notification.mocasa.com/v1/app_notification/send
```

### 请求字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `appCode` | string | 是 | 公共鉴权字段。 |
| `dateTime` | string | 是 | 公共鉴权字段。 |
| `sign` | string | 是 | 公共鉴权字段。 |
| `token` | string | 是 | App 推送 token。支持多个 token，用英文逗号分隔。 |
| `title` | string | 是 | 推送标题。 |
| `body` | string | 是 | 推送内容。 |
| `image` | string | 否 | 图片 URL。当前 JPush 发送实现未使用该字段。 |
| `data` | string | 否 | 透传扩展字段，必须是 JSON object 字符串，且 value 为字符串。 |
| `label` | string | 否 | 请求 DTO 中存在该字段，但当前发送 DTO 未接收，现有 JPush 发送不会使用该字段。 |

### 请求示例

```json
{
  "appCode": "mocasa_app",
  "dateTime": "1718000000000",
  "sign": "md5_hex_value",
  "token": "registration-id-1,registration-id-2",
  "title": "Payment reminder",
  "body": "Your payment is due tomorrow.",
  "data": "{\"scene\":\"repayment\",\"orderNo\":\"LN123456\"}"
}
```

### 响应示例

```json
{
  "code": 0,
  "msg": "success"
}
```

### 注意事项

- `/v1/app_notification/send` 为异步发送接口，`code = 0` 仅表示消息已成功写入队列。
- 后台消费消息时使用内容类型 `jpush` 路由账号并调用 JPush。
- JPush 会同时构建 notification 和 message，并向所有平台发送。
- `data` 会被解析为 `Map<String,String>` 并作为 JPush extras；如果不是合法 JSON object 字符串，发送时会失败。
- JPush 的 APNs production 开关由通知中心运行环境决定：`prod` 环境为生产 APNs，其他环境为开发 APNs。
