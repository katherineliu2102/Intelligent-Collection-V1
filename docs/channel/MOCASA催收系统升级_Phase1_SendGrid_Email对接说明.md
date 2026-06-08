# MOCASA Phase 1 — SendGrid Email 对接说明

> **版本**: v1.0 · **日期**: 2026-06-03  
> **供应商**: Twilio SendGrid（主）；SES 备（Phase 1 不实现切换）  
> **上级文档**: [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md)、[SendGrid催收邮件接入指南](./SendGrid催收邮件接入指南.md)（API 附录）、[渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) §3.5  
> **代码目标**: `com.collection.channel.adapter.SendGridEmailAdapter`

---

## 1. 范围与依赖

| 项 | 说明 |
|----|------|
| Phase 1 Email | **仅里程碑**（§7.9）；不生成条件 Email step |
| snapshot | `userProfile.basic.email`；空则 **不调用** SendGrid |
| 无邮箱 | `ComplianceExecutionGuard` **BLOCK**，`blockedRuleType=NO_EMAIL`，步骤 SKIPPED |
| 模板 | SendGrid 控制台 Dynamic Template；`template_id` 见总规格 **附录 A** |

---

## 2. 调用模型

**同步渠道**：HTTP **202 Accepted** → 引擎 `STEP_COMPLETED`。

```
Guard(有邮箱) → StepResolver → StepCommand(EMAIL)
  → SendGridEmailAdapter → POST /v3/mail/send
  → StepResult(DELIVERED) → STEP_COMPLETED
```

**SendGrid Event Webhook**（delivered/open/click/bounce）：**不** 触发 `STEP_COMPLETED`；更新 timeline / suppression 即可。

---

## 3. StepCommand → SendGrid API

| StepCommand | SendGrid |
|-------------|----------|
| targetAddress | `personalizations[].to[].email` |
| templateId | SendGrid `d-xxxxxxxx`（附录 A） |
| metadata.dynamicTemplateData | Handlebars 变量，建议键： |

**dynamicTemplateData 建议键**

| 键 | 来源 |
|----|------|
| borrower_name | snapshot.basic.name |
| amount_due | caseContext 已到期应还合计 |
| overdue_days | caseContext.maxDpd |
| payment_link | caseContext.repaymentUrl |
| stage | metadata.stage |
| offer_* | snapshot offer 字段（F10 已写入 snapshot） |

**custom_args（强制）**

```json
{
  "case_id": "1001",
  "step_id": "10",
  "idempotency_key": "1:1:0",
  "script_slot": "S1_EMAIL_OVERDUE_NOTICE"
}
```

> 禁止使用 `loan_id` 作为业务主键；与引擎 `case_id` 一致。

**asm**：退订组 ID（NPC 合规），见 [接入指南 §4.2](./SendGrid催收邮件接入指南.md)。

**from**：催收独立子域，如 `notice.collections.{brand}.ph`（与 OTP 子域隔离）。

---

## 4. SendGrid 响应 → StepResult

| HTTP | success | contactResult | 说明 |
|------|---------|---------------|------|
| 202 | true | DELIVERED | 已入队，步骤完成 |
| 400/401/403 | false | FAILED | retryable=false |
| 429/5xx | false | FAILED | retryable=true |

---

## 5. Webhook → timeline（非 CHANNEL_CALLBACK）

**路径**：`POST /webhook/sendgrid`（collection-admin）

| event | timeline 动作 |
|-------|----------------|
| delivered | 更新 result=DELIVERED（若需升级链） |
| open | READ（`canUpgradeFrom`） |
| click | CLICKED |
| bounce (hard) | REJECTED + 案件 `email_suppressed` |
| spam_report | REJECTED + suppression |
| unsubscribe | REJECTED + 停止后续 Email Guard |

关联：Webhook 体内 `case_id` / `step_id` 来自 `custom_args`。

---

## 6. 错误码与 retryable

| errorCode | retryable |
|-----------|-----------|
| SENDGRID_RATE_LIMIT | true |
| SENDGRID_5XX | true |
| SENDGRID_BAD_REQUEST | false |
| EMAIL_SUPPRESSED | false（Guard 后续 BLOCK） |

---

## 7. 幂等与对账

- `idempotency_key` 与引擎 step 幂等键一致
- `providerMsgId`：SendGrid `sg_message_id`（响应头或后续 webhook）
- List Cleaning：发信前校验（选型报告 P0）

---

## 8. Phase 1 特例

| 场景 | 行为 |
|------|------|
| 无邮箱 | Guard BLOCK，不调 API |
| 里程碑到日 | 由 PlanFactory `trigger_time` 触发，非 SendGrid `send_at` 主路径 |
| POC 门禁 | Delivery >98%；hard bounce <2%（选型报告） |

---

## 9. 配置项

| 项 | 说明 |
|----|------|
| SENDGRID_API_KEY | credentials_ref |
| SENDGRID_FROM_EMAIL | 催收域发件人 |
| SENDGRID_UNSUBSCRIBE_GROUP_ID | asm |
| SENDGRID_WEBHOOK_PUBLIC_KEY | 验签 |

---

## 10. 联调检查清单

- [ ] 有邮箱：202 → step COMPLETED，timeline DELIVERED
- [ ] 无邮箱：Guard SKIPPED，无 SendGrid 调用
- [ ] custom_args 含 case_id，Webhook 可回写 timeline
- [ ] hard bounce 后同案后续 Email 步骤 SKIPPED
- [ ] 附录 A 中 S0/S1 模板 ID 已填且双语变量渲染正确

---

> API 细节（curl、SDK、重试退避）见 [SendGrid催收邮件接入指南](./SendGrid催收邮件接入指南.md)
