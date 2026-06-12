# Email Subjects & SendGrid 配置（Phase 1 SSOT）

> Subject / Preheader **在 SendGrid 控制台 Settings 填写**，不在 Java 代码硬编码。  
> 文案原则见 [README §2 催收心理学矩阵](./README.md#2-催收心理学矩阵)。  
> **E2E 联调**（5 封）：[`email-e2e-test-cases.md`](./email-e2e-test-cases.md)

## Phase 1 启用（5 封 · 发件人 `collections@mocasa.com`）

| Stage | scriptSlot | 触发 DPD | Subject | Preheader | SendGrid ID |
|-------|------------|----------|---------|-----------|-------------|
| S0 | `S0_DUE_TODAY_EMAIL` | D0 | ⚠️ Action Required: Your Payment Is Due Today | Pay ₱{{amount_due}} today to avoid late fees and protect your credit record. | `d-9b485bfd24e14950a7811faf33c2b22f` |
| S1 | `S1_EMAIL_OVERDUE_NOTICE` | D+1 | Overdue: Payment Not Received—Late Fees Apply | {{overdue_days}} day overdue. Pay ₱{{amount_due}} today to avoid account escalation. | `d-bc7f5aee7e304caf93ca4d435a73a1d7` |
| S2 | `S2_EMAIL_ENTRY` | D+4 | Notice: Account Escalated—Late Fees Accruing | ₱{{amount_due}} · {{overdue_days}} days overdue. Pay now to stop further escalation. | `d-86ed8faae3b24489ad7db8a11067b8c4` |
| S4 | `S4_EMAIL_ENTRY` | D+31 | Formal Collection Notice—Pay Full Balance Immediately | Your account is now in formal collection. Pay ₱{{amount_due}} in full—no alternatives offered. | `d-658d5be184ab4710a19c8419ed66bca9` |
| S4 | `S4_EMAIL_PRE_CLOSE` | D+75 | ⚠️ Final Notice: Account Scheduled for Final Review | Before {{assignment_date}}, pay ₱{{amount_due}} in full. Unpaid files enter final delinquency status. | `d-881ce23667cc4df2abf82097b890cae1` · 法务审 |

## 备用（HTML 保留 · Phase 1 不发信 · 无 SendGrid 映射）

| scriptSlot | 原 DPD | 说明 |
|------------|--------|------|
| `S1_EMAIL_STAGE_WARNING` | D+3 | 素材在 `milestones/` |
| `S2_EMAIL_MID` | D+7 | 同上 |
| `S2_EMAIL_PRE_S3` | D+12 | 同上 |
| `S3_EMAIL_*` ×3 | D+16/23/30 | 同上 |
| `S4_EMAIL_FINAL_REMINDER` | D+45 | 同上 |
| `S4_EMAIL_MID` | D+60 | 同上 |
| `S*_EMAIL_CONDITIONAL` ×4 | Phase 2 | `conditionals/` |

## Nacos / 本地配置

```yaml
channel:
  sendgrid:
    from-email: collections@mocasa.com
    templates:
      S0_DUE_TODAY_EMAIL: d-9b485bfd24e14950a7811faf33c2b22f
      S1_EMAIL_OVERDUE_NOTICE: d-bc7f5aee7e304caf93ca4d435a73a1d7
      S2_EMAIL_ENTRY: d-86ed8faae3b24489ad7db8a11067b8c4
      S4_EMAIL_ENTRY: d-658d5be184ab4710a19c8419ed66bca9
      S4_EMAIL_PRE_CLOSE: d-881ce23667cc4df2abf82097b890cae1
```

> **无 `default-template-id`**：未命中映射时 Adapter 返回 `SENDGRID_NO_TEMPLATE`。

## 条件 Email（Phase 2 · 当前不启用）

| Stage | scriptSlot | Subject |
|-------|------------|---------|
| S1–S4 | `S*_EMAIL_CONDITIONAL` | 见 `conditionals/` · Phase 2 Plan 启用后再评估 |
