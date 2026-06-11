# Email Subjects & SendGrid 配置（Phase 1 SSOT）

> Subject / Preheader **在 SendGrid 控制台 Settings 填写**，不在 Java 代码硬编码。  
> 文案原则见 [README §2 催收心理学矩阵](./README.md#2-催收心理学矩阵)。  
> Test Data 见 [`email-templates-test/`](./email-templates-test/README.md)。  
> **E2E 联调案例**见 [`email-e2e-test-cases.md`](./email-e2e-test-cases.md)。

## 里程碑 Email（14:00 PHT · Phase 1 Plan）

| Stage | scriptSlot | DPD | Subject | Preheader | SendGrid ID |
|-------|------------|-----|---------|-----------|-------------|
| S0 | `S0_DUE_TODAY_EMAIL` | D0 | ⚠️ Action Required: Your Payment Is Due Today | Pay ₱{{amount_due}} today to avoid late fees and protect your credit record. | `d-39cb3cd90ee44451887c5c67bd1ac073` |
| S1 | `S1_EMAIL_OVERDUE_NOTICE` | D+1 | Overdue: Payment Not Received—Late Fees Apply | {{overdue_days}} day overdue. Pay ₱{{amount_due}} today to avoid account escalation. | `d-8f802444870c4623a750f1502afa45bc` |
| S1 | `S1_EMAIL_STAGE_WARNING` | D+3 | Action Required: Account Escalation Starts If Unpaid | Pay ₱{{amount_due}} today or late fees increase and your account escalates. | `d-72c68f1e83074ea2b183a0e7f3f6c17b` |
| S2 | `S2_EMAIL_ENTRY` | D+4 | Notice: Account Escalated—Late Fees Accruing | ₱{{amount_due}} · {{overdue_days}} days overdue. Pay now to stop further escalation. | `d-9353d39a741d4e51bc3458b4d3f8cea9` |
| S2 | `S2_EMAIL_MID` | D+7 | Follow-Up: Escalation Continues—Balance Unpaid | {{overdue_days}} days overdue. Additional late fees apply each day you remain unpaid. | `d-7c4ee2dceaa34d81b0c3cc518f112064` |
| S2 | `S2_EMAIL_PRE_S3` | D+12 | Warning: Legal Review Starts Soon | Without payment, expect intensified follow-up and escalation to legal review. | `d-fae607f9b20d49d886fd226232973f3f` |
| S3 | `S3_EMAIL_ENTRY` | D+16 | Urgent: Legal Review Initiated—Pay Immediately | Credit reporting warning. Pay ₱{{amount_due}} before further action. | `d-f82a59b63b83485291cb642231cf1b1a` |
| S3 | `S3_EMAIL_MID` | D+23 | Second Notice: Credit Reporting Under Review | {{overdue_days}} days overdue. Pay today to avoid bureau reporting. | `d-d9f66c376bc04408ad80446506120bcf` |
| S3 | `S3_EMAIL_PRE_S4` | D+30 | Final Warning Before Formal Collection | Credit bureau reporting imminent. Pay ₱{{amount_due}} now. | `d-5104ed17e6984b738c98d3e66d67cd37` |
| S4 | `S4_EMAIL_ENTRY` | D+31 | Formal Collection Notice—Pay Full Balance Immediately | Your account is now in formal collection. Pay ₱{{amount_due}} in full—no alternatives offered. | `d-c2ef2c34bc9b41409fd62423a74688c2` |
| S4 | `S4_EMAIL_FINAL_REMINDER` | D+45 | Formal Collection Continues—Final Reminder to Pay | {{overdue_days}} days overdue. MOCASA formal collection is ongoing—pay full balance now. | `d-0db13155d990471287029a120e3dee00` |
| S4 | `S4_EMAIL_MID` | D+60 | Serious Delinquency—Formal Collection Ongoing | {{overdue_days}} days overdue. MOCASA continues formal collection—pay full balance now. | `d-428fd5db13ff4a8c8a2fb8eb0bef4b56` |
| S4 | `S4_EMAIL_PRE_CLOSE` | D+75 | ⚠️ Final Notice: Account Scheduled for Final Review | Before {{assignment_date}}, pay ₱{{amount_due}} in full. Unpaid files enter final delinquency status. | `d-e28ba12db2344c8e9c0a4d9a4e26a107` · 法务审 |

## Nacos / 本地配置

```yaml
channel:
  sendgrid:
    templates:
      S0_DUE_TODAY_EMAIL: d-39cb3cd90ee44451887c5c67bd1ac073
      # … 键名 = scriptSlot，值 = 上表 SendGrid ID
```

> **无 `default-template-id`**：未命中映射时 Adapter 返回 `SENDGRID_NO_TEMPLATE`，避免错发模板。

## 条件 Email（16:00 · Phase 2 Guard）

| Stage | scriptSlot | Subject | Preheader |
|-------|------------|---------|-----------|
| S1 | `S1_EMAIL_CONDITIONAL` | Payment Still Outstanding—Act Today | We have not received ₱{{amount_due}}. Pay now to avoid account escalation. |
| S2 | `S2_EMAIL_CONDITIONAL` | Unpaid Balance—Respond Today | {{overdue_days}} days overdue. Late fees continue to accrue. |
| S3 | `S3_EMAIL_CONDITIONAL` | Immediate Action Required—No Payment Received | ₱{{amount_due}} still unpaid. Pay today to avoid credit bureau reporting. |
| S4 | `S4_EMAIL_CONDITIONAL` | Formal Collection—Pay Full Balance Today | Pay ₱{{amount_due}} in full immediately. |
