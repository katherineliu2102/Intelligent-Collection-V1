# MOCASA Phase 1 — ContextSnapshot 字段透传说明

> **版本**: v1.0 · **日期**: 2026-06-09  
> **定位**: 从 **引擎快照 → StepResolver → StepCommand → Adapter → 供应商 API** 的全链字段映射 SSOT。  
> **快照契约**: [README_ContextSnapshot契约对齐.md](../../../AI%20collection/相关资料/README_ContextSnapshot契约对齐.md)、[ContextSnapshot.sample.json](../../../AI%20collection/相关资料/ContextSnapshot.sample.json)  
> **渠道 API**: [Notification 对接说明](./MOCASA催收系统升级_Phase1_Notification对接说明.md)、[notification-send-api.md](../../../AI%20collection/相关资料/notification-send-api.md)  
> **引擎契约**: [collection-channel 总规格 §3](./MOCASA催收系统升级_Phase1_collection-channel总规格.md#3-契约stepcommand--stepresult--channel_callback)

---

## 1. 数据流

```
数据接入层组装 ContextSnapshot（精简 JSON）
  → 落库 t_contact_plan.context_snapshot
  → ExecutionContext（SPI 零 DB）
  → StepResolver.resolve() → StepCommand
  → NotificationSmsAdapter / NotificationPushAdapter / SendGridEmailAdapter / LthVoiceAdapter
  → 通知中心 / SendGrid / LTH
```

**分工**

| 层 | 职责 |
|----|------|
| 快照 | 只存**事实**（号码、金额、还款链、频控计数等），不存 `title`/`body`/`sms_body` |
| StepResolver | 选 `scriptSlot`、渲染文案、填 `targetAddress`、组装 `metadata` |
| Adapter | 映射 HTTP 请求体、手机号归一化、签名鉴权 |

---

## 2. 快照精简字段（落库 SSOT）

完整表见 [README §Phase 1 精简字段集](../../../AI%20collection/相关资料/README_ContextSnapshot契约对齐.md#phase-1-精简字段集落库-ssot)。本节只列**渠道消费**相关字段。

| 块 | 字段 | 渠道消费 |
|----|------|----------|
| caseContext | `caseId`, `userId`, `dpd`, `stage`, `product`, `dueDate` | 选槽、模板变量、Guard |
| caseContext | `totalOutstanding` | 文案 `amount_due`（金额 SSOT） |
| caseContext | `repaymentUrl` | SMS 链接、Push `deep_link`、Email `payment_link` |
| caseContext | `strategyTone`, `complaintFrozen`, `collectionStatus` | PlanFactory / Guard；`strategyTone` 计算见 [渠道编排 §6.3.1](./MOCASA催收系统升级_Phase1_渠道编排规格.md#631-难催子条件计算口径ingestion-层) |
| basic | `name`, `primaryPhone`, `email`, `language` | 文案、`targetAddress` |
| device | `jpushToken`, `phoneValidity` | Push `targetAddress`；Guard |
| contactHistory | `todayTouchCount`, `channelTouchCounts`, … | Guard 频控 |

**不在快照中**：`title`、`body`、`sms_body`（Resolver 渲染）；`scene`（Adapter/Resolver 固定 `"collection"`）。

### 2.1 `jpushToken` 上游来源（Push 硬依赖）

App Push **无法**由 channel 模块自行生成 token；必须从业务侧写入快照，再透传到通知中心 `token` 字段。

| 环节 | 责任方 | 说明 |
|------|--------|------|
| 1. 采集 | **App 客户端** | 登录/启动时向极光 SDK 注册，取得 **JPush Registration ID**（不是 FCM token） |
| 2. 上报 | **App / 信贷后端** | 将 RID 写入用户设备表（现网建议列：`t_user_equipment`，DB 列名实现时与 Java `jpushToken` 对齐） |
| 3. 入案组装 | **数据接入层（ingestion）** | `ProfileService.getFullProfile(userId)` 读设备表 → 填入 `userProfile.device.jpushToken` |
| 4. 落库 | **引擎 / CaseService** | 组装精简快照 JSON → `t_contact_plan.context_snapshot` |
| 5. 渠道执行 | **StepResolver** | `device.jpushToken` → `StepCommand.targetAddress` |
| 6. 发送 | **NotificationPushAdapter** | `targetAddress` → 通知中心 API `token` → JPush |

```text
App(JPush SDK) → 业务库 t_user_equipment → ProfileService → ContextSnapshot.device.jpushToken
  → StepCommand.targetAddress → POST /v1/app_notification/send { token }
```

| 场景 | `jpushToken` | 渠道行为 |
|------|--------------|----------|
| 有有效 RID | 非空 | 正常 Push 入队 |
| 空 / null | 无 token | **PushAdapter 同槽 fallback SMS**（仍计一次 Push 槽位）；不是 Push 成功 |
| 多设备 | 以最新登录设备为准，单 token | 见 §4.2；已与 App / 通知中心确认 |

> 领域模型 §3.2 DeviceInfo、`Notification 对接说明` §2.2 与本文一致。联调账号见功能测试指南 userId **90002**（有 token）、**90003**（无 token → fallback）。

---

## 3. StepCommand metadata 已知 key（Phase 1）

| key | 类型 | 产出方 | 消费方 |
|-----|------|--------|--------|
| `scriptSlot` | String | Resolver | 全渠道日志、Push `data.script_slot` |
| `stage` | String | Resolver | 日志 |
| `language` | String | Resolver（来自 `basic.language`，默认 `en`） | 渲染 |
| `sms_body` | String | Resolver 渲染 | SMS `content`；Push fallback SMS |
| `fallback_sms_body` | String | Resolver（可选） | Push fallback 优先正文 |
| `title` | String | Resolver 渲染 | Push API `title` |
| `body` | String | Resolver 渲染 | Push API `body` |
| `pushData` | String | Resolver 序列化 | Push API `data`（JSON object 字符串） |
| `dynamicTemplateData` | Map | Resolver | SendGrid Handlebars |
| `case_id` | Long | Resolver（来自 plan 或 `caseContext.caseId`） | 日志；Push `data` 内 `case_id` |
| `callbackUrl`, `timeoutMinutes` | — | Resolver | Voice 异步 |
| `fallback_sms` | Boolean | PushAdapter | 标记已 fallback |

---

## 4. 全链路透传矩阵

### 4.1 SMS（通知中心同步）

| ContextSnapshot | StepCommand | 通知中心 `/v1/sms/send` | 说明 |
|-----------------|-------------|-------------------------|------|
| `basic.primaryPhone` | `targetAddress` | `mobile` | 快照建议 E.164 `+639…`；API 接受 `0917…`；Adapter 宜归一化 |
| Resolver 渲染 | `metadata.sms_body` | `content` | 1–1000 字，须报备 |
| — | — | `contentType` | 固定 `collection`（Nacos `sms-content-type`） |
| — | — | `appCode`, `dateTime`, `sign` | `NotificationClient` 鉴权 |
| `basic.language` | `metadata.language` | — | 仅渲染，不进 API |
| `basic.name` | —（渲染进 `sms_body`） | — | 模板变量 `borrower_name` |
| `caseContext.totalOutstanding` | —（渲染进 `sms_body`） | — | `amount_due` |
| `caseContext.dpd` | —（渲染进 `sms_body`） | — | `overdue_days` |
| `caseContext.repaymentUrl` | —（渲染进 `sms_body`） | — | 还款链接文案 |
| 响应 | `providerMsgId` | `data.requestId` | `requestSuccess=true` 时落库 |
| 响应 | `metadata.notification_channel` | `data.channel` | 如 `QHSms` |

### 4.2 App Push（通知中心异步入队 → JPush）

> **Push 最小必填（快照侧）**：`device.jpushToken`（来自业务系统，见 §2.1）+ `caseContext.repaymentUrl`（`deep_link`）+ `basic.primaryPhone`（无 token 时 fallback SMS）。`title`/`body` 由 Resolver 渲染，不进快照。

| ContextSnapshot | StepCommand | 通知中心 `/v1/app_notification/send` | 说明 |
|-----------------|-------------|--------------------------------------|------|
| `userProfile.device.jpushToken` | `targetAddress` | `token` | **业务系统 → ingestion 写入**；JPush Registration ID；空 → PushAdapter fallback SMS |
| Resolver 渲染 | `metadata.title` | `title` | 不进快照 |
| Resolver 渲染 | `metadata.body` | `body` | 不进快照 |
| `caseContext.repaymentUrl` | `metadata.pushData` 内 `deep_link` | `data` JSON 字段 | **Email 用 `payment_link`，Push API 用 `deep_link`** |
| `caseContext.caseId` | `metadata.pushData` 内 `case_id` | `data` JSON 字段 | value 必须为 **string** |
| `metadata.scriptSlot` | `metadata.pushData` 内 `script_slot` | `data` JSON 字段 | |
| — | `metadata.pushData` 内 `scene` | `data` JSON 字段 | 固定 `"collection"` |
| Resolver 渲染 | `metadata.sms_body` | — | token 空时 fallback SMS `content` |
| — | — | `appCode`, `dateTime`, `sign` | 鉴权 |
| 响应 | 无 `providerMsgId` | — | `code=0` = 入队成功 = 步骤完成 |

**`pushData` 正式 schema（Phase 1，待 App 会后确认）**

```json
{
  "scene": "collection",
  "case_id": "1002",
  "deep_link": "https://app.mocasa.com/repay?bill=LN1002",
  "script_slot": "S1_PUSH_STANDARD"
}
```

序列化后写入 `metadata.pushData`，Adapter 原样作为请求体 `data` 字段。

**多设备 token**：已确认**以最新登录设备为准**，快照仅保留单个 `jpushToken`，StepResolver 传单值，不使用逗号拼接。

### 4.3 Email（SendGrid）

| ContextSnapshot | StepCommand | SendGrid API | 说明 |
|-----------------|-------------|--------------|------|
| `basic.email` | `targetAddress` | `personalizations[0].to` | `null` → Guard SKIP |
| `caseContext.repaymentUrl` | `dynamicTemplateData.payment_link` | 模板变量 | |
| `caseContext.totalOutstanding` | `dynamicTemplateData.amount_due` | 模板变量 | |
| `caseContext.dpd` | `dynamicTemplateData.overdue_days` | 模板变量 | |
| `basic.name` | `dynamicTemplateData.borrower_name` | 模板变量 | |
| `caseContext.dueDate` | `dynamicTemplateData`（部分槽位） | 模板变量 | 如 S4 里程碑 |

### 4.4 Voice（LTH，异步）

| ContextSnapshot | StepCommand | LTH API |
|-----------------|-------------|---------|
| `basic.primaryPhone` | `targetAddress` | 被叫号码 |
| `metadata.callbackUrl` | — | Adapter 拼回调 URL |
| `contactHistory.todayPhoneAnswered` | — | Guard（接通即停） |

---

## 5. StepResolver 渲染约定（实现参考）

```text
SMS   → targetAddress = basic.primaryPhone
        metadata.sms_body = render(scriptSlot, name, totalOutstanding, dpd, repaymentUrl, language)
        metadata.language = basic.language ?? "en"

PUSH  → targetAddress = device.jpushToken（空则 metadata.fallbackPhone + 留给 Adapter fallback）
        metadata.title / body = renderPushTitleBody(scriptSlot, …)
        metadata.pushData = JSON.stringify({ scene, case_id, deep_link, script_slot })  // 全 string
        metadata.sms_body = 同槽 SMS 正文（供 fallback）

EMAIL → targetAddress = basic.email
        metadata.dynamicTemplateData = { payment_link, amount_due, overdue_days, borrower_name, … }
```

`targetAddress` **只由 Resolver 填写**；Gateway / Adapter **不再查库取号**。

---

## 6. 手机号归一化

| 阶段 | 格式 | 说明 |
|------|------|------|
| 快照落库 | E.164 `+639171234567` | README 已定稿 |
| StepCommand | 与快照一致或 `639…` | Mock 常用无 `+` |
| 通知中心 API | `0917…` 或 `639…` | 按 App 国家配置补齐；Adapter 建议统一为供应商友好格式 |

---

## 7. Phase 1 边界（透传层不覆盖）

- Push 入队成功 ≠ JPush 最终送达；入队后 token 无效 **不** 自动 fallback SMS
- SMS `requestSuccess=true` ≠ 运营商 `delivered` 回执
- 无通知中心 → 催收引擎 Webhook；对账依赖 `requestId` + history（API 待定）
- 合规（静默时段、日上限）在 **调用通知中心之前** 由 Guard 执行

---

## 8. 关联文档

| 文档 | 用途 |
|------|------|
| [Notification 对接说明 §6](./MOCASA催收系统升级_Phase1_Notification对接说明.md#6-contextsnapshot-字段映射) | Adapter 侧映射与附录 B 待决项 |
| [总规格 §3.1](./MOCASA催收系统升级_Phase1_collection-channel总规格.md#31-stepcommand) | StepCommand / StepResult |
| [沟通提纲](../../../AI%20collection/相关资料/MOCASA_Notification_对接与测试沟通提纲.md) | 跨团队待对齐问题 |

---

> MOCASA Collection — ContextSnapshot Field Passthrough v1.0
