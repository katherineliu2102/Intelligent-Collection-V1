# MOCASA Phase 1 — FCM Push 对接说明

> **版本**: v1.0 · **日期**: 2026-06-03  
> **供应商**: Firebase Cloud Messaging（Android）；APNs（iOS，若 Phase 1 接入）  
> **上级文档**: [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md)、[渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) §3.5、§7.1 Push fallback  
> **代码目标**: `com.collection.channel.adapter.FcmPushAdapter`

---

## 1. 范围与依赖

| 项 | 说明 |
|----|------|
| Phase 1 | 不采购 OneSignal 等；与 App 共用 Firebase 项目 |
| snapshot | `userProfile.device.fcmToken`（ingestion 从 App/设备表写入） |
| 深链 | `caseContext.repaymentUrl` → payload `data.deep_link` / `payment_link` |
| 无互动 | Phase 1 **不** 采集 open/click，故不做条件 Email（§3.5） |

---

## 2. 调用模型

**同步渠道**：FCM 受理成功 → `STEP_COMPLETED`。

```
StepCommand(PUSH) → FcmPushAdapter
  → (optional) FCM HTTP v1 send
  → 失败或无 token → 内部调用 LthSmsAdapter（同槽 fallback）
  → 单一 StepResult 返回引擎
```

---

## 3. StepCommand → FCM API

| StepCommand | FCM |
|-------------|-----|
| targetAddress | `fcmToken` |
| metadata | title, body, deep_link, scriptSlot |
| templateId | 可选：推送模板键 |

**推荐**：`data` 消息（后台），由 App 展示通知栏；避免仅 `notification`  payload 导致透传字段丢失（按 App 团队约定）。

**payload 示例（逻辑）**

```json
{
  "message": {
    "token": "<fcmToken>",
    "data": {
      "title": "...",
      "body": "...",
      "deep_link": "<repaymentUrl>",
      "case_id": "1001"
    }
  }
}
```

---

## 4. FCM 响应 → StepResult

| 情况 | success | contactResult | retryable |
|------|---------|---------------|-----------|
| 200 受理 | true | DELIVERED | false |
| token 未注册/无效 | false | FAILED | false → **触发 fallback SMS** |
| 5xx/超时 | false | FAILED | true |

`metadata.fallback_sms=true` 时：`contactResult` 仍为 DELIVERED（SMS 成功）或 FAILED（SMS 也失败）。

---

## 5. Webhook / 回调

Phase 1：**无** FCM 送达 Webhook 接入引擎。

App 侧点击深链若需记入 timeline，由 **App 上报接口** 写入（ingestion 扩展，非本 Adapter 范围）。

---

## 6. 同槽 fallback SMS

| 条件 | 行为 |
|------|------|
| `fcmToken` 为空 | 不调 FCM，直接 `LthSmsAdapter` |
| FCM `UNREGISTERED` 等 | fallback SMS |
| idempotencyKey | 建议 `{base}:sms_fallback` |
| 合规 | 同案仍计 **一次** Push 槽位触达（编排：fallback 不计第二次 plan step） |

fallback 正文：可与当日 SMS 同 `sms_body`，或更短「仅深链」——由 `DefaultStepResolver` 提供 `metadata.fallback_sms_body`（可选）。

---

## 7. 错误码与 retryable

| errorCode | retryable | fallback |
|-----------|-----------|----------|
| FCM_UNAVAILABLE | true | 否 |
| FCM_INVALID_TOKEN | false | 是 |
| FCM_AUTH_ERROR | false | 否（配置错误） |

---

## 8. Phase 1 特例

| 场景 | 行为 |
|------|------|
| 多设备 | Phase 1 仅 **单 token**（最新设备）；多 token Phase 2 |
| iOS | 若仅有 `apnsToken`，扩展 `DeviceInfo` 或分 Adapter |

---

## 9. 配置项

| 项 | 说明 |
|----|------|
| Firebase 服务账号 JSON | credentials_ref，FCM v1 |
| project_id | App 共用项目 |

---

## 10. 联调检查清单

- [ ] 有 token：Push DELIVERED + STEP_COMPLETED
- [ ] 无 token：仅 SMS timeline，无二次 plan step
- [ ] FCM 失败：fallback SMS 成功，metadata.fallback_sms=true
- [ ] repaymentUrl 在 App 可打开

---

## 11. 待 App / ingestion 确认

| 项 | 说明 |
|----|------|
| fcmToken 写入路径 | ProfileService / 画像表字段名 |
| 深链 URL 格式 | 与 SKYPAYLOANS biller 一致 |
| data vs notification | App 展示约定 |

---

> 选型说明：Push 无新供应商，见 [选型报告](../../../AI%20collection/philippines_fintech_channel_vendor_selection_report.md) §2.5
